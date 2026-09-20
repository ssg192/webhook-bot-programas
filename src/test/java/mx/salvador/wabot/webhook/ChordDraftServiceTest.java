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
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"corroboration\":2", "\"corroboration\":1"), sources));
        assertThrows(IOException.class, () -> service.parse("{}", sources));
    }

    @Test void unconfirmedKeyStillAllowsOriginalCopy() throws Exception {
        var parsed = service.parse(draft.replace("\"key\":\"C\"", "\"key\":\"D\""), sources);
        assertEquals("", parsed.key());
        assertFalse(service.render("Tema", "", parsed, "").transposed());
    }

    @Test void preservesComplexNotationWithoutRequiringTransposition() throws Exception {
        var page = List.of(new ChordDraftService.Source("Tema", "https://fuente.example/song", "Key: C. C7sus4 F"));
        var parsed = service.parse(draft.replace("\"corroboration\":2", "\"corroboration\":0")
                .replace("\"C\",\"G\",\"Am\",\"F\"", "\"C7sus4\",\"F\""), page);
        assertNotNull(service.render("Tema", "", parsed, "").bytes());
        assertThrows(IllegalArgumentException.class, () -> service.render("Tema", "", parsed, "D"));
        assertEquals("D7(b9)/F#", ChordTransposer.transpose("C7(b9)/E", 2, false));
        assertEquals("D/F#", ChordTransposer.transpose("Do/Mi", 2, false));
        assertEquals("D#", ChordTransposer.transpose("C♯", 2, false));
        assertFalse(ChordTransposer.copyableChord("Como"));
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

    @Test void acceptsExtraFieldsAndMarkdownWithoutLosingEvidenceChecks() throws Exception {
        String extended = draft.replace("\"reference\":", "\"explanation\":\"extra\",\"reference\":")
                .replace("\"name\":", "\"comment\":\"extra\",\"name\":");
        assertEquals(service.parse(draft, sources), service.parse("```json\n" + extended + "```", sources));
        assertThrows(IOException.class, () -> service.parse(extended.replace("\"Am\"", "\"Bdim\""), sources));
    }

    @Test void optionalMetadataCanBeAbsentWithoutInventingKeyOrSources() throws Exception {
        String minimal = draft.replace("\"key\":\"C\",\"keySource\":1,\"corroboration\":2,", "");
        assertEquals("", service.parse(minimal, sources).key());
        assertEquals(1, service.parse(minimal, sources).sections().size());
        assertEquals("", service.parse(draft.replace("\"keySource\":1,", ""), sources).key());
        assertEquals("", service.parse(minimal.replace("\"sections\":", "\"key\":null,\"keySource\":null,\"corroboration\":null,\"sections\":"), sources).key());
        assertThrows(IOException.class, () -> service.parse(minimal.replace(",\"source\":1", ""), sources));
    }

    @Test void malformedResponsesHaveActionableErrorsAndRejectTrailingData() {
        var malformed = assertThrows(IOException.class, () -> service.parse("{broken", sources));
        assertTrue(malformed.getMessage().contains("respuesta ilegible"));
        assertThrows(IOException.class, () -> service.parse(draft + " {}", sources));
        assertThrows(IOException.class, () -> service.parse("[]", sources));
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"corroboration\":2", "\"corroboration\":\"2\""), sources));
    }
}
