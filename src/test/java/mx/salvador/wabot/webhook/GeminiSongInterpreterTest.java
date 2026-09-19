package mx.salvador.wabot.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiSongInterpreterTest {
    @Test
    void requiresExplicitEnableAndApiKey() {
        var ai = new Stub();
        ai.enabled = false;
        assertFalse(ai.available());
        assertThrows(IOException.class, () -> ai.interpret("bajala", List.of("Uno.mp3"), 1));
        assertNull(ai.request);
        ai.enabled = true;
        ai.apiKey = Optional.empty();
        assertFalse(ai.available());
    }

    @Test
    void buildsStructuredRequestWithOnlyNamesTextAndSelection() throws Exception {
        var ai = new Stub();
        var result = ai.interpret("baja dos la primera", List.of("Uno.mp3"), 0);
        assertEquals(new GeminiSongInterpreter.Interpretation("tone", 1, -2), result);
        var payload = ai.json.readTree(ai.request);
        var data = ai.json.readTree(payload.path("contents").path(0).path("parts").path(0).path("text").asText());
        assertEquals(3, data.size());
        assertEquals("Uno.mp3", data.path("canciones").path(0).asText());
        assertEquals(0, data.path("seleccion").asInt());
        assertFalse(ai.request.contains("test-secret"));
        assertEquals("application/json", payload.path("generationConfig").path("responseMimeType").asText());
        assertFalse(payload.has("tools"));
    }

    @Test
    void rejectsInventedSongsWrongTypesAndOutOfRangeChanges() {
        var ai = new Stub();
        for (String bad : List.of(
                "{\"intent\":\"tone\",\"song\":2,\"semitones\":-2}",
                "{\"intent\":\"tone\",\"song\":1,\"semitones\":-13}",
                "{\"intent\":\"tone\",\"song\":1,\"semitones\":1.5}",
                "{\"intent\":\"tone\",\"song\":\"1\",\"semitones\":2}",
                "{\"intent\":\"tone\",\"song\":99999999999999999,\"semitones\":2}",
                "{\"intent\":\"delete\",\"song\":1,\"semitones\":2}",
                "{\"intent\":\"clarify\",\"song\":1,\"semitones\":2}",
                "{\"intent\":\"tone\",\"song\":1}", "null", "[]", "not JSON")) {
            assertThrows(IOException.class, () -> ai.parse(bad, 1), bad);
        }
    }

    @Test
    void rejectsTruncatedOrBlockedResponsesAndPropagatesQuotaFailure() {
        var ai = new Stub();
        ai.finish = "MAX_TOKENS";
        assertThrows(IOException.class, () -> ai.interpret("bajala", List.of("Uno"), 1));
        ai.finish = "SAFETY";
        assertThrows(IOException.class, () -> ai.interpret("bajala", List.of("Uno"), 1));
        ai.failure = true;
        assertThrows(IOException.class, () -> ai.interpret("bajala", List.of("Uno"), 1));
    }

    @Test
    void oversizedRequestsNeverReachProvider() {
        var ai = new Stub();
        assertThrows(IOException.class, () -> ai.interpret("x".repeat(1501), List.of("Uno"), 1));
        assertNull(ai.request);
    }

    @Test
    void providerErrorsHaveSafeDistinctDiagnostics() {
        var quota = GeminiSongInterpreter.httpFailure(429);
        assertEquals("GEMINI_HTTP_429", quota.code());
        assertTrue(quota.userMessage().contains("cuota"));
        assertTrue(GeminiSongInterpreter.httpFailure(403).userMessage().contains("permisos"));
        assertTrue(GeminiSongInterpreter.httpFailure(400).userMessage().contains("configuracion"));
        assertTrue(GeminiSongInterpreter.httpFailure(404).userMessage().contains("modelo"));
    }

    @Test
    void truncationHasDifferentDiagnosticFromQuota() {
        var ai = new Stub();
        ai.finish = "MAX_TOKENS";
        var error = assertThrows(GeminiSongInterpreter.Failure.class,
                () -> ai.interpret("subela", List.of("Uno"), 1));
        assertEquals("GEMINI_MAX_TOKENS", error.code());
    }

    private static class Stub extends GeminiSongInterpreter {
        String request;
        String finish = "STOP";
        boolean failure;
        Stub() {
            json = new ObjectMapper();
            enabled = true;
            apiKey = Optional.of("test-secret");
        }
        @Override String exchange(String body) throws Exception {
            request = body;
            if (failure) throw new IOException("Gemini HTTP 429");
            return json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "finishReason", finish, "content", Map.of("parts", List.of(Map.of("text",
                            "{\"intent\":\"tone\",\"song\":1,\"semitones\":-2}")))))));
        }
    }
}
