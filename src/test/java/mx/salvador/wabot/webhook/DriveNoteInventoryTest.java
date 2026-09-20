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
}
