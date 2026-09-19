package mx.salvador.wabot.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Almacen local de una instancia. Escrituras atomicas; nunca guarda credenciales. */
@ApplicationScoped
public class ContextStore {
    private static final Logger LOG = Logger.getLogger(ContextStore.class);
    @Inject ObjectMapper json;
    @ConfigProperty(name = "bot.context-path", defaultValue = "data/conversation-context.json") String filename;
    private final Map<String, JsonNode> sections = new LinkedHashMap<>();
    private boolean loaded;
    private boolean writable = true;

    private void load() {
        if (loaded) return;
        loaded = true;
        Path path = Path.of(filename);
        try {
            if (Files.exists(path)) sections.putAll(json.readValue(Files.readAllBytes(path), new TypeReference<Map<String, JsonNode>>() {}));
        } catch (Exception e) {
            writable = false; // No sobreescribir un archivo dañado que podria recuperarse.
            LOG.error("No se pudo leer el contexto persistente; se usara memoria sin sobrescribirlo");
        }
    }

    public synchronized <T> T read(String section, TypeReference<T> type) {
        load();
        var value = sections.get(section);
        if (value == null) return null;
        try { return json.convertValue(value, type); }
        catch (IllegalArgumentException e) { LOG.warn("Se ignoro una seccion de contexto incompatible"); return null; }
    }

    public synchronized void save(String section, Object value) {
        load();
        sections.put(section, json.valueToTree(value));
        if (!writable) return;
        Path temp = null;
        try {
            Path target = Path.of(filename).toAbsolutePath();
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".context-", ".json");
            try { Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
            Files.write(temp, json.writeValueAsBytes(sections));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { LOG.error("No se pudo persistir el contexto; revise el volumen y permisos"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) {} }
    }
}
