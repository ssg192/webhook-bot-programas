package mx.salvador.wabot.webhook;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@Path("/webhook")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    @Inject
    SongPipeline pipeline;

    @ConfigProperty(name = "wa.verify-token")
    String verifyToken;

    /**
     * Verificación del webhook: Meta manda un GET con hub.challenge
     * al registrar la URL. Hay que regresar el challenge tal cual.
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response verify(
            @QueryParam("hub.mode") String mode,
            @QueryParam("hub.verify_token") String token,
            @QueryParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            LOG.info("Webhook verificado por Meta");
            return Response.ok(challenge).build();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    /**
     * Recepción de mensajes. Responder 200 rápido — el trabajo pesado
     * se va a un worker; si tardas, Meta reintenta y duplica webhooks.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receive(WebhookPayload payload) {
        if (payload != null && payload.entry() != null) {
            payload.entry().stream()
                    .filter(e -> e.changes() != null)
                    .flatMap(e -> e.changes().stream())
                    .filter(c -> c.value() != null && c.value().messages() != null)
                    .flatMap(c -> c.value().messages().stream())
                    .forEach(pipeline::handleIncoming);
        }
        return Response.ok().build();
    }
}
