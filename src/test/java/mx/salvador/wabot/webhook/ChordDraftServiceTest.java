package mx.salvador.wabot.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import mx.salvador.wabot.media.ChordTransposer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChordDraftServiceTest {
    private final ChordDraftService service = new ChordDraftService();
    private final List<ChordDraftService.Source> sources = List.of(
            new ChordDraftService.Source("Tema - Artista (original)", "https://fuente-a.example/song", "Tema Artista. Key: C. Coro C G Am F C/E G"),
            new ChordDraftService.Source("Tema Artista", "https://fuente-b.example/song", "Tema Artista, grabacion original."));
    private final String draft = """
            {"reference":"Tema - Artista, original","key":"C","keySource":1,"corroboration":2,
             "sections":[{"name":"Coro","chords":["C","G","Am","F"],"source":1}]}
            """;
    ChordDraftServiceTest() { service.json = new ObjectMapper(); }

    @Test void transposesChordsAndSlashBassWithoutChangingQuality() {
        assertEquals("D/F#", ChordTransposer.transpose("C/E", 2, false));
        assertEquals("Bm7", ChordTransposer.transpose("Am7", 2, false));
        assertEquals("Ebmaj7/Bb", ChordTransposer.transpose("Dmaj7/A", 1, true));
        assertEquals(2, ChordTransposer.distance("C", "D"));
        assertThrows(IllegalArgumentException.class, () -> ChordTransposer.distance("C", "Dm"));
        assertThrows(IllegalArgumentException.class, () -> ChordTransposer.distance("", "D"));
    }

    @Test void docxContainsUsefulBaseSourcesAndCoverCaveat() throws Exception {
        var document = service.render("Tema cover (+2)", "https://youtu.be/example", service.parse(draft, sources), "D");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(document.bytes()))) {
            String text = doc.getParagraphs().stream().map(p -> p.getText()).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("D | A | Bm | G"));
            assertTrue(text.contains("cover NO estan verificadas"));
            assertTrue(text.contains("https://fuente-a.example/song"));
            assertTrue(text.contains("https://fuente-b.example/song"));
            assertTrue(text.contains("Version solicitada"));
            assertTrue(text.contains("Pendiente de comprobar"));
        }
    }

    @Test void refusesEmptyHallucinatedOrUnsupportedDrafts() {
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"C\",\"G\",\"Am\",\"F\"", "\"C\",\"Bdim\""), sources));
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"name\":\"Coro\"", "\"name\":\"Letra completa\""), sources));
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"key\":\"C\"", "\"key\":\"D\""), sources));
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"corroboration\":2", "\"corroboration\":1"), sources));
        assertThrows(IOException.class, () -> service.parse("{}", sources));
    }

    @Test void missingReferenceKeyPreservesChordsButCannotTranspose() throws Exception {
        var unknown = service.parse(draft.replace("\"key\":\"C\",\"keySource\":1", "\"key\":\"\",\"keySource\":0"), sources);
        assertFalse(service.render("Tema", "", unknown, "").transposed());
        assertThrows(IllegalArgumentException.class, () -> service.render("Tema", "", unknown, "D"));
    }

    @Test void oneUsefulPageIsEnoughWithoutInventingCorroboration() throws Exception {
        assertEquals(1, service.parse(draft.replace("\"corroboration\":2", "\"corroboration\":0"), sources.subList(0, 1)).sections().size());
    }

    @Test void searchIsBoundedHasNoAnswersAndFiltersUnsafeLinks() throws Exception {
        var stub = new ChordDraftService() {
            @Override String searchExchange(String body) throws Exception {
                var request = json.readTree(body);
                assertEquals("basic", request.path("search_depth").asText());
                assertFalse(request.path("auto_parameters").asBoolean());
                assertFalse(request.path("include_answer").asBoolean());
                return "{\"results\":[{\"url\":\"javascript:alert(1)\",\"content\":\"C G\"},{\"title\":\"Tema\",\"url\":\"https://fuente.example/song\",\"content\":\"C G Am F\"}]}";
            }
        };
        stub.json = service.json;
        assertEquals(1, stub.search("Tema").size());
    }

    @Test void monthlyCapStopsBeforeCallingProvider() throws Exception {
        service.monthlyLimit = 1;
        service.reserveSearch();
        var failure = assertThrows(IOException.class, () -> service.reserveSearch());
        assertTrue(failure.getMessage().contains("proximo mes"));
    }
}
