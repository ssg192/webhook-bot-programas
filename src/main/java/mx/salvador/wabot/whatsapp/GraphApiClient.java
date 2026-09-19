package mx.salvador.wabot.whatsapp;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Cliente hacia la Graph API de Meta para mandar mensajes. */
@RegisterRestClient(configKey = "graph-api")
@Produces(MediaType.APPLICATION_JSON)
public interface GraphApiClient {

    @POST
    @Path("/{phoneNumberId}/messages")
    void sendMessage(
            @PathParam("phoneNumberId") String phoneNumberId,
            @HeaderParam("Authorization") String bearerToken,
            OutgoingMessage message);

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record OutgoingMessage(String messaging_product, String to, String type, Text text, Object interactive) {
        public static OutgoingMessage text(String to, String body) {
            return new OutgoingMessage("whatsapp", to, "text", new Text(body), null);
        }
        public static OutgoingMessage buttons(String to, String body, String token) {
            return new OutgoingMessage("whatsapp", to, "interactive", null, java.util.Map.of(
                    "type", "button", "body", java.util.Map.of("text", body), "action", java.util.Map.of("buttons", java.util.List.of(
                            java.util.Map.of("type", "reply", "reply", java.util.Map.of("id", "confirm:" + token, "title", "Confirmar")),
                            java.util.Map.of("type", "reply", "reply", java.util.Map.of("id", "cancel:" + token, "title", "Cancelar"))))));
        }
    }

    record Text(String body) {}
}
