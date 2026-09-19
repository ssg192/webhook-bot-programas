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
    private FakePipeline pipeline;

    @BeforeEach
    void setup() {
        drive = new FakeDrive();
        pitch = new FakePitch();
        messages = new FakeWhatsApp();
        flow = new PlaylistToneFlow();
        flow.drive = drive;
        flow.shifter = pitch;
        flow.whatsApp = messages;
        flow.interpreter = new GeminiSongInterpreter(); // Desactivado por defecto.
        pipeline = new FakePipeline();
        flow.pipeline = pipeline;
    }

    @Test
    void lowersOnlySelectedSongAndUploadsBeforeTrashingExactId() {
        flow.handle("a", "CAMBIAR   TONALIDAD");
        assertTrue(messages.last().contains("1. Uno.mp3"));
        flow.handle("a", "canción 1");
        assertTrue(messages.last().contains("¿Que quieres hacer con esta cancion?"));
        assertTrue(drive.events.isEmpty());
        flow.handle("a", "bajar 2 semitonos");
        assertEquals(List.of(-2), pitch.shifts);
        assertEquals(List.of("download:1", "upload:Uno (-2).mp3", "trash:1"), drive.events);
        assertEquals(Set.of("1"), drive.trashed);
        assertTrue(pitch.inputs.stream().noneMatch(Files::exists));
        flow.handle("a", "bajar 2");
        assertEquals(1, pitch.shifts.size());
    }

    @Test
    void keepsSeparateSelectionsAndDirectionsForEachSender() {
        flow.handle("a", "cambiar tonalidad");
        flow.handle("b", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handle("b", "2"); // A number cannot select a song accidentally.
        assertTrue(drive.events.isEmpty());
        flow.handle("a", "subir 1");
        flow.handle("b", "cancion 2");
        flow.handle("b", "bajar 2");
        assertEquals(List.of(1, -2), pitch.shifts);
        assertEquals(Set.of("1", "2"), drive.trashed);
    }

    @Test
    void oneSongSkipsSelectionAndInvalidAmountsCanBeRetried() {
        drive.songs = List.of(drive.songs.get(0));
        flow.handle("a", "cambiar tonalidad");
        assertTrue(messages.last().contains("semitonos"));
        for (String value : List.of("2", "subir 0", "bajar -2", "subir 13", "subir 999999999999999999999999999")) {
            flow.handle("a", value);
            assertTrue(drive.events.isEmpty());
        }
        flow.handle("a", "subir 12");
        assertEquals(List.of(12), pitch.shifts);
    }

    @Test
    void rejectsWholeInvalidSelectionAndAllowsRetry() {
        flow.handle("a", "cambiar tonalidad");
        for (String value : List.of("todas", "cancion 1 3", "cancion 1 4", "cancion 0", "cancion", "cancion 999999999999999")) {
            flow.handle("a", value);
            assertTrue(messages.last().contains("Elige una sola cancion"));
            assertTrue(drive.events.isEmpty());
        }
        flow.handle("a", "cancion 2");
        flow.handle("a", "bajar 1");
        assertEquals(Set.of("2"), drive.trashed);
    }

    @Test
    void failedUploadPreservesOriginalAndAllowsAnotherSongWithDifferentAdjustment() {
        drive.failUpload = "Uno (-2).mp3";
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handle("a", "bajar 2");
        assertTrue(drive.trashed.isEmpty());
        assertTrue(messages.last().contains("Uno.mp3: no pude ajustar"));
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 3");
        flow.handle("a", "subir 1");
        assertEquals(Set.of("3"), drive.trashed);
        assertEquals(List.of(-2, 1), pitch.shifts);
        assertTrue(messages.last().contains("Tres (+1).m4a: lista"));
    }

    @Test
    void processingFailurePreservesOriginal() {
        pitch.fail = true;
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handle("a", "subir 2");
        assertEquals(List.of("download:1"), drive.events);
        assertTrue(drive.trashed.isEmpty());
        assertTrue(pitch.inputs.stream().noneMatch(Files::exists));
    }

    @Test
    void failedTrashReportsBothFilesInsteadOfClaimingReplacement() {
        drive.failTrash = true;
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handle("a", "subir 2");
        assertTrue(messages.last().contains("subida, pero no pude"));
        assertTrue(drive.trashed.isEmpty());
    }

    @Test
    void menuKeepsOriginalIdsAndRejectsChangedAudio() {
        flow.handle("a", "cambiar tonalidad");
        drive.songs = List.of(drive.songs.get(2), drive.songs.get(1), drive.songs.get(0));
        drive.changed.add("1");
        flow.handle("a", "cancion 1");
        flow.handle("a", "subir 2");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("Uno.mp3: cambio"));
    }

    @Test
    void cancelledAndEmptySelectionsDoNotModifyFiles() {
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancelar");
        flow.handle("a", "cancion 1");
        flow.handle("a", "subir 2");
        assertTrue(drive.events.isEmpty());
        drive.songs = List.of();
        flow.handle("a", "cambiar tonalidad");
        assertTrue(messages.last().contains("Aun no hay canciones"));
    }

    @Test
    void routesOnlyStandaloneToneConversationCommands() {
        assertTrue(flow.accepts(" CAMBIAR TONALIDAD "));
        assertTrue(flow.accepts("subir 1"));
        assertTrue(flow.accepts("bajar 2 semitonos"));
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

    @Test
    void naturalInstructionUsesExistingSongAndSafeReplacement() {
        var ai = new FakeInterpreter("tone", 2, -2);
        flow.interpreter = ai;
        flow.handleNatural("a", "bajale dos a la segunda");
        assertEquals(List.of(-2), pitch.shifts);
        assertEquals(Set.of("2"), drive.trashed);
        assertEquals(List.of("Uno.mp3", "Dos.mp3", "Tres.m4a"), ai.names);
    }

    @Test
    void naturalFollowupReceivesSelectedSongContext() {
        var ai = new FakeInterpreter("tone", 3, 1);
        flow.interpreter = ai;
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 3");
        flow.handleNatural("a", "subela un semitono");
        assertEquals(3, ai.selected);
        assertEquals(Set.of("3"), drive.trashed);
    }

    @Test
    void ambiguousOrMultipleNaturalTargetsDoNotChangeAudio() {
        flow.interpreter = new FakeInterpreter("clarify", 0, 0);
        flow.handleNatural("a", "baja la primera y sube la otra");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("¿A que cancion"));
    }

    @Test
    void naturalSelectionWithoutAmountAsksBeforeChanging() {
        flow.interpreter = new FakeInterpreter("tone", 2, 0);
        flow.handleNatural("a", "quiero cambiar la segunda");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("Dos.mp3"));
        flow.handle("a", "bajar 1");
        assertEquals(Set.of("2"), drive.trashed);
    }

    @Test
    void providerFailureKeepsManualCommandsAndSelectionWorking() {
        var ai = new FakeInterpreter("tone", 1, -2);
        ai.fail = true;
        flow.interpreter = ai;
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handleNatural("a", "bajala dos");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("No pude interpretar"));
        flow.handle("a", "bajar 2");
        assertEquals(List.of(-2), pitch.shifts);
    }

    @Test
    void quotaErrorExplainsFailureWithoutBlamingUserWording() {
        flow.interpreter = new FakeInterpreter("tone", 1, 2) {
            @Override public Interpretation interpret(String message, List<String> songs, int selected) throws Exception {
                throw GeminiSongInterpreter.httpFailure(429);
            }
        };
        flow.handleNatural("a", "sube esa a dos semitonos");
        assertTrue(messages.last().contains("cuota"));
        assertTrue(messages.last().contains("HTTP 429"));
        assertFalse(messages.last().contains("Usa cambiar tonalidad"));
        assertTrue(drive.events.isEmpty());
    }

    @Test
    void cancellationDuringInterpretationDoesNotApplyOldResult() {
        var ai = new FakeInterpreter("tone", 1, -2);
        ai.duringInterpret = () -> flow.handle("a", "cancelar");
        flow.interpreter = ai;
        flow.handle("a", "cambiar tonalidad");
        flow.handleNatural("a", "bajala dos");
        assertTrue(drive.events.isEmpty());
    }

    @Test
    void standaloneNaturalRequestCanAlsoBeCancelledWhileWaitingForProvider() {
        var ai = new FakeInterpreter("tone", 1, -2);
        ai.duringInterpret = () -> flow.handle("a", "cancelar");
        flow.interpreter = ai;
        flow.handleNatural("a", "bajale dos a la primera");
        assertTrue(drive.events.isEmpty());
    }

    @Test
    void notesFollowupInvokesExistingNotesWorkflow() {
        flow.interpreter = new FakeInterpreter("notes", 0, 0);
        flow.handleNatural("a", "y las notas?");
        assertEquals(1, pipeline.notesRequests.size());
        assertNull(pipeline.notesRequests.get(0));
        assertTrue(drive.events.isEmpty());
    }

    @Test
    void notesForSpecificSongAreScopedToThatSong() {
        flow.interpreter = new FakeInterpreter("notes", 2, 0);
        flow.handleNatural("a", "traeme las notas de la segunda");
        assertEquals(List.of("Dos.mp3"), pipeline.notesRequests);
    }

    @Test
    void combinedNotesAndToneRequestDoesBothOnce() {
        flow.interpreter = new FakeInterpreter("tone_notes", 1, 2);
        flow.handleNatural("a", "crea las notas y sube esa a dos semitonos");
        assertEquals(1, pipeline.notesRequests.size());
        assertEquals(List.of(2), pitch.shifts);
        assertEquals(Set.of("1"), drive.trashed);
    }

    @Test
    void removalAfterAdjustmentTargetsNewFileAndKeepsContextAcrossNotes() {
        flow.handle("a", "cambiar tonalidad");
        flow.handle("a", "cancion 1");
        flow.handle("a", "subir 2");
        flow.interpreter = new FakeInterpreter("notes", 0, 0);
        flow.handleNatural("a", "y las notas?");
        var ai = new FakeInterpreter("remove", 3, 0);
        flow.interpreter = ai;
        flow.handleNatural("a", "elimina esa cancion de la playlist");
        assertEquals(3, ai.selected); // Dos, Tres, Uno (+2): el ID nuevo esta al final.
        assertEquals("Uno (+2).mp3", ai.names.get(2));
        assertEquals(Set.of("1", "new-Uno (+2).mp3"), drive.trashed);
        assertTrue(messages.last().contains("se puede recuperar"));
    }

    @Test
    void recentUploadContextIsPerSenderAndCanBeClearedForMultipleUploads() {
        flow.rememberSong("a", "2");
        var ai = new FakeInterpreter("tone", 0, 0);
        flow.interpreter = ai;
        flow.handleNatural("a", "sube esa");
        assertEquals(2, ai.selected);
        flow.handleNatural("b", "sube esa");
        assertEquals(0, ai.selected);
        flow.handle("a", "cancelar");
        flow.rememberSong("a", null);
        flow.handleNatural("a", "sube esa");
        assertEquals(0, ai.selected);
    }

    @Test
    void missingRemovalTargetAsksThenKeepsRemovalIntentForNaturalSelection() {
        flow.interpreter = new FakeInterpreter("remove", 0, 0);
        flow.handleNatural("a", "quita esa cancion");
        assertTrue(drive.events.isEmpty());
        assertTrue(messages.last().contains("quieres quitar"));
        var ai = new FakeInterpreter("remove", 2, 0);
        flow.interpreter = ai;
        flow.handleNatural("a", "la segunda");
        assertEquals("remove", ai.pendingAction);
        assertEquals(Set.of("2"), drive.trashed);
        assertTrue(pitch.shifts.isEmpty());
    }

    @Test
    void removalMenuAlsoAcceptsNumberedSelectionAndRejectsChangedFile() {
        flow.interpreter = new FakeInterpreter("remove", 0, 0);
        flow.handleNatural("a", "quita una cancion");
        drive.changed.add("2");
        flow.handle("a", "cancion 2");
        assertTrue(drive.trashed.isEmpty());
        assertTrue(messages.last().contains("cambio"));
    }

    @Test
    void shortAmountFollowupReceivesEarlierDirection() {
        flow.interpreter = new FakeInterpreter("tone", 1, 0);
        flow.handleNatural("a", "sube esa un poquito");
        assertTrue(drive.events.isEmpty());
        var ai = new FakeInterpreter("tone", 1, 2);
        flow.interpreter = ai;
        flow.handle("a", "dos");
        assertEquals(List.of("sube esa un poquito"), ai.previousMessages);
        assertEquals(List.of(2), pitch.shifts);
    }

    @Test
    void naturalLyricsAndPlaylistQueriesUseExistingData() {
        flow.interpreter = new FakeInterpreter("lyrics", 0, 0);
        flow.handleNatural("a", "armame el documento de letras");
        assertEquals(1, pipeline.lyricsRequests);
        flow.interpreter = new FakeInterpreter("list", 0, 0);
        flow.handleNatural("a", "que canciones tenemos?");
        assertTrue(messages.last().contains("1. Uno.mp3"));
        assertTrue(messages.last().contains("3. Tres.m4a"));
        assertTrue(drive.events.isEmpty());
    }

    private static class FakePipeline extends SongPipeline {
        List<String> notesRequests = new ArrayList<>();
        int lyricsRequests;
        @Override void generarNotas(String from, String selectedSong) { notesRequests.add(selectedSong); }
        @Override void generarLetras(String from) { lyricsRequests++; }
    }

    private static class FakeInterpreter extends GeminiSongInterpreter {
        final Interpretation result;
        int selected;
        List<String> names;
        boolean fail;
        Runnable duringInterpret;
        List<String> previousMessages;
        String pendingAction;
        FakeInterpreter(String intent, int song, int semitones) {
            result = new Interpretation(intent, song, semitones);
        }
        @Override public boolean available() { return true; }
        @Override public Interpretation interpret(String message, List<String> songs, int selected,
                                                   List<String> previousMessages, String pendingAction) throws Exception {
            this.previousMessages = previousMessages;
            this.pendingAction = pendingAction;
            return interpret(message, songs, selected);
        }
        @Override public Interpretation interpret(String message, List<String> songs, int selected) throws Exception {
            this.names = songs;
            this.selected = selected;
            if (duringInterpret != null) duringInterpret.run();
            if (fail) throw new IOException("Simulated quota limit");
            return result;
        }
    }

    private static class FakeDrive extends DriveService {
        List<AudioFile> songs = List.of(new AudioFile("1", "Uno.mp3", 1L),
                new AudioFile("2", "Dos.mp3", 1L), new AudioFile("3", "Tres.m4a", 1L));
        List<String> events = new ArrayList<>();
        Set<String> trashed = new HashSet<>();
        Set<String> changed = new HashSet<>();
        String failUpload;
        boolean failTrash;
        List<AudioFile> uploaded = new ArrayList<>();

        @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) {
            return new EstructuraDomingo("playlist", "notes", "sunday", "folder-link");
        }
        @Override public List<AudioFile> listAudioFiles(String parentId) {
            return java.util.stream.Stream.concat(songs.stream(), uploaded.stream())
                    .filter(song -> !trashed.contains(song.id())).toList();
        }
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
            uploaded.add(new AudioFile("new-" + name, name, 1L));
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
