package mx.salvador.wabot.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Payload del webhook de WhatsApp Cloud API (solo los campos que usamos).
 * Estructura: entry[] -> changes[] -> value -> messages[]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(List<Entry> entry) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(List<Change> changes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(Value value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(List<Message> messages, List<Contact> contacts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(Profile profile, String wa_id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String from, String type, Text text, Interactive interactive) {
        public Message(String id, String from, String type, Text text) { this(id, from, type, text, null); }
        public String body() {
            if ("text".equals(type) && text != null) return text.body();
            if ("interactive".equals(type) && interactive != null && interactive.button_reply() != null)
                return interactive.button_reply().id();
            return null;
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Interactive(ButtonReply button_reply) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ButtonReply(String id, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String body) {}
}
