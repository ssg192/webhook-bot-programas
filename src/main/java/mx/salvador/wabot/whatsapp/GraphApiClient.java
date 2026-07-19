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

    record OutgoingMessage(String messaging_product, String to, String type, Text text) {
        public static OutgoingMessage text(String to, String body) {
            return new OutgoingMessage("whatsapp", to, "text", new Text(body));
        }
    }

    record Text(String body) {}
}
