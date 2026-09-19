package mx.salvador.wabot.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.drive.DriveService.AudioFile;
import mx.salvador.wabot.media.PitchShifter;
import mx.salvador.wabot.util.Fechas;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Conversacion: direccion -> canciones -> semitonos. Se ejecuta en los workers. */
@ApplicationScoped
public class PlaylistToneFlow {
    private static final Logger LOG = Logger.getLogger(PlaylistToneFlow.class);
    private static final Pattern SONGS = Pattern.compile("^canci[oó]n(?:es)?(?:\\s+.*)?$");
    private static final Pattern AMOUNT = Pattern.compile("^([+-]?\\d+)(?:\\s+semitonos?)?$");
    private static final Pattern PREVIOUS_SHIFT = Pattern.compile("^(.*) \\(([+-]\\d+)\\)$");

    @Inject DriveService drive;
    @Inject PitchShifter shifter;
    @Inject WhatsAppService whatsApp;

    private record Pending(int direction, DriveService.EstructuraDomingo folder,
                           LocalDate sunday, List<AudioFile> songs, List<AudioFile> selected,
                           Instant expires) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public boolean accepts(String body) {
        if (body == null) return false;
        String text = normalize(body);
        return text.equals("bajar tono") || text.equals("subir tono")
                || text.equals("todas") || text.equals("cancelar")
                || SONGS.matcher(text).matches() || AMOUNT.matcher(text).matches();
    }

    public void handle(String from, String body) {
        String text = normalize(body);
        pending.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        try {
            if (text.equals("bajar tono") || text.equals("subir tono")) {
                start(from, text.equals("bajar tono") ? -1 : 1);
                return;
            }
            if (text.equals("cancelar")) {
                pending.remove(from);
                whatsApp.replyText(from, "Cambio de tono cancelado. Si un ajuste ya comenzo, terminara de procesarse.");
                return;
            }
            Pending choice = pending.get(from);
            if (choice == null || !choice.sunday().equals(Fechas.proximoDomingo())) {
                if (choice != null) pending.remove(from, choice);
                whatsApp.replyText(from, "Escribe \"bajar tono\" o \"subir tono\" para comenzar.");
                return;
            }
            if (choice.selected().isEmpty()) {
                List<AudioFile> selected = select(text, choice.songs());
                Pending next = new Pending(choice.direction(), choice.folder(), choice.sunday(),
                        choice.songs(), selected, choice.expires());
                if (pending.replace(from, choice, next)) askAmount(from, next);
                return;
            }
            var match = AMOUNT.matcher(text);
            if (!match.matches()) {
                askAmount(from, choice);
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(match.group(1));
            } catch (NumberFormatException e) {
                amount = 0;
            }
            if (amount < 1 || amount > 12) {
                whatsApp.replyText(from, "Escribe un numero del 1 al 12, por ejemplo 2. La direccion ya esta elegida.");
                return;
            }
            // Consume la seleccion una sola vez, aun si llegan respuestas simultaneas.
            if (!pending.remove(from, choice)) return;
            whatsApp.replyText(from, "Ajustando " + choice.selected().size() + " cancion(es), te aviso...");
            int semitones = amount * choice.direction();
            var result = new StringBuilder();
            for (AudioFile song : choice.selected()) {
                result.append("• ").append(replaceAudio(song, choice.folder().playlistId(), semitones)).append('\n');
            }
            result.append('\n').append(choice.folder().link());
            whatsApp.replyText(from, result.toString());
        } catch (IllegalArgumentException e) {
            whatsApp.replyText(from, "Elige numeros de la lista: cancion 1, cancion 1 3 o todas.");
        } catch (Exception e) {
            LOG.error("Error en seleccion de tono", e);
            whatsApp.replyText(from, "No pude preparar el cambio de tono. Vuelve a escribir bajar tono o subir tono.");
        }
    }

