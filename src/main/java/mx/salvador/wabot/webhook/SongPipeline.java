package mx.salvador.wabot.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.media.LyricsDocxService;
import mx.salvador.wabot.media.LyricsHistoryService;
import mx.salvador.wabot.media.PitchShifter;
import mx.salvador.wabot.media.TitleCleaner;
import mx.salvador.wabot.media.YtDlpDownloader;
import mx.salvador.wabot.util.Fechas;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class SongPipeline {

    private static final Logger LOG = Logger.getLogger(SongPipeline.class);

    /** Canciones tipicas de un servicio; al llegar aqui se sugiere el doc de letras. */
    private static final int SUGERIR_LETRAS_EN = 4;

    @Inject YtDlpDownloader downloader;
    @Inject PitchShifter pitchShifter;
    @Inject DriveService driveService;
    @Inject LyricsDocxService lyricsDocx;
    @Inject LyricsHistoryService lyricsHistory;
    @Inject WhatsAppService whatsApp;

    /** Numeros permitidos (formato SIN el 1: 52 + 10 digitos). Vacio = todos. */
    @ConfigProperty(name = "bot.allowed-numbers")
    Optional<List<String>> allowedNumbers;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);

    /** Dedupe de message IDs: Meta reintenta webhooks si no respondes rapido. */
    private final Set<String> seenMessageIds =
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(512) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 500;
                }
            });

    public void handleIncoming(WebhookPayload.Message msg) {
        if (msg == null || !"text".equals(msg.type()) || msg.text() == null) return;

        synchronized (seenMessageIds) {
            if (!seenMessageIds.add(msg.id())) return; // ya procesado
        }

        String rawFrom = msg.from();
        // Mexico: el wa_id llega como "521..." pero la Cloud API exige enviar a "52..."
        final String from = (rawFrom.startsWith("521") && rawFrom.length() == 13)
                ? "52" + rawFrom.substring(3)
                : rawFrom;

        if (allowedNumbers.isPresent() && !allowedNumbers.get().isEmpty()
                && !allowedNumbers.get().contains(from)) {
            LOG.infof("Numero no autorizado: %s", from);
            return;
        }

        String body = msg.text().body();

        // Comando: generar el doc de letras del domingo
        if (body != null && body.strip().equalsIgnoreCase("letras")) {
            whatsApp.replyText(from, "Armando el doc de letras, busco en el historico. Dame unos segundos...");
            workers.submit(() -> generarLetras(from));
            return;
        }

        // Comando: copiar los acordeorios del historico a Notas/ del domingo.
        //   "notas"                -> todas las canciones (las de version unica se copian;
        //                             las de varias versiones piden eleccion)
        //   "notas en ti 2"        -> copia la version 2 de "en ti"
        //   "notas en ti todas"    -> copia todas las versiones de "en ti"
        if (body != null && body.strip().toLowerCase().startsWith("notas")) {
            String resto = body.strip().substring(5).strip();
            if (resto.isEmpty()) {
                whatsApp.replyText(from, "Buscando las notas en el historico...");
                workers.submit(() -> generarNotas(from));
            } else {
                workers.submit(() -> copiarNotaElegida(from, resto));
            }
            return;
        }

        // Comando: indexar TODO el historico de letras (correr una vez, tarda)
        if (body != null && body.strip().equalsIgnoreCase("indexar")) {
            whatsApp.replyText(from, "Indexando el historico completo de letras, esto puede tardar unos minutos...");
            workers.submit(() -> whatsApp.replyText(from, lyricsHistory.indexarTodo()));
            return;
        }

        List<String> urls = downloader.extractYouTubeUrls(body);
        if (urls.isEmpty()) return;

        final int semitones = pitchShifter.parseSemitones(body);

        String aviso = semitones == 0
                ? "Descargando %d cancion(es), te aviso...".formatted(urls.size())
                : "Descargando %d cancion(es) y ajustando tono %s%d, te aviso..."
                .formatted(urls.size(), semitones > 0 ? "+" : "", semitones);
        whatsApp.replyText(from, aviso);

        workers.submit(() -> process(from, urls, semitones));
    }

    private void process(String from, List<String> urls, int semitones) {
        try {
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);

            List<String> ok = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (String url : urls) {
                Path descargado = null;
                Path aSubir = null;
                try {
                    var descarga = downloader.downloadMp3(url);
                    descargado = descarga.archivo();

                    aSubir = descargado;
                    if (semitones != 0) {
                        aSubir = pitchShifter.shift(descargado, semitones);
                    }

                    driveService.uploadMp3(aSubir, estructura.playlistId());

                    // Version con tono: la original y variantes previas van a papelera
                    if (semitones != 0) {
                        String base = stripMp3(descargado.getFileName().toString());
                        var quitadas = driveService.trashVariants(
                                estructura.playlistId(), base, aSubir.getFileName().toString());
                        if (!quitadas.isEmpty()) {
                            LOG.infof("A papelera por cambio de tono: %s", quitadas);
                        }
                    }

                    String etiqueta = semitones == 0
                            ? descarga.titulo()
                            : "%s (%s%d)".formatted(descarga.titulo(),
                            semitones > 0 ? "+" : "", semitones);
                    ok.add(etiqueta);
                } catch (Exception e) {
                    LOG.errorf(e, "Fallo con %s", url);
                    failed.add(url);
                } finally {
                    deleteQuietly(descargado);
                    if (aSubir != null && !aSubir.equals(descargado)) {
                        deleteQuietly(aSubir);
                    }
                }
            }

            var sb = new StringBuilder();
            if (!ok.isEmpty()) {
                sb.append("Subidas a *").append(carpeta).append("/Playlist*:\n");
                ok.forEach(t -> sb.append("\u2022 ").append(t).append('\n'));
                sb.append('\n').append(estructura.link());
                // Sugerir notas si el historico tiene acordeorios de lo subido
                try {
                    List<String> nombres = ok.stream()
                            .map(t -> TitleCleaner.clean(t + ".mp3").nombre())
                            .toList();
                    long disponibles = lyricsHistory.refs(nombres).values().stream()
                            .filter(r -> !r.acordeorios.isEmpty()).count();
                    if (disponibles > 0) {
                        sb.append("\n\nHay notas de ").append(disponibles)
                                .append(" cancion(es) en el historico. Manda \"notas\" y las copio a *Notas/*.");
                    }
                } catch (Exception e) {
                    LOG.warn("No se pudo evaluar sugerencia de notas", e);
                }
                if (semitones == 0) {
                    sb.append("\n\n\u00BFLa quieres en otro tono? Reenvia el link con \"tono -2\" (o +1, +2...)");
                }
            }
            if (!failed.isEmpty()) {
                sb.append("\n\nNo se pudieron descargar:\n");
                failed.forEach(u -> sb.append("\u2022 ").append(u).append('\n'));
            }

            // Sugerencia del doc de letras:
            // - sin doc y ya hay 4+ canciones -> ofrecer crearlo
            // - con doc y entraron canciones nuevas -> ofrecer actualizarlo
            try {
                if (!ok.isEmpty()) {
                    int total = driveService.listMp3Names(estructura.playlistId()).size();
                    String docName = "Letras - " + carpeta + ".docx";
                    var doc = driveService.findFile(docName, estructura.domingoId());
                    if (doc == null && total >= SUGERIR_LETRAS_EN) {
                        sb.append("\n\nYa hay ").append(total)
                                .append(" canciones. Manda \"letras\" y te genero el doc de letras.");
                    } else if (doc != null) {
                        sb.append("\n\nManda \"letras\" para agregar los titulos nuevos al doc.");
                    }
                }
            } catch (Exception e) {
                LOG.warn("No se pudo evaluar sugerencia de letras", e);
            }

            whatsApp.replyText(from, sb.toString().strip());

        } catch (Exception e) {
            LOG.error("Error en pipeline", e);
            whatsApp.replyText(from, "Error al procesar: " + e.getMessage());
        }
    }

    private void generarLetras(String from) {
        try {
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);
            String docName = "Letras - " + carpeta + ".docx";

            List<String> mp3s = driveService.listMp3Names(estructura.playlistId());
            if (mp3s.isEmpty()) {
                whatsApp.replyText(from, "Aun no hay canciones en la playlist de " + carpeta + ".");
                return;
            }
            List<TitleCleaner.Titulo> titulos = mp3s.stream().map(TitleCleaner::clean).toList();

            // Buscar letras en docs historicos del Drive (una pasada por doc)
            var letras = lyricsHistory.buscar(titulos.stream().map(TitleCleaner.Titulo::nombre).toList());

            var existente = driveService.findFile(docName, estructura.domingoId());

            if (existente == null) {
                // Crear el esqueleto por primera vez
                byte[] docx = lyricsDocx.build(titulos, letras);
                var subido = driveService.uploadBytes(docName, docx, DriveService.DOCX_MIME, estructura.domingoId());
                var sb = new StringBuilder("Doc de letras creado con:\n");
                titulos.forEach(t -> {
                    sb.append("\u2022 ").append(t.display());
                    var l = letras.get(t.nombre());
                    if (l != null && !l.parrafos().isEmpty()) {
                        sb.append(" (letra encontrada, de ").append(l.fuente()).append(")");
                    } else {
                        sb.append(" (sin letra en el historico)");
                    }
                    sb.append('\n');
                });
                sb.append("\nEditalo aqui: ").append(subido.getWebViewLink());
                sb.append("\n\nLetras y tonos vienen del historico: puede que falten o no correspondan a esta version. Revisa el doc antes del domingo.");
                whatsApp.replyText(from, sb.toString());
                return;
            }

            // Ya existe: solo AGREGAR titulos faltantes al final, sin tocar lo escrito
            byte[] actual = driveService.downloadBytes(existente.getId());
            var sync = lyricsDocx.appendMissing(actual, titulos, letras);
            if (sync.agregados().isEmpty()) {
                whatsApp.replyText(from,
                        "El doc ya esta al dia. Editalo aqui:\n" + existente.getWebViewLink());
                return;
            }
            driveService.updateBytes(existente.getId(), sync.bytes(), DriveService.DOCX_MIME);
            var sb = new StringBuilder("Titulos agregados al doc:\n");
            sync.agregados().forEach(t -> sb.append("\u2022 ").append(t).append('\n'));
            sb.append("\nEditalo aqui: ").append(existente.getWebViewLink());
            whatsApp.replyText(from, sb.toString());

        } catch (Exception e) {
            LOG.error("Error generando doc de letras", e);
            whatsApp.replyText(from, "No pude crear el doc de letras: " + e.getMessage());
        }
    }

    private void generarNotas(String from) {
        try {
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);

            List<String> mp3s = driveService.listMp3Names(estructura.playlistId());
            if (mp3s.isEmpty()) {
                whatsApp.replyText(from, "Aun no hay canciones en la playlist de " + carpeta + ".");
                return;
            }

            List<String> copiadas = new ArrayList<>();
            List<String> sinNotas = new ArrayList<>();
            List<String> elecciones = new ArrayList<>();
            var refs = lyricsHistory.refs(mp3s.stream()
                    .map(n -> TitleCleaner.clean(n).nombre()).toList());

            for (String mp3 : mp3s) {
                String nombre = TitleCleaner.clean(mp3).nombre();
                var ref = refs.get(nombre);
                var variantes = ref == null ? List.<mx.salvador.wabot.media.LyricsHistoryService.Acordeorio>of()
                        : ref.acordeorios;
                if (variantes.isEmpty()) {
                    sinNotas.add(nombre);
                } else if (variantes.size() == 1) {
                    var a = variantes.get(0);
                    driveService.copyTo(a.id, a.name, estructura.notasId());
                    copiadas.add(nombre + " \u2192 " + a.name);
                } else {
                    // varias versiones: NO copiar, pedir eleccion
                    var menu = new StringBuilder("\"" + nombre + "\" tiene "
                            + variantes.size() + " versiones — dime cual:\n");
                    for (int i = 0; i < variantes.size(); i++) {
                        menu.append("\u2022 notas ").append(nombre).append(' ').append(i + 1)
                                .append(" \u2192 ").append(variantes.get(i).name).append('\n');
                    }
                    menu.append("\u2022 notas ").append(nombre).append(" todas");
                    elecciones.add(menu.toString());
                }
            }

            var sb = new StringBuilder();
            if (!copiadas.isEmpty()) {
                sb.append("Notas copiadas a *Notas/*:\n");
                copiadas.forEach(c -> sb.append("\u2022 ").append(c).append('\n'));
            }
            for (String menu : elecciones) {
                sb.append(sb.length() > 0 ? "\n" : "").append(menu).append('\n');
            }
            if (!sinNotas.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n" : "");
                sb.append("Sin notas en el historico:\n");
                sinNotas.forEach(s -> sb.append("\u2022 ").append(s).append('\n'));
                sb.append("Si el archivo si existe, revisa que este en la carpeta *notas* de su fecha y se llame como la cancion (ej. \"En Ti E.pdf\" o \"En Ti TONO E.docx\") — renombralo, y con el proximo \"letras\" o \"notas\" ya aparece.\n");
            }
            sb.append("\nLas notas vienen del historico: revisa que correspondan a la version de esta semana.");
            sb.append('\n').append(estructura.link());
            whatsApp.replyText(from, sb.toString());

        } catch (Exception e) {
            LOG.error("Error copiando notas", e);
            whatsApp.replyText(from, "No pude copiar las notas: " + e.getMessage());
        }
    }

    /** "en ti 2" o "en ti todas": copia la(s) version(es) elegida(s) de una cancion. */
    private void copiarNotaElegida(String from, String consulta) {
        try {
            String[] tokens = consulta.strip().split("\\s+");
            String ultimo = tokens[tokens.length - 1].toLowerCase();
            boolean todas = ultimo.equals("todas");
            int seleccion = -1;
            if (!todas && ultimo.matches("\\d+")) {
                seleccion = Integer.parseInt(ultimo);
            }
            if (!todas && seleccion < 1) {
                whatsApp.replyText(from,
                        "No entendi la eleccion. Usa: notas <cancion> <numero> o notas <cancion> todas");
                return;
            }
            String cancion = String.join(" ",
                    java.util.Arrays.copyOfRange(tokens, 0, tokens.length - 1)).strip();
            if (cancion.isBlank()) {
                whatsApp.replyText(from, "Falta la cancion. Ej: notas en ti 1");
                return;
            }

            var domingo = Fechas.proximoDomingo();
            var estructura = driveService.ensureSundayStructure(domingo);
            var refs = lyricsHistory.refs(List.of(cancion));
            var ref = refs.get(cancion);
            var variantes = ref == null ? List.<mx.salvador.wabot.media.LyricsHistoryService.Acordeorio>of()
                    : ref.acordeorios;

            if (variantes.isEmpty()) {
                whatsApp.replyText(from, "No encontre notas de \"" + cancion + "\" en el historico.");
                return;
            }
            if (!todas && seleccion > variantes.size()) {
                whatsApp.replyText(from, "\"" + cancion + "\" solo tiene "
                        + variantes.size() + " version(es).");
                return;
            }

            var sb = new StringBuilder("Copiado a *Notas/*:\n");
            if (todas) {
                for (var a : variantes) {
                    driveService.copyTo(a.id, a.name, estructura.notasId());
                    sb.append("\u2022 ").append(a.name).append('\n');
                }
            } else {
                var a = variantes.get(seleccion - 1);
                driveService.copyTo(a.id, a.name, estructura.notasId());
                sb.append("\u2022 ").append(a.name).append('\n');
            }
            whatsApp.replyText(from, sb.toString().strip());

        } catch (Exception e) {
            LOG.error("Error copiando nota elegida", e);
            whatsApp.replyText(from, "No pude copiar: " + e.getMessage());
        }
    }

    private static String stripMp3(String name) {
        return name.endsWith(".mp3") ? name.substring(0, name.length() - 4) : name;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }
}