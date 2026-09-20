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
    @Test void extractsMissingPagesWithoutTrustingSnippetsOrUnexpectedUrls() throws Exception {
        var stub = new ChordDraftService() {
            @Override String searchExchange(String body) { return "{\"results\":[{\"url\":\"https://page.example/song\",\"title\":\"Tema\",\"content\":\"snippet\"}]}"; }
            @Override String extractExchange(String body) throws Exception {
                var request = json.readTree(body);
                assertEquals("basic", request.path("extract_depth").asText());
                assertEquals(1, request.path("urls").size());
                return "{\"results\":[{\"url\":\"https://page.example/song\",\"raw_content\":\"full chart\"},{\"url\":\"https://other.example/song\",\"raw_content\":\"unrequested\"}]}";
            }
        };
        stub.json = service.json;
        stub.monthlyLimit = 2;
        var result = stub.search("Tema");
        assertEquals(1, result.size());
        assertEquals("full chart", result.get(0).content());
        assertThrows(IOException.class, stub::reserveSearch);
    }

    @Test void retries503OnceWithSamePayloadButNeverRetriesQuotaErrors() throws Exception {
        for (int status : List.of(503, 429, 403)) {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var stub = new ChordDraftService() { @Override void retryPause() {} };
            stub.gemini = new GeminiSongInterpreter() {
                @Override String exchange(String body) throws Exception {
                    assertEquals("payload", body);
                    if (count.incrementAndGet() == 1) throw httpFailure(status);
                    return "ok";
                }
            };
            if (status == 503) { assertEquals("ok", stub.generateWithRetry("payload")); assertEquals(2, count.get()); }
            else { assertThrows(GeminiSongInterpreter.Failure.class, () -> stub.generateWithRetry("payload")); assertEquals(1, count.get()); }
        }
    }

    @Test void persistent503StopsAfterSecondAttempt() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var stub = new ChordDraftService() { @Override void retryPause() {} };
        stub.gemini = new GeminiSongInterpreter() {
            @Override String exchange(String body) throws Exception { count.incrementAndGet(); throw httpFailure(503); }
        };
        assertThrows(GeminiSongInterpreter.Failure.class, () -> stub.generateWithRetry("payload"));
        assertEquals(2, count.get());
    }
    @Test void diagnosticLogsDoNotExposeUrlCredentialsOrAllowNewLines() {
        assertEquals("https://example.com/chart", ChordDraftService.logUrl("https://example.com/chart?token=secret#private"));
        assertEquals("[invalid-or-unsafe-url]", ChordDraftService.logUrl("https://user:secret@example.com/chart"));
        assertFalse(ChordDraftService.logText("title\nforged\rentry").contains("\n"));
        assertEquals(500, ChordDraftService.logText("x".repeat(600)).length());
    }
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
            assertTrue(text.contains("D    A    Bm    G"));
            assertTrue(text.contains("no verificado contra las fuentes ni el audio"));
            assertTrue(text.contains("https://fuente-a.example/song"));
            assertFalse(text.contains("https://fuente-b.example/song"));
            assertEquals("TEMA COVER (+2)", doc.getParagraphs().get(0).getText());
            assertTrue(doc.getParagraphs().get(0).getRuns().get(0).isBold());
            assertEquals("Tahoma", doc.getParagraphs().get(0).getRuns().get(0).getFontFamily());
            assertTrue(text.contains("CORO"));
        }
    }

    @Test void refusesEmptyOrMalformedDrafts() {
        assertThrows(IOException.class, () -> service.parse(draft.replace("\"Am\"", "{}"), sources));
        assertThrows(IOException.class, () -> service.parse("{}", sources));
    }

    @Test void unconfirmedKeyStillAllowsOriginalCopy() throws Exception {
        var parsed = service.parse(draft.replace("\"key\":\"C\"", "\"key\":\"D\""), sources);
        assertEquals("D", parsed.key());
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
                return "{\"results\":[{\"url\":\"javascript:alert(1)\",\"raw_content\":\"C G\"},{\"title\":\"Tema\",\"url\":\"https://fuente.example/song\",\"raw_content\":\"C G Am F\"}]}";
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

    @Test void acceptsExtraFieldsAndMarkdownWithoutLosingFormatChecks() throws Exception {
        String extended = draft.replace("\"reference\":", "\"explanation\":\"extra\",\"reference\":")
                .replace("\"name\":", "\"comment\":\"extra\",\"name\":");
        assertEquals(service.parse(draft, sources), service.parse("```json\n" + extended + "```", sources));
        assertThrows(IOException.class, () -> service.parse(extended.replace("\"Am\"", "{}"), sources));
    }

    @Test void createsReviewableDocEvenWhenAiChordsDoNotMatchSourceText() throws Exception {
        var parsed = service.parse(draft.replace("\"C\",\"G\",\"Am\",\"F\"", "\"F\",\"Bdim\",\"C\""), sources);
        var document = service.render("Tema", "", parsed, "");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(document.bytes()))) {
            String text = doc.getParagraphs().stream().map(p -> p.getText()).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("F    Bdim    C"));
            assertTrue(text.contains("no verificado contra las fuentes ni el audio"));
            assertTrue(text.contains("Borrador de IA"));
        }
    }

    @Test void optionalMetadataCanBeAbsentWithoutInventingKeyOrSources() throws Exception {
        String minimal = draft.replace("\"key\":\"C\",\"keySource\":1,\"corroboration\":2,", "");
        assertEquals("", service.parse(minimal, sources).key());
        assertEquals(1, service.parse(minimal, sources).sections().size());
        assertEquals("C", service.parse(draft.replace("\"keySource\":1,", ""), sources).key());
        assertEquals("", service.parse(minimal.replace("\"sections\":", "\"key\":null,\"keySource\":null,\"corroboration\":null,\"sections\":"), sources).key());
        assertEquals(0, service.parse(minimal.replace(",\"source\":1", ""), sources).sections().get(0).source());
    }

    @Test void malformedResponsesHaveActionableErrorsAndRejectTrailingData() {
        var malformed = assertThrows(IOException.class, () -> service.parse("{broken", sources));
        assertTrue(malformed.getMessage().contains("respuesta ilegible"));
        assertThrows(IOException.class, () -> service.parse(draft + " {}", sources));
        assertThrows(IOException.class, () -> service.parse("[]", sources));
    }

    @Test void differentSourcesAndUnusualNotationDoNotBlockDocument() throws Exception {
        String mixed = draft.replace("\"corroboration\":2", "\"corroboration\":\"optional\"")
                .replace("\"C\",\"G\",\"Am\",\"F\"", "\"C(add9)/G (x2)\"")
                .replace("\"source\":1}]", "\"source\":1},{\"name\":\"Interludio libre\",\"chords\":[\"Re sus\"],\"source\":2}]");
        var parsed = service.parse(mixed, sources);
        assertEquals(2, parsed.sections().size());
        assertNotNull(service.render("Tema", "", parsed, "").bytes());
    }

    @Test void searchAndAiReceiveChosenKeyAndDocCopiesResultWithoutTransposingAgain() throws Exception {
        var stub = new ChordDraftService() {
            @Override public boolean available() { return true; }
            @Override String searchExchange(String body) throws Exception {
                assertTrue(json.readTree(body).path("query").asText().contains("tonalidad D"));
                assertTrue(json.readTree(body).path("query").asText().contains("acordes guitarra"));
                return json.writeValueAsString(java.util.Map.of("results", List.of(java.util.Map.of(
                        "title", "Tema", "url", "https://fuente.example/song", "raw_content", "C G"))));
            }
        };
        stub.json = service.json;
        stub.gemini = new GeminiSongInterpreter() {
            @Override String exchange(String body) throws Exception {
                var input = service.json.readTree(service.json.readTree(body).path("contents").get(0).path("parts").get(0).path("text").asText());
                assertEquals("D", input.path("tonoSolicitado").asText());
                String result = "{\"identityMatch\":true,\"complete\":true,\"source\":1,\"reference\":\"Tema\",\"key\":\"D\",\"sections\":[{\"name\":\"Base\",\"chords\":[\"D\",\"A\"],\"source\":1}]}";
                return service.json.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of(
                        "finishReason", "STOP", "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", result)))))));
            }
        };
        var document = stub.create("Tema", "", "D");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(document.bytes()))) {
            String text = doc.getParagraphs().stream().map(p -> p.getText()).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("D    A"));
            assertFalse(text.contains("E    B"));
        }
    }

    @Test void chartPreservesLyricChordPairsAndTemplateStyling() throws Exception {
        // Original fixture text, not lyrics fetched from a website.
        String response = """
                {"reference":"Tema de prueba","key":"D","sections":[
                  {"name":"Intro","source":1,"lines":[{"chords":"D (2X)    A","lyrics":""}]},
                  {"name":"Verso","source":1,"lines":[
                    {"chords":"  D        A","lyrics":"  Texto de prueba"},
                    {"chords":"","lyrics":"Segunda linea de ejemplo"}]},
                  {"name":"Coro (2X)","source":1,"lines":[{"chords":"G     D","lyrics":"Otro ejemplo"}]}]}
                """;
        var parsed = service.parse(response, sources);
        assertEquals("  D        A", parsed.sections().get(1).lines().get(0).chords());
        var result = service.render("Tema de prueba", "", parsed, "");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
            var paragraphs = doc.getParagraphs();
            var texts = paragraphs.stream().map(p -> p.getText()).toList();
            assertEquals("TEMA DE PRUEBA", texts.get(0));
            assertEquals("Tahoma", paragraphs.get(0).getRuns().get(0).getFontFamily());
            assertTrue(paragraphs.get(0).getRuns().get(0).isBold());
            assertTrue(texts.contains("ESTROFA"));
            assertTrue(texts.contains("CORO (2X)"));
            int chord = texts.indexOf("  D        A");
            assertEquals("  Texto de prueba", texts.get(chord + 1));
            assertEquals("Segunda linea de ejemplo", texts.get(chord + 2));
            assertEquals("Courier New", paragraphs.get(chord).getRuns().get(0).getFontFamily());
            assertEquals("Courier New", paragraphs.get(chord + 1).getRuns().get(0).getFontFamily());
            assertTrue(paragraphs.get(chord).isKeepNext());
            assertEquals(1, texts.stream().filter(t -> t.startsWith("Fuente:")).count());
            assertFalse(texts.stream().anyMatch(t -> t.contains("solo contiene acordes")));
        }
    }

    @Test void instrumentalOnlyDraftExplainsMissingLyricsAndMalformedLinesFailClearly() throws Exception {
        var result = service.render("Tema", "", service.parse(draft, sources), "");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(result.bytes()))) {
            assertTrue(doc.getParagraphs().stream().anyMatch(p -> p.getText().contains("solo contiene acordes")));
        }
        String bad = "{\"reference\":\"Tema\",\"sections\":[{\"lines\":[{\"chords\":{}}]}]}";
        assertThrows(IOException.class, () -> service.parse(bad, sources));
    }

    @Test void researchDoesNotRequireInternalCertificationFields() throws Exception {
        String checked = draft.replace("\"reference\":", "\"identityMatch\":true,\"complete\":true,\"source\":1,\"reference\":");
        assertNotNull(service.parseResearch(checked, sources));
        assertNotNull(service.parseResearch(checked.replace("\"complete\":true", "\"complete\":false"), sources));
        assertNotNull(service.parseResearch(draft, sources));
        assertNotNull(service.parseResearch(checked.replace("\"source\":1}]", "\"source\":2}]"), sources));
    }

    @Test void referencesAcceptNumbersStringsUrlsAndSectionOnlyMetadata() throws Exception {
        for (String value : List.of("1", "\"1\"", "\"[1]\"", "\"https://fuente-a.example/song\"", "{\"url\":\"https://fuente-a.example/song\"}")) {
            var parsed = service.parseResearch(draft.replace("\"source\":1", "\"source\":" + value), sources);
            assertEquals(1, parsed.sections().get(0).source());
            assertNotNull(service.render("Tema", "", parsed, "").bytes());
        }
        var unknown = service.parseResearch(draft.replace("\"source\":1", "\"source\":999"), sources);
        assertEquals(0, unknown.sections().get(0).source());
        assertNotNull(service.render("Tema", "", unknown, "").bytes());
        var inherited = service.parseResearch(draft.replace("\"reference\":", "\"source\":\"1\",\"reference\":").replace(",\"source\":1", ""), sources);
        assertEquals(1, inherited.sections().get(0).source());
    }

    @Test void searchDoesNotUseVideoSnippetsOrSilentlyCutOffPages() throws Exception {
        var stub = new ChordDraftService() {
            @Override String searchExchange(String body) throws Exception {
                return json.writeValueAsString(java.util.Map.of("results", List.of(
                        java.util.Map.of("url", "https://www.youtube.com/watch?v=abc", "raw_content", "video description"),
                        java.util.Map.of("url", "https://page.example/snippet", "content", "short summary"),
                        java.util.Map.of("url", "https://page.example/large", "raw_content", "x".repeat(40001)),
                        java.util.Map.of("url", "https://page.example/chart", "raw_content", "whole page"))));
            }
        };
        stub.json = service.json;
        var found = stub.search("Tema");
        assertEquals(1, found.size());
        assertEquals("whole page", found.get(0).content());
        assertTrue(ChordDraftService.videoUrl("https://m.youtube.com/watch?v=abc"));
        assertFalse(ChordDraftService.videoUrl("https://notyoutube.com/chart"));
    }
}
