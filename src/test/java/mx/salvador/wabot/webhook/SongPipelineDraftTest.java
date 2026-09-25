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

    @Test void ambiguousArtistsWaitAndKeepStableOptionsUntilSelection() {
        drafts.ambiguous = true;
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "D");
        assertEquals(0, drive.uploads);
        assertTrue(messages.get(messages.size() - 1).contains("Marco Barrientos"));
        assertFalse(messages.get(messages.size() - 1).contains("Escribe"));
        assertFalse(pipeline.artistSelectionMatches("sender", "la de Marco", 1));
        assertTrue(pipeline.artistSelectionMatches("sender", "la de Barrientos", 1));
        assertFalse(pipeline.artistSelectionMatches("sender", "la de Barrientos", 2));
        assertTrue(pipeline.artistSelectionMatches("sender", "la primera que dijiste", 1));
        var token = pipeline.artistQuestionToken("sender");
        pipeline.context("sender", List.of("Tema.m4a"));
        pipeline.repeatArtistQuestion("sender");
        assertSame(token, pipeline.artistQuestionToken("sender"));
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "artist:1");
        assertEquals(1, drive.uploads);
        assertTrue(drive.name.contains("Barrientos"));
        assertEquals("D", drafts.selectedKey);
        assertEquals(1, drafts.calls);
        assertNull(pipeline.artistQuestionToken("sender"));
    }

    @Test void cancelledArtistChoiceCannotUploadOnLateReply() {
        drafts.ambiguous = true;
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "original");
        var old = pipeline.pendingDraftChoice("sender");
        pipeline.cancelNoteChoice("sender");
        pipeline.chooseDraft("sender", old, "artist:1");
        assertEquals(0, drive.uploads);
        assertNull(pipeline.artistQuestionToken("sender"));
    }

    @Test void declinedSongIsNotOfferedAgainInGeneralRequestButExplicitRequestCanChangeIt() {
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "decline");
        messages.clear();
        pipeline.generarNotas("sender", null);
        assertNull(pipeline.pendingDraftChoice("sender"));
        assertTrue(messages.stream().noneMatch(s -> s.contains("¿Quieres buscar")));
        assertTrue(messages.stream().anyMatch(s -> s.contains("antes pediste no crear notas")));
        assertEquals(0, drafts.calls);
        pipeline.generarNotas("sender", "Tema.m4a");
        assertNotNull(pipeline.pendingDraftChoice("sender"));
    }

    @Test void registeredPresentNotesAreNotCopiedOrOfferedAgain() {
        pipeline.workState.copied("Tema", "previous", "Tema.pdf", "notes");
        drive.present = true;
        pipeline.generarNotas("sender", null);
        assertEquals(0, drafts.calls);
        assertEquals(0, drive.uploads);
        assertNull(pipeline.pendingDraftChoice("sender"));
        drive.present = false;
        pipeline.generarNotas("sender", null);
        assertNotNull(pipeline.pendingDraftChoice("sender"));
    }

    @Test void generalRequestOnlyOffersRemainingSongsAndReportsAllDone() {
        drive.songs = List.of("Tema.m4a", "Otra.m4a");
        drive.present = true;
        pipeline.workState.copied("Tema", "one", "Tema.pdf", "notes");
        pipeline.generarNotas("sender", null);
        assertEquals("Otra", pipeline.pendingDraftChoice("sender").song());
        pipeline.cancelNoteChoice("sender");
        pipeline.workState.copied("Otra", "two", "Otra.pdf", "notes");
        messages.clear();
        pipeline.generarNotas("sender", null);
        assertNull(pipeline.pendingDraftChoice("sender"));
        assertTrue(messages.stream().anyMatch(s -> s.contains("Las 2 canciones ya tienen notas")));
        assertEquals(0, drive.uploads);
        assertEquals(0, drafts.calls);
    }

    @Test void manualNotesAreRefreshedAndPreventDuplicateCreation() {
        var folder = drive.ensureSundayStructure(LocalDate.now());
        drive.manual = List.of(new File().setId("manual").setName("Tema.pdf").setMimeType("application/pdf"));
        pipeline.refreshDriveContext("sender", drive.songs, folder);
        assertEquals(1L, pipeline.context("sender", drive.songs).get("conNotasConfirmadas"));
        pipeline.generarNotas("sender", null);
        assertNull(pipeline.pendingDraftChoice("sender"));
        assertEquals(0, drafts.calls);
        drive.manual = List.of();
        pipeline.refreshDriveContext("sender", drive.songs, folder);
        assertEquals(0L, pipeline.context("sender", drive.songs).get("conNotasConfirmadas"));
        pipeline.generarNotas("sender", null);
        assertNotNull(pipeline.pendingDraftChoice("sender"));
    }

    @Test void pendingKeyQuestionIsPreservedWithoutRepeatingMenu() {
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "search");
        var pending = pipeline.pendingDraftChoice("sender");
        messages.clear();
        pipeline.generarNotas("sender", null);
        assertSame(pending, pipeline.pendingDraftChoice("sender"));
        assertTrue(messages.stream().noneMatch(s -> s.contains("¿Quieres buscar") || s.contains("Sin notas en el historico")));
    }

    @Test void screenshotScenarioRecognizesExistingPdfAndOffersMissingSong() {
        drive.songs = List.of("No Puedo Parar.m4a", "El Dios que Adoramos - Gracia Soberana.m4a");
        drive.manual = List.of(DriveNoteInventoryTest.pdf("manual", "El Dios que Adoramos.pdf"));
        pipeline.generarNotas("sender", null);
        assertEquals("No Puedo Parar", pipeline.pendingDraftChoice("sender").song());
        assertEquals(1L, pipeline.context("sender", drive.songs).get("conNotasConfirmadas"));
        assertFalse(messages.stream().anyMatch(s -> s.contains("Conservo tus decisiones")));
    }

    @Test void unrelatedUnassignedPdfDoesNotBlockExplicitRequest() {
        drive.manual = List.of(DriveNoteInventoryTest.pdf("manual", "Archivo desconocido.pdf"));
        pipeline.generarNotas("sender", "Tema.m4a");
        assertEquals("Tema", pipeline.pendingDraftChoice("sender").song());
        assertTrue(pipeline.workState.copies("Tema").isEmpty());
    }

    @Test void ambiguousPdfOnlyBlocksItsCandidateSongs() {
        drive.songs = List.of("Tema.m4a", "Tema especial.m4a", "Otra.m4a");
        drive.manual = List.of(DriveNoteInventoryTest.pdf("manual", "Tema especial.pdf"));
        pipeline.generarNotas("sender", null);
        assertEquals("Otra", pipeline.pendingDraftChoice("sender").song());
        assertTrue(messages.stream().anyMatch(s -> s.contains("coinciden con varias canciones")));
        assertFalse(messages.stream().anyMatch(s -> s.contains("Conservo tus decisiones")));
        assertTrue(pipeline.workState.copies("Tema").isEmpty());
        assertTrue(pipeline.workState.copies("Tema especial").isEmpty());
    }

    @Test void dailyDecisionSurvivesRestartAndIsScopedToSender(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var store = new ContextStore();
        store.json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        store.filename = dir.resolve("context.json").toString();
        pipeline.contextStore = store;
        pipeline.generarNotas("sender", null);
        pipeline.chooseDraft("sender", pipeline.pendingDraftChoice("sender"), "decline");
        var restored = new SongPipeline();
        restored.contextStore = store;
        restored.driveService = drive;
        restored.chordDrafts = drafts;
        restored.whatsApp = pipeline.whatsApp;
        restored.lyricsHistory = pipeline.lyricsHistory;
        try {
            restored.init();
            restored.generarNotas("sender", null);
            assertNull(restored.pendingDraftChoice("sender"));
            restored.generarNotas("other", null);
            assertNotNull(restored.pendingDraftChoice("other"));
        } finally { restored.shutdown(); }
    }

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
        int calls; boolean fail, ambiguous; String versionUrl, targetKey, selectedKey;
        @Override Document createSelected(String song, String url, String key, ArtistChoiceRequired choice, int index) throws Exception {
            selectedKey = key;
            return render(song, url, new Draft(choice.options.get(index).reference(), key,
                    List.of(new Section("Coro", List.of("D", "A"), 1)), choice.sources), "");
        }
        @Override public boolean available() { return true; }
        @Override Draft research(String song, String url, String target) throws Exception {
            calls++; versionUrl = url; targetKey = target;
            if (ambiguous) throw new ArtistChoiceRequired(List.of(new ArtistOption("Tema - Marco Barrientos", 1),
                    new ArtistOption("Tema - Marco Otro", 2)), List.of(new Source("A", "https://a.example/song", "D A"), new Source("B", "https://b.example/song", "D A")));
            if (fail) throw new java.io.IOException("Cuota agotada");
            return new Draft("Tema - Original", "C", List.of(new Section("Coro", List.of("C", "G"), 1)),
                    List.of(new Source("Tema", "https://fuente.example/song", "Key: C. C G")));
        }
    }
    private static class FakeDrive extends DriveService {
        int uploads; String destination, name; File existing; boolean present;
        List<String> songs = List.of("Tema.m4a");
        List<File> manual = List.of();
        @Override public List<File> listFolderFiles(String folder) { return folder.equals("notes") ? manual : List.of(); }
        @Override public boolean noteCopyPresent(String id, String folder) { return present; }
        @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) { return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link"); }
        @Override public List<String> listMp3Names(String folder) { return songs; }
        @Override public File findFile(String name, String folder) { return existing; }
        @Override public File copyTo(String id, String name, String folder) { return new File().setId("copied-id"); }
        @Override public File uploadBytes(String name, byte[] bytes, String mime, String folder) {
            uploads++; this.name = name; destination = folder;
            assertEquals(DOCX_MIME, mime);
            return new File().setId("draft-id").setWebViewLink("draft-link");
        }
    }
}
