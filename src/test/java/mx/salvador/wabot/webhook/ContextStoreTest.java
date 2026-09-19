package mx.salvador.wabot.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContextStoreTest {
    @TempDir Path dir;
    ContextStore store() {
        var store = new ContextStore();
        store.json = new ObjectMapper().findAndRegisterModules();
        store.filename = dir.resolve("context.json").toString();
        return store;
    }

    @Test
    void notesAndCopiesSurviveRestartButRunningJobsDoNotPretendToContinue() {
        var state = new MusicWorkState();
        state.attach(store());
        state.note("Ingrid.m4a", "Copiadas: Ingrid.pdf");
        state.copied("Ingrid.m4a", "copied-id", "Ingrid.pdf", "notes-folder");
        state.document("En preparacion: buscando letras");
        var restored = new MusicWorkState();
        restored.attach(store());
        assertEquals("copied-id", restored.copies("Ingrid (+2).m4a").get(0).id());
        assertTrue(MusicWorkState.describe(restored.snapshot(List.of("Ingrid.m4a"), 0), "status", 0).contains("interrumpida"));
        assertTrue(MusicWorkState.describe(restored.snapshot(List.of("Ingrid.m4a"), 0), "status_notes", 1).contains("Copiadas"));
    }

    @Test
    void updatingOneSectionPreservesOtherSections() {
        var first = store();
        first.save("one", List.of("a"));
        first.save("two", List.of("b"));
        var second = store();
        assertEquals(List.of("a"), second.read("one", new TypeReference<List<String>>() {}));
        assertEquals(List.of("b"), second.read("two", new TypeReference<List<String>>() {}));
    }
}
