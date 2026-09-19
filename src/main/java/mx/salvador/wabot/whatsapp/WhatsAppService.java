package mx.salvador.wabot.whatsapp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WhatsAppService {
    @Inject mx.salvador.wabot.webhook.ContextStore contextStore;
    @jakarta.annotation.PostConstruct
    void loadConversations() {
        var saved = contextStore.read("conversations", new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Conversation>>() {});
        if (saved != null) saved.forEach((key, value) -> { if (value.expires().isAfter(java.time.Instant.now())) conversations.put(key, value); });
    }
    private record Conversation(java.util.List<java.util.Map<String, String>> messages, java.time.Instant expires) {}
    private final java.util.Map<String, Conversation> conversations = new java.util.concurrent.ConcurrentHashMap<>();

    public void rememberIncoming(String from, String body) { remember(from, "user", body); }

    private void remember(String from, String role, String body) {
        if (body == null || body.isBlank()) return;
        conversations.entrySet().removeIf(e -> e.getValue().expires().isBefore(java.time.Instant.now()));
        if (conversations.size() >= 1000 && !conversations.containsKey(from)) return;
        conversations.compute(from, (key, previous) -> {
            var messages = new java.util.ArrayList<java.util.Map<String, String>>();
            if (previous != null) messages.addAll(previous.messages());
            messages.add(java.util.Map.of("role", role, "text", body.substring(0, Math.min(2500, body.length()))));
            while (messages.size() > 16) messages.remove(0);
            return new Conversation(java.util.List.copyOf(messages), java.time.Instant.now().plusSeconds(1800));
        });
        if (contextStore != null) contextStore.save("conversations", conversations);
    }

    public java.util.List<java.util.Map<String, String>> conversation(String from) {
        var value = conversations.get(from);
        if (value == null || value.expires().isBefore(java.time.Instant.now())) return java.util.List.of();
        return value.messages();
    }

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
            remember(to, "assistant", body);
        } catch (Exception e) {
            LOG.errorf(e, "Error enviando mensaje a %s", to);
        }
    }

    public void confirmButtons(String to, String body, String token) {
        String message = body + "\nResponde confirmar o cancelar.";
        try {
            graphApi.sendMessage(phoneNumberId, "Bearer " + accessToken,
                    GraphApiClient.OutgoingMessage.buttons(to, message, token));
            remember(to, "assistant", message);
        } catch (Exception e) {
            replyText(to, message); // Sigue funcionando por texto si Meta rechaza los botones.
        }
    }
}
