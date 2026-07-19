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
    public record Message(String id, String from, String type, Text text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Text(String body) {}
}
