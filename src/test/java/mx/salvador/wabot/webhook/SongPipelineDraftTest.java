package mx.salvador.wabot.webhook;

import com.google.api.services.drive.model.File;
import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.media.LyricsHistoryService;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SongPipelineDraftTest {
    private final SongPipeline pipeline = new SongPipeline();
    private final FakeDrive drive = new FakeDrive();
    private final FakeDraft drafts = new FakeDraft();
    private final List<String> messages = new ArrayList<>();
    private final Map<String, LyricsHistoryService.SongRef> refs = new HashMap<>();
    SongPipelineDraftTest() {
        pipeline.driveService = drive;
        pipeline.chordDrafts = drafts;
        pipeline.whatsApp = new WhatsAppService() { @Override public void replyText(String to, String body) { messages.add(body); } };
        pipeline.lyricsHistory = new LyricsHistoryService() {
            @Override public Map<String, SongRef> refs(List<String> songs) { return refs; }
        };
    }
    @AfterEach void close() { pipeline.shutdown(); }

    @Test void missingHistoryGeneratesDraftInNotesAndRegistersItsId() {
        pipeline.workState.reference("Tema", "https://youtu.be/exact-version");
        pipeline.generarNotas("sender", null);
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "original");
        assertEquals(1, drafts.calls);
        assertEquals("https://youtu.be/exact-version", drafts.versionUrl);
        assertEquals("notes", drive.destination);
        assertTrue(drive.name.startsWith("BORRADOR - Tema"));
        assertEquals("draft-id", pipeline.workState.copies("Tema").get(0).id());
    }

    @Test void decliningDoesNotSearchOrCreateAnyFile() {
        pipeline.generarNotas("sender", null);
        assertEquals(0, drafts.calls);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "decline");
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        assertNull(pipeline.pendingDraftChoice("sender"));
    }

    @Test void decliningAtKeyQuestionDoesNotSearchOrCreateDocument() {
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "decline");
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
    }

    @Test void simpleKeyRepliesDoNotNeedAnotherAiCall() {
        assertEquals("D", SongPipeline.draftKeyReply("en Re"));
        assertEquals("F#m", SongPipeline.draftKeyReply("Fa sostenido menor"));
        assertEquals("Bb", SongPipeline.draftKeyReply("Bb"));
        assertEquals("D", SongPipeline.draftKeyReply("D"));
        assertEquals("D#", SongPipeline.draftKeyReply("D#"));
        assertEquals("D#", SongPipeline.draftKeyReply("Re sostenido"));
        assertEquals("Dm", SongPipeline.draftKeyReply("Dm"));
        assertNull(SongPipeline.draftKeyReply("baja esa cancion"));
    }

    @Test void originalVariantsAreHandledLocallyOnlyAtTheKeyQuestion() {
        pipeline.generarNotas("sender", null);
        var permission = pipeline.pendingDraftChoice("sender");
        assertNull(pipeline.draftDecision("el original", permission));
        pipeline.chooseDraft("sender", permission, "search");
        for (String text : List.of("original", "el original", "El tono original.", "en el original", "conserva el original", "en la original", "la original", "en la tonalidad original"))
            assertEquals("original", pipeline.draftDecision(text, pipeline.pendingDraftChoice("sender")));
        assertNull(pipeline.draftDecision("no quiero el original", pipeline.pendingDraftChoice("sender")));
        assertEquals(0, drafts.calls);
    }

    @Test void staleApprovalCannotSearchOrCreateAndCancellationDiscardsChoice() {
        pipeline.generarNotas("sender", null);
        var permission = pipeline.pendingDraftChoice("sender");
        pipeline.chooseDraft("other", permission, "search");
        assertEquals(0, drafts.calls);
        pipeline.chooseDraft("sender", permission, "D");
        assertEquals(0, drafts.calls); // Elegir tono no equivale a autorizar buscar.
        pipeline.chooseDraft("sender", permission, "search");
        pipeline.chooseDraft("sender", permission, "original");
        assertEquals(0, drive.uploads);
        var keyQuestion = pipeline.pendingDraftChoice("sender");
        pipeline.cancelNoteChoice("sender");
        pipeline.chooseDraft("sender", keyQuestion, "original");
        assertEquals(0, drive.uploads);
    }

    @Test void chosenKeyIsIncludedInResearchAndDoesNotTouchAudio() {
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "D");
        assertEquals(1, drafts.calls);
        assertEquals("D", drafts.targetKey);
        assertEquals(1, drive.uploads);
        assertTrue(drive.name.endsWith(" - D.docx"));
        assertEquals("notes", drive.destination);
    }

    @Test void existingHistoricalNotesNeverInvokeWeb() {
        var ref = new LyricsHistoryService.SongRef();
        ref.acordeorios.add(new LyricsHistoryService.Acordeorio("historic-id", "Tema.pdf"));
        refs.put("Tema", ref);
        pipeline.generarNotas("sender", null);
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        assertEquals("copied-id", pipeline.workState.copies("Tema").get(0).id());
    }

    @Test void existingDraftIsNotOverwrittenOrResearchedAgain() {
        drive.existing = new File().setId("edited-draft").setWebViewLink("edited-link");
        pipeline.createNoteDraft("sender", "Tema", "D", drive.ensureSundayStructure(LocalDate.now()));
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "D");
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        assertTrue(messages.stream().anyMatch(text -> text.contains("Conserve el DOCX")));
    }

    @Test void researchFailureDoesNotUploadEmptyDocumentOrBreakNotesFlow() {
        drafts.fail = true;
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "original");
        assertEquals(0, drive.uploads);
        assertTrue(messages.stream().anyMatch(text -> text.contains("No cree ningun documento")));
    }

    private static class FakeDraft extends ChordDraftService {
        int calls; boolean fail; String versionUrl, targetKey;
        @Override public boolean available() { return true; }
        @Override Draft research(String song, String url, String target) throws Exception {
            calls++; versionUrl = url; targetKey = target;
            if (fail) throw new java.io.IOException("Cuota agotada");
            return new Draft("Tema - Original", "C", List.of(new Section("Coro", List.of("C", "G"), 1)),
                    List.of(new Source("Tema", "https://fuente.example/song", "Key: C. C G")));
        }
    }
    private static class FakeDrive extends DriveService {
        int uploads; String destination, name; File existing;
        @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) { return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link"); }
        @Override public List<String> listMp3Names(String folder) { return List.of("Tema.m4a"); }
        @Override public File findFile(String name, String folder) { return existing; }
        @Override public File copyTo(String id, String name, String folder) { return new File().setId("copied-id"); }
        @Override public File uploadBytes(String name, byte[] bytes, String mime, String folder) {
            uploads++; this.name = name; destination = folder;
            assertEquals(DOCX_MIME, mime);
            return new File().setId("draft-id").setWebViewLink("draft-link");
        }
    }
}
