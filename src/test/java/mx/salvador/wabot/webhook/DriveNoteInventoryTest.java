package mx.salvador.wabot.webhook;

import com.google.api.services.drive.model.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriveNoteInventoryTest {
    static File pdf(String id, String name) { return new File().setId(id).setName(name).setMimeType("application/pdf"); }

    @Test void discoversManualFilesWithoutRegisteringThemAsDeletionTargets() {
        var state = new MusicWorkState();
        var result = DriveNoteInventory.match(List.of("En_Ti.m4a"), List.of(pdf("manual", "En Ti-Marco Barrientos-E.pdf")), state, "today");
        assertEquals(List.of("En Ti-Marco Barrientos-E.pdf"), result.matches().get("En_Ti.m4a"));
        assertTrue(state.copies("En Ti").isEmpty());
    }

    @Test void ambiguousTitlesAreNotAssignedAndOtherFolderIdsAreNotTrusted() {
        var state = new MusicWorkState();
        state.copied("Otra", "manual", "Tema.pdf", "old-folder");
        var result = DriveNoteInventory.match(List.of("Tema.m4a", "Tema especial.m4a", "Otra.m4a"),
                List.of(pdf("manual", "Tema especial.pdf")), state, "today");
        assertEquals(2, result.ambiguous().size());
        assertTrue(result.matches().get("Otra.m4a").isEmpty());
        assertEquals(List.of("Tema especial.pdf"), result.unassigned());
    }

    @Test void matchesTitleWithoutArtistOnEitherSideAndPreservesVersionAmbiguity() {
        var songs = List.of("El Dios que Adoramos - Gracia Soberana.m4a", "Artista - Otra cancion.m4a");
        var files = List.of(pdf("one", "El Dios que Adoramos.pdf"), pdf("two", "Otra canción.pdf"));
        var result = DriveNoteInventory.match(songs, files, new MusicWorkState(), "today");
        assertEquals(List.of("El Dios que Adoramos.pdf"), result.matches().get(songs.get(0)));
        assertEquals(List.of("Otra canción.pdf"), result.matches().get(songs.get(1)));
        assertTrue(result.unassigned().isEmpty());

        var versions = List.of("Tema - Artista uno.m4a", "Tema - Artista dos.m4a");
        var ambiguous = DriveNoteInventory.match(versions, List.of(pdf("three", "Tema.pdf")), new MusicWorkState(), "today");
        assertEquals(java.util.Set.copyOf(versions), ambiguous.ambiguous());
        assertTrue(ambiguous.matches().values().stream().allMatch(List::isEmpty));
    }
}
