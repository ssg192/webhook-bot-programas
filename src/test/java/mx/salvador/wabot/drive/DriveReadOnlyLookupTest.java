package mx.salvador.wabot.drive;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriveReadOnlyLookupTest {
    @Test void missingHierarchyStopsWithoutCreatingAnything() throws Exception {
        var service = new DriveService() {
            @Override String findFolder(String name, String parent) { return null; }
            @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) { throw new AssertionError("write"); }
        };
        assertNull(service.findSundayStructure(LocalDate.of(2026, 9, 20)));
    }

    @Test void partialDateFolderRemainsPartialAndListsAreSafe() throws Exception {
        var service = new DriveService() {
            @Override String findFolder(String name, String parent) {
                return name.equals("Playlist") || name.equals("Notas") ? null : name;
            }
            @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) { throw new AssertionError("write"); }
        };
        var found = service.findSundayStructure(LocalDate.of(2026, 9, 20));
        assertNotNull(found);
        assertNull(found.playlistId());
        assertNull(found.notasId());
        assertTrue(service.listAudioFiles(null).isEmpty());
        assertTrue(service.listFolderFiles(null).isEmpty());
    }
}