    private void start(String from, int direction) throws Exception {
        pending.remove(from);
        LocalDate sunday = Fechas.proximoDomingo();
        var folder = drive.ensureSundayStructure(sunday);
        List<AudioFile> songs = drive.listAudioFiles(folder.playlistId());
        if (songs.isEmpty()) {
            whatsApp.replyText(from, "Aun no hay canciones en la playlist. Envia primero sus links de YouTube.");
            return;
        }
        Pending choice = new Pending(direction, folder, sunday, songs,
                songs.size() == 1 ? songs : List.of(), Instant.now().plusSeconds(1800));
        pending.put(from, choice);
        if (songs.size() == 1) {
            askAmount(from, choice);
            return;
        }
        var menu = new StringBuilder("¿A cuales canciones quieres ")
                .append(direction < 0 ? "bajar" : "subir").append(" el tono?\n");
        for (int i = 0; i < songs.size(); i++) {
            menu.append(i + 1).append(". ").append(songs.get(i).name()).append('\n');
        }
        menu.append("\nResponde cancion 1, cancion 1 3 o todas. Para salir, escribe cancelar.");
        whatsApp.replyText(from, menu.toString());
    }

    private void askAmount(String from, Pending choice) {
        var message = new StringBuilder("Seleccionadas:\n");
        choice.selected().forEach(song -> message.append("• ").append(song.name()).append('\n'));
        message.append("\n¿Cuantos semitonos quieres ")
                .append(choice.direction() < 0 ? "bajar" : "subir")
                .append("? Escribe un numero del 1 al 12, por ejemplo 2.\n")
                .append("Se aplica sobre el tono actual de los audios. Para salir, escribe cancelar.");
        whatsApp.replyText(from, message.toString());
    }

    static List<AudioFile> select(String text, List<AudioFile> songs) {
        if (text.equals("todas")) return songs;
        if (!SONGS.matcher(text).matches()) throw new IllegalArgumentException();
        String[] parts = text.split("\\s+");
        if (parts.length < 2) throw new IllegalArgumentException();
        var indices = new LinkedHashSet<Integer>();
        for (int i = 1; i < parts.length; i++) {
            int index = Integer.parseInt(parts[i]);
            if (index < 1 || index > songs.size()) throw new IllegalArgumentException();
            indices.add(index - 1);
        }
        return indices.stream().map(songs::get).toList();
    }

    /** Serializa reemplazos locales; otro menu que apunte al archivo viejo se rechaza. */
    private synchronized String replaceAudio(AudioFile song, String folderId, int semitones) {
        Path temp = null;
        String uploadedName = null;
        try {
            if (!drive.audioUnchanged(song, folderId)) {
                return song.name() + ": cambio desde que mostre la lista. Vuelve a solicitar el cambio de tono.";
            }
            temp = Files.createTempDirectory("wa-tone-");
            String ext = song.name().substring(song.name().lastIndexOf('.'));
            Path input = temp.resolve("audio" + ext);
            drive.downloadAudio(song.id(), input);
            Path shifted = shifter.shift(input, semitones);
            if (!Files.isRegularFile(shifted) || Files.size(shifted) == 0) {
                throw new IllegalStateException("Audio procesado vacio");
            }
            if (!drive.audioUnchanged(song, folderId)) {
                return song.name() + ": cambio durante el procesamiento. Vuelve a solicitar el cambio de tono.";
            }
            String name = adjustedName(song.name(), semitones);
            drive.uploadNewAudio(shifted, name, folderId);
            uploadedName = name;
            drive.trashFile(song.id());
            return name + ": lista; version anterior en la papelera.";
        } catch (Exception e) {
            LOG.errorf(e, "No se pudo reemplazar el audio %s", song.id());
            if (uploadedName != null) {
                return uploadedName + ": subida, pero no pude mandar la anterior a la papelera. Revisa ambas en Drive.";
            }
            return song.name() + ": no pude ajustar el tono; no mande la anterior a la papelera.";
        } finally {
            if (temp != null) {
                try (var paths = Files.walk(temp)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (Exception e) {
                    LOG.warn("No se pudieron limpiar los temporales de tono", e);
                }
            }
        }
    }

    static String adjustedName(String name, int semitones) {
        int dot = name.lastIndexOf('.');
        String base = name.substring(0, dot);
        var previous = PREVIOUS_SHIFT.matcher(base);
        if (previous.matches()) {
            try {
                semitones = Math.addExact(semitones, Integer.parseInt(previous.group(2)));
                base = previous.group(1);
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Un sufijo numerico ajeno al bot se conserva en el nombre.
            }
        }
        return base + (semitones == 0 ? "" : " (%+d)".formatted(semitones)) + name.substring(dot);
    }

    private static String normalize(String body) {
        return body.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
