package mx.salvador.wabot.webhook;

import com.google.api.services.drive.model.File;
import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.media.PitchShifter;
import mx.salvador.wabot.media.YtDlpDownloader;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SongPipelineInstructionsTest {
    private static final String FIRST = "https://youtu.be/abcdefghi01";
    private static final String SECOND = "https://youtu.be/abcdefghi02";
    @TempDir Path temp;
    private List<String> events;
    private TestPipeline pipeline;
    private FakeDrive drive;
    private FakeDownload download;
    private FakeInterpreter ai;
    private FakePitch pitch;
    private List<String> replies;

    @BeforeEach
    void setup() {
        events = Collections.synchronizedList(new ArrayList<>());
        replies = Collections.synchronizedList(new ArrayList<>());
        drive = new FakeDrive();
        download = new FakeDownload();
        pitch = new FakePitch();
        ai = new FakeInterpreter();
        pipeline = new TestPipeline();
        var flow = new PlaylistToneFlow();
        var whatsapp = new WhatsAppService() {
            @Override public void replyText(String to, String body) { replies.add(body); }
        };
        flow.drive = drive;
        flow.interpreter = ai;
        flow.whatsApp = whatsapp;
        flow.shifter = pitch;
        flow.pipeline = pipeline;
        pipeline.downloader = download;
        pipeline.driveService = drive;
        pipeline.pitchShifter = pitch;
        pipeline.playlistTone = flow;
        pipeline.whatsApp = whatsapp;
        pipeline.allowedNumbers = Optional.empty();
        pipeline.downloadConcurrency = 2;
        pipeline.init();
    }

    @AfterEach
    void stopWorkers() { pipeline.shutdown(); }

    @ParameterizedTest
    @ValueSource(strings = {"notas y letras porfa\n%s\n%s", "%s\n%s\ncrea las canciones y su letra"})
    void linksWithNaturalRequestDownloadBeforeGeneratingDocuments(String format) throws Exception {
        String text = format.formatted(FIRST, SECOND);
        pipeline.handleIncoming(new WebhookPayload.Message("message-id", "525500000000", "text",
                new WebhookPayload.Text(text)));
        assertTrue(pipeline.documentsDone.await(5, TimeUnit.SECONDS));
        assertEquals(2, drive.songs.size());
        assertEquals(4, events.size());
        assertTrue(events.get(0).startsWith("upload:"));
        assertTrue(events.get(1).startsWith("upload:"));
        assertEquals(List.of("notes", "lyrics"), events.subList(2, 4));
        assertEquals("after_upload", ai.pendingAction);
        assertFalse(ai.message.contains("https://"));
        assertEquals(2, ai.names.size());
        assertTrue(replies.stream().anyMatch(reply -> reply.startsWith("Subidas a")));
        assertTrue(replies.stream().noneMatch(reply -> reply.contains("Manda \"letras\"")));
    }

    @Test
    void partialFailureUsesOnlySuccessfulUploadsForFollowup() {
        download.failed = SECOND;
        pipeline.process("a", List.of(FIRST, SECOND), 0, FIRST + " " + SECOND + " prepara las letras");
        assertEquals(1, ai.names.size());
        assertEquals(1, drive.songs.size());
        assertEquals(List.of("upload:Cancion01.m4a", "notes", "lyrics"), events);
        assertTrue(replies.stream().anyMatch(reply -> reply.contains("No se pudieron descargar")));
    }

    @Test
    void noDocumentsIfAllDownloadsFail() {
        download.failed = FIRST;
        pipeline.process("a", List.of(FIRST), 0, FIRST + " preparame las letras");
        assertEquals(0, ai.calls);
        assertTrue(events.isEmpty());
    }

    @Test
    void bareLinksNeverCallGemini() {
        pipeline.process("a", List.of(FIRST), 0, FIRST);
        assertEquals(0, ai.calls);
        assertEquals(List.of("upload:Cancion01.m4a"), events);
    }

    @Test
    void legacyPitchIsAppliedOnceAndRemainingRequestIsPreserved() {
        pipeline.process("a", List.of(FIRST), -2, FIRST + " tono -2 y preparame las letras");
        assertEquals(List.of(-2), pitch.shifts);
        assertEquals("y preparame las letras", ai.message);
        assertEquals("after_upload", ai.pendingAction);
    }

    @Test
    void disabledAiDoesNotBlockDownloads() {
        ai.enabledForTest = false;
        pipeline.process("a", List.of(FIRST), 0, FIRST + " preparame letras");
        assertEquals(0, ai.calls);
        assertEquals(1, drive.songs.size());
    }

    @Test
    void onlyUploadWordingDoesNotProduceAnUnrelatedHelpReply() {
        ai.intent = "unrelated";
        pipeline.process("a", List.of(FIRST), 0, "subeme esta " + FIRST);
        assertEquals(List.of("upload:Cancion01.m4a"), events);
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).startsWith("Subidas a"));
    }

    private class TestPipeline extends SongPipeline {
        @Override void requestDocuments(String from, boolean notes, boolean lyrics, String song) {
            if (notes) generarNotas(from, song);
            if (lyrics) generarLetras(from);
        }
        final CountDownLatch documentsDone = new CountDownLatch(1);
        @Override void generarNotas(String from, String selected) { events.add("notes"); }
        @Override void generarLetras(String from) {
            events.add("lyrics");
            documentsDone.countDown();
        }
    }

    private class FakeDownload extends YtDlpDownloader {
        String failed;
        @Override public Descarga downloadMp3(String url) throws IOException {
            if (url.equals(failed)) throw new IOException("Simulated download failure");
            String name = "Cancion" + url.substring(url.length() - 2);
            Path dir = Files.createTempDirectory(temp, "download-");
            return new Descarga(Files.write(dir.resolve(name + ".m4a"), new byte[]{1, 2, 3}), name);
        }
    }

    private class FakeDrive extends DriveService {
        final List<AudioFile> songs = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger ids = new AtomicInteger();
        @Override public EstructuraDomingo findSundayStructure(LocalDate date) {
            return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link");
        }
        @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) {
            return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link");
        }
        @Override public File uploadMp3(Path path, String parentId) {
            String id = "song-" + ids.incrementAndGet();
            String name = path.getFileName().toString();
            songs.add(new AudioFile(id, name, 1L));
            events.add("upload:" + name);
            return new File().setId(id).setName(name);
        }
        @Override public List<AudioFile> listAudioFiles(String parentId) { return List.copyOf(songs); }
        @Override public List<String> listMp3Names(String parentId) { return songs.stream().map(AudioFile::name).toList(); }
        @Override public List<com.google.api.services.drive.model.File> listFolderFiles(String folder) { return List.of(); }
        @Override public File findFile(String name, String parentId) { return null; }
        @Override public List<String> trashVariants(String parentId, String baseName, String exceptName) { return List.of(); }
    }

    private static class FakePitch extends PitchShifter {
        final List<Integer> shifts = Collections.synchronizedList(new ArrayList<>());
        @Override public Path shift(Path input, int semitones) throws IOException {
            shifts.add(semitones);
            return Files.copy(input, input.resolveSibling("Ajustada.m4a"));
        }
    }

    private static class FakeInterpreter extends GeminiSongInterpreter {
        boolean enabledForTest = true;
        int calls;
        String message;
        String pendingAction;
        List<String> names;
        String intent = "notes_lyrics";
        @Override public boolean available() { return enabledForTest; }
        @Override public Interpretation interpret(String message, List<String> names, int selected,
                List<String> history, String action, SongPipeline.NoteChoice choice, java.util.Map<String, Object> state) {
            return interpret(message, names, selected, history, action);
        }
        @Override public Interpretation interpret(String message, List<String> names, int selected,
                                                 List<String> history, String pendingAction) {
            calls++;
            this.message = message;
            this.names = names;
            this.pendingAction = pendingAction;
            return new Interpretation(intent, 0, 0);
        }
    }
}
