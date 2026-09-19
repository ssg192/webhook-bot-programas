package mx.salvador.wabot.webhook;

import com.google.api.services.drive.model.File;
import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.drive.DriveService.AudioFile;
import mx.salvador.wabot.media.PitchShifter;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistToneFlowTest {
    private PlaylistToneFlow flow;
    private FakeDrive drive;
    private FakePitch pitch;
    private FakeWhatsApp messages;

    @BeforeEach
    void setup() {
        drive = new FakeDrive();
        pitch = new FakePitch();
        messages = new FakeWhatsApp();
        flow = new PlaylistToneFlow();
        flow.drive = drive;
        flow.shifter = pitch;
        flow.whatsApp = messages;
    }

    @Test
    void lowersSelectedSongsAndUploadsBeforeTrashingExactIds() {
        flow.handle("a", "BAJAR   TONO");
        assertTrue(messages.last().contains("1. Uno.mp3"));
        flow.handle("a", "canción 1 3 1");
        assertTrue(messages.last().contains("¿Cuantos semitonos quieres bajar?"));
        assertTrue(drive.events.isEmpty());
        flow.handle("a", "2 semitonos");
        assertEquals(List.of(-2, -2), pitch.shifts);
        assertEquals(List.of("download:1", "upload:Uno (-2).mp3", "trash:1",
                "download:3", "upload:Tres (-2).m4a", "trash:3"), drive.events);
        assertFalse(drive.trashed.contains("2"));
        assertTrue(pitch.inputs.stream().noneMatch(Files::exists));
        flow.handle("a", "2");
        assertEquals(2, pitch.shifts.size());
    }

    @Test
    void raisesAllWithSeparateSessionsForEachSender() {
        flow.handle("a", "subir tono");
        flow.handle("b", "bajar tono");
        flow.handle("a", "todas");
        flow.handle("b", "2"); // A number cannot select a song accidentally.
        assertTrue(drive.events.isEmpty());
        flow.handle("a", "1");
        assertEquals(List.of(1, 1, 1), pitch.shifts);
    }

    @Test
    void oneSongSkipsSelectionAndInvalidAmountsCanBeRetried() {
        drive.songs = List.of(drive.songs.get(0));
        flow.handle("a", "subir tono");
        assertTrue(messages.last().contains("semitonos"));
        for (String value : List.of("0", "-2", "13", "999999999999999999999999999")) {
            flow.handle("a", value);
            assertTrue(drive.events.isEmpty());
        }
        flow.handle("a", "12");
        assertEquals(List.of(12), pitch.shifts);
    }

    @Test
    void rejectsWholeInvalidSelectionAndAllowsRetry() {
        flow.handle("a", "bajar tono");
        for (String value : List.of("cancion 1 4", "cancion 0", "cancion", "cancion 999999999999999")) {
            flow.handle("a", value);
            assertTrue(messages.last().contains("Elige numeros"));
        }
        flow.handle("a", "cancion 2");
        flow.handle("a", "1");
        assertEquals(Set.of("2"), drive.trashed);
    }

    @Test
    void failedUploadPreservesOriginalAndContinuesOtherSongs() {
        drive.failUpload = "Uno (-2).mp3";
        flow.handle("a", "bajar tono");
        flow.handle("a", "todas");
        flow.handle("a", "2");
        assertEquals(Set.of("2", "3"), drive.trashed);
        assertTrue(messages.last().contains("Uno.mp3: no pude ajustar"));
        assertTrue(messages.last().contains("Tres (-2).m4a: lista"));
    }

    @Test
    void processingFailurePreservesOriginal() {
        pitch.fail = true;
        flow.handle("a", "subir tono");
        flow.handle("a", "cancion 1");
        flow.handle("a", "2");
        assertEquals(List.of("download:1"), drive.events);
        assertTrue(drive.trashed.isEmpty());
        assertTrue(pitch.inputs.stream().noneMatch(Files::exists));
    }

    @Test
    void failedTrashReportsBothFilesInsteadOfClaimingReplacement() {
        drive.failTrash = true;
        flow.handle("a", "subir tono");
        flow.handle("a", "cancion 1");
        flow.handle("a", "2");
        assertTrue(messages.last().contains("subida, pero no pude"));
        assertTrue(drive.trashed.isEmpty());
    }

    @Test
    void menuKeepsOriginalIdsAndRejectsChangedAudio() {
        flow.handle("a", "subir tono");
        drive.songs = List.of(drive.songs.get(2), drive.songs.get(1), drive.songs.get(0));
        drive.changed.add("1");
        flow.handle("a", "cancion 1");
        flow.handle("a", "2");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("Uno.mp3: cambio"));
    }

    @Test
    void cancelledAndEmptySelectionsDoNotModifyFiles() {
        flow.handle("a", "subir tono");
        flow.handle("a", "cancelar");
        flow.handle("a", "cancion 1");
        flow.handle("a", "2");
        assertTrue(drive.events.isEmpty());
        drive.songs = List.of();
        flow.handle("a", "bajar tono");
        assertTrue(messages.last().contains("Aun no hay canciones"));
    }

    @Test
    void routesOnlyStandaloneToneConversationCommands() {
        assertTrue(flow.accepts(" SUBIR TONO "));
        assertTrue(flow.accepts("canción 1 3"));
        assertTrue(flow.accepts("2 semitonos"));
        assertFalse(flow.accepts("https://youtube.com/watch?v=abc tono -2"));
        assertFalse(flow.accepts("version 1"));
        assertFalse(flow.accepts("notas en ti todas"));
        assertFalse(flow.accepts(null));
    }

    @Test
    void namesAccumulateRelativeChanges() {
        assertEquals("Uno (-2).mp3", PlaylistToneFlow.adjustedName("Uno.mp3", -2));
        assertEquals("Uno.mp3", PlaylistToneFlow.adjustedName("Uno (-2).mp3", 2));
        assertEquals("Uno (-3).mp3", PlaylistToneFlow.adjustedName("Uno (-2).mp3", -1));
        assertEquals("Uno (vivo) (+2).m4a", PlaylistToneFlow.adjustedName("Uno (vivo).m4a", 2));
    }

    private static class FakeDrive extends DriveService {
        List<AudioFile> songs = List.of(new AudioFile("1", "Uno.mp3", 1L),
                new AudioFile("2", "Dos.mp3", 1L), new AudioFile("3", "Tres.m4a", 1L));
        List<String> events = new ArrayList<>();
        Set<String> trashed = new HashSet<>();
        Set<String> changed = new HashSet<>();
        String failUpload;
        boolean failTrash;

        @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) {
            return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link");
        }
        @Override public List<AudioFile> listAudioFiles(String parentId) { return songs; }
        @Override public boolean audioUnchanged(AudioFile audio, String parentId) {
            return !changed.contains(audio.id()) && !trashed.contains(audio.id());
        }
        @Override public void downloadAudio(String id, Path path) throws Exception {
            events.add("download:" + id);
            Files.write(path, new byte[]{1, 2, 3});
        }
        @Override public File uploadNewAudio(Path path, String name, String parentId) throws Exception {
            events.add("upload:" + name);
            if (name.equals(failUpload)) throw new IOException("Simulated upload failure");
            assertTrue(Files.size(path) > 0);
            return new File().setId("new-" + name);
        }
        @Override public void trashFile(String id) throws Exception {
            events.add("trash:" + id);
            if (failTrash) throw new IOException("Simulated trash failure");
            trashed.add(id);
        }
    }

    private static class FakePitch extends PitchShifter {
        List<Integer> shifts = new ArrayList<>();
        List<Path> inputs = new ArrayList<>();
        boolean fail;
        @Override public Path shift(Path input, int semitones) throws IOException {
            inputs.add(input);
            shifts.add(semitones);
            if (fail) throw new IOException("Simulated processing failure");
            return Files.copy(input, input.resolveSibling("shifted" + input.getFileName()));
        }
    }

    private static class FakeWhatsApp extends WhatsAppService {
        List<String> replies = new ArrayList<>();
        @Override public void replyText(String to, String body) { replies.add(body); }
        String last() { return replies.get(replies.size() - 1); }
    }
}
