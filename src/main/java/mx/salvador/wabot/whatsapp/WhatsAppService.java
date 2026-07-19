package mx.salvador.wabot.whatsapp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WhatsAppService {

    private static final Logger LOG = Logger.getLogger(WhatsAppService.class);

    @Inject
    @RestClient
    GraphApiClient graphApi;

    @ConfigProperty(name = "wa.phone-number-id")
    String phoneNumberId;

    @ConfigProperty(name = "wa.access-token")
    String accessToken;

    public void replyText(String to, String body) {
        try {
            graphApi.sendMessage(phoneNumberId, "Bearer " + accessToken,
                    GraphApiClient.OutgoingMessage.text(to, body));
        } catch (Exception e) {
            LOG.errorf(e, "Error enviando mensaje a %s", to);
        }
    }
}
