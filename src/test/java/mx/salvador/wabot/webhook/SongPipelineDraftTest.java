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
        assertEquals(1, drafts.calls);
        assertEquals("https://youtu.be/exact-version", drafts.versionUrl);
        assertEquals("notes", drive.destination);
        assertTrue(drive.name.startsWith("BORRADOR - Tema"));
        assertEquals("draft-id", pipeline.workState.copies("Tema").get(0).id());
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
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        assertTrue(messages.get(0).contains("Conserve tus ediciones"));
    }

    @Test void researchFailureDoesNotUploadEmptyDocumentOrBreakNotesFlow() {
        drafts.fail = true;
        pipeline.generarNotas("sender", null);
        assertEquals(0, drive.uploads);
        assertTrue(messages.stream().anyMatch(text -> text.contains("No pude crear la base")));
    }

    private static class FakeDraft extends ChordDraftService {
        int calls; boolean fail; String versionUrl;
        @Override public boolean available() { return true; }
        @Override public Document create(String song, String url, String target) throws Exception {
            calls++; versionUrl = url;
            if (fail) throw new java.io.IOException("Cuota agotada");
            return new Document(new byte[]{1, 2}, "C", false);
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
