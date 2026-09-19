package mx.salvador.wabot.whatsapp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhatsAppConversationTest {
    @Test
    void confirmationUsesInteractiveReplyIdsAndOmitsNullText() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = json.readTree(json.writeValueAsString(GraphApiClient.OutgoingMessage.buttons("a", "¿Borro solo las notas?", "nonce")));
        assertFalse(data.has("text"));
        assertEquals("interactive", data.path("type").asText());
        var buttons = data.path("interactive").path("action").path("buttons");
        assertEquals(2, buttons.size());
        assertEquals("confirm:nonce", buttons.path(0).path("reply").path("id").asText());
        assertEquals("cancel:nonce", buttons.path(1).path("reply").path("id").asText());
        var message = json.readValue("{\"id\":\"m\",\"from\":\"a\",\"type\":\"interactive\",\"interactive\":{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"confirm:nonce\",\"title\":\"Confirmar\"}}}",
                mx.salvador.wabot.webhook.WebhookPayload.Message.class);
        assertEquals("confirm:nonce", message.body());
    }
    @Test
    void remembersBothSidesInOrderAndSeparatesSenders() {
        var service = new WhatsAppService();
        service.graphApi = (phone, token, message) -> {};
        service.rememberIncoming("a", "crea letras");
        service.replyText("a", "Doc de letras creado con Marcos y En Ti");
        service.rememberIncoming("a", "agrega la de Ingrid al doc");
        var history = service.conversation("a");
        assertEquals(3, history.size());
        assertEquals("assistant", history.get(1).get("role"));
        assertTrue(history.get(1).get("text").contains("Doc de letras"));
        assertTrue(service.conversation("b").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> history.clear());
    }

    @Test
    void boundsHistoryAndDoesNotRememberFailedOutgoingMessages() {
        var service = new WhatsAppService();
        service.graphApi = (phone, token, message) -> { throw new IllegalStateException("test failure"); };
        for (int i = 0; i < 20; i++) service.rememberIncoming("a", "mensaje " + i);
        service.replyText("a", "no enviado");
        assertEquals(16, service.conversation("a").size());
        assertEquals("mensaje 19", service.conversation("a").get(15).get("text"));
    }
}
