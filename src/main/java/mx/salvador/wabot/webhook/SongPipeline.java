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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SongPipeline {

    private static final Logger LOG = Logger.getLogger(SongPipeline.class);

    /** Canciones tipicas de un servicio; al llegar aqui se sugiere el doc de letras. */
    private static final int SUGERIR_LETRAS_EN = 4;
    private static final Pattern RESPUESTA_VERSION =
            Pattern.compile("(?i)^(?:(?:la\\s+)?versi[o\u00f3]n\\s+)+(\\d+)\\s*[.!?]*$");

    @Inject YtDlpDownloader downloader;
    @Inject PitchShifter pitchShifter;
    @Inject DriveService driveService;
    @Inject LyricsDocxService lyricsDocx;
    @Inject LyricsHistoryService lyricsHistory;
    @Inject WhatsAppService whatsApp;
    @Inject PlaylistToneFlow playlistTone;
    @Inject ContextStore contextStore;
    @Inject ChordDraftService chordDrafts;
    private final Set<String> draftsInProgress = ConcurrentHashMap.newKeySet();
    record DraftChoice(String song, String targetKey, DriveService.EstructuraDomingo folder,
                       java.time.LocalDate sunday, java.time.Instant expires, boolean authorized) {}
    private final Map<String, List<DraftChoice>> draftChoices = new ConcurrentHashMap<>();

    DraftChoice pendingDraftChoice(String from) {
        var list = draftChoices.get(from);
        if (list == null || list.isEmpty()) return null;
        var first = list.get(0);
        if (first.expires().isBefore(java.time.Instant.now()) || !first.sunday().equals(Fechas.proximoDomingo())) {
            draftChoices.remove(from, list); return null;
        }
        return first;
    }

    private boolean replaceDraftChoice(String from, DraftChoice expected, DraftChoice next) {
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        draftChoices.computeIfPresent(from, (key, list) -> {
            if (list.get(0) != expected) return list;
            var updated = new ArrayList<>(list);
            if (next == null) updated.remove(0); else updated.set(0, next);
            changed.set(true);
            return updated.isEmpty() ? null : List.copyOf(updated);
        });
        return changed.get();
    }

    private void askDraftChoice(String from, DraftChoice choice) {
        if (!choice.authorized()) {
            whatsApp.replyText(from, "No encontre notas de " + choice.song() + ". ¿Quieres buscar en internet una version base? Responde si o no.");
        } else {
            whatsApp.replyText(from, "¿En que tono busco la base de " + choice.song()
                    + "? Escribe original, D (Re), D# (Re sostenido) o Dm (Re menor). Tambien puedes escribir el nombre del tono. Para salir, cancelar.");
        }
    }

    private void nextDraftChoice(String from, DraftChoice choice) {
        if (replaceDraftChoice(from, choice, null)) {
            var next = pendingDraftChoice(from);
            if (next != null) askDraftChoice(from, next);
        }
    }

    void chooseDraft(String from, DraftChoice expected, String decision) {
        if (expected == null || pendingDraftChoice(from) != expected) return;
        if (decision.equals("decline")) {
            workState.note(expected.song(), "Busqueda web omitida por el usuario; no se creo documento");
            whatsApp.replyText(from, "De acuerdo, no creare notas de " + expected.song() + ".");
            nextDraftChoice(from, expected); return;
        }
        if (!expected.authorized()) {
            if (!decision.equals("search")) { askDraftChoice(from, expected); return; }
            var ready = new DraftChoice(expected.song(), expected.targetKey(), expected.folder(), expected.sunday(), expected.expires(), true);
            if (replaceDraftChoice(from, expected, ready)) {
                workState.note(expected.song(), "Esperando tonalidad antes de buscar en internet");
                askDraftChoice(from, ready);
            }
            return;
        }
        String task = from + ":" + expected.song();
        if (!draftsInProgress.add(task)) { whatsApp.replyText(from, "Sigo procesando esa peticion; te aviso al terminar."); return; }
        try {
            {
                String target = decision.equals("original") ? "" : decision;
                if (!target.isEmpty() && !mx.salvador.wabot.media.ChordTransposer.validKey(target)) { askDraftChoice(from, expected); return; }
                String name = "BORRADOR - " + expected.song().replaceAll("[\\\\/:*?\"<>|]", " ")
                        + (target.isEmpty() ? " - base web" : " - " + target) + ".docx";
                var existing = driveService.findFile(name, expected.folder().notasId());
                workState.note(expected.song(), "Buscando base web y preparando DOCX");
                if (existing == null) whatsApp.replyText(from, "Buscando acordes de " + expected.song()
                        + (target.isEmpty() ? " en tono original" : " en " + target) + " y preparando el DOCX, te aviso...");
                ChordDraftService.Document doc = existing == null
                        ? chordDrafts.create(expected.song(), workState.reference(expected.song()), target) : null;
                if (pendingDraftChoice(from) != expected) return;
                var uploaded = existing != null ? existing : driveService.uploadBytes(name, doc.bytes(), DriveService.DOCX_MIME, expected.folder().notasId());
                workState.copied(expected.song(), uploaded.getId(), name, expected.folder().notasId());
                workState.note(expected.song(), "DOCX de acordes disponible; revisar antes de usar");
                whatsApp.replyText(from, (existing != null ? "Conserve el DOCX existente y tus ediciones." : "Borrador de notas creado. Revisa los acordes y la tonalidad del DOCX antes de usarlo.") + "\n" + uploaded.getWebViewLink());
                nextDraftChoice(from, expected);
            }
        } catch (Exception e) {
            if (pendingDraftChoice(from) != expected) return;
            String reason = e instanceof GeminiSongInterpreter.Failure failure ? failure.userMessage()
                    : e instanceof java.io.IOException || e instanceof IllegalArgumentException ? e.getMessage() : "No pude completar la operacion";
            whatsApp.replyText(from, reason + ". No cree ningun documento.");
            {
                workState.note(expected.song(), "No se completo la busqueda web; no se creo documento");
                nextDraftChoice(from, expected);
            }
        } finally { draftsInProgress.remove(task); }
    }

    String draftDecision(String body, DraftChoice choice) {
        String text = body.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[.!¡¿?]+$", "").strip().replaceAll("\\s+", " ");
        if (text.matches("no|no gracias|no buscar|omitir")) return "decline";
        if (!choice.authorized() && text.matches("si|sí|si busca|sí busca|buscar|busca|buscar en la web")) return "search";
        if (choice.authorized() && text.matches("(?:(?:conservar|conserva|en) )?(?:(?:el|la) )?(?:(?:tono|tonalidad|version) )?original")) return "original";
        if (choice.authorized()) return draftKeyReply(text);
        return null;
    }

    static String draftKeyReply(String text) {
        var match = Pattern.compile("(?i)^(?:(?:en|tono)\\s+)?(do|re|mi|fa|sol|la|si|[a-g])\\s*(sostenido|bemol|#|b)?\\s*(menor|mayor|m)?[.!]?$")
                .matcher(text.strip());
        if (!match.matches()) return null;
        String root = match.group(1).toLowerCase(java.util.Locale.ROOT);
        root = Map.of("do", "C", "re", "D", "mi", "E", "fa", "F", "sol", "G", "la", "A", "si", "B").getOrDefault(root, root.toUpperCase(java.util.Locale.ROOT));
        String accidental = match.group(2) == null ? "" : match.group(2).toLowerCase(java.util.Locale.ROOT);
        if (accidental.equals("sostenido")) accidental = "#";
        if (accidental.equals("bemol")) accidental = "b";
        String mode = match.group(3) == null ? "" : match.group(3).toLowerCase(java.util.Locale.ROOT);
        return root + accidental + (mode.equals("menor") || mode.equals("m") ? "m" : "");
    }

    /** Numeros permitidos (formato SIN el 1: 52 + 10 digitos). Vacio = todos. */
    @ConfigProperty(name = "bot.allowed-numbers")
    Optional<List<String>> allowedNumbers;

    /**
     * Descargas simultaneas. Regla practica: ~1 por cada 0.5 vCPU del server
     * (cada descarga es yt-dlp + deno + ffmpeg). Env var: DOWNLOAD_CONCURRENCY.
     */
    @ConfigProperty(name = "bot.download-concurrency", defaultValue = "2")
    int downloadConcurrency;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> lyricsInProgress = ConcurrentHashMap.newKeySet();
    final MusicWorkState workState = new MusicWorkState();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> downloads = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> afterDownloads = new ConcurrentHashMap<>();
    // Las consultas cortas no deben esperar en la cola de descargas/documentos.
    private final ExecutorService conversations = Executors.newFixedThreadPool(2);

    Map<String, Object> context(String from, List<String> songs) {
        synchronized (downloads) {
            var count = downloads.get(from);
            Map<String, Object> state = new LinkedHashMap<>(workState.snapshot(songs, count == null ? 0 : count.get()));
            state.put("peticionesDocumentosEnEspera", afterDownloads.getOrDefault(from, List.of()).size());
            state.put("conversacion", whatsApp == null ? List.of() : whatsApp.conversation(from));
            var draftChoice = pendingDraftChoice(from);
            if (draftChoice != null) state.put("preguntaBaseWeb", Map.of("cancion", draftChoice.song(),
                    "etapa", !draftChoice.authorized() ? "permiso_busqueda" : "elegir_tono",
                    "tonoSolicitado", draftChoice.targetKey()));
            pendingNoteChoice(from); // Descarta menus vencidos antes de formar el contexto.
            var pending = versionesPendientes.get(from);
            state.put("preguntasNotasPendientes", pending == null ? List.of() : pending.choices().stream()
                    .map(choice -> Map.of("cancion", choice.song(), "versiones",
                            choice.versions().stream().map(NoteVersion::name).toList())).toList());
            return state;
        }
    }

    void verifyNotes(List<String> songs, String notesFolder) {
        if (driveService == null) return;
        for (String song : songs) {
            var copies = workState.copies(song);
            if (copies.isEmpty()) continue;
            try {
                var present = new ArrayList<String>();
                for (var copy : copies) {
                    if (copy.folder().equals(notesFolder) && driveService.noteCopyPresent(copy.id(), notesFolder)) present.add(copy.name());
                }
                workState.note(song, present.isEmpty() ? "Las copias registradas ya no estan en Notas/"
                        : "En Notas/: " + String.join(", ", present));
            } catch (Exception e) { workState.note(song, "No pude verificar las copias en Drive ahora; resultado sin confirmar"); }
        }
    }

    void requestDocuments(String from, boolean notes, boolean lyrics, String song) {
        Runnable task = () -> {
            if (notes) generarNotas(from, song);
            if (lyrics) {
                if (song == null) generarLetras(from);
                else generarLetras(from, song);
            }
        };
        synchronized (downloads) {
            var count = downloads.get(from);
            if (count != null && count.get() > 0) {
                afterDownloads.computeIfAbsent(from, ignored -> new ArrayList<>()).add(task);
                whatsApp.replyText(from, "Recibi tu peticion de documentos. Esperare a que terminen las descargas pendientes para incluir esas canciones.");
                return;
            }
            workers.submit(task);
        }
    }

    private void downloadFinished(String from, int size) {
        synchronized (downloads) {
            var count = downloads.get(from);
            if (count.addAndGet(-size) == 0) {
                downloads.remove(from);
                var tasks = afterDownloads.remove(from);
                if (tasks != null) tasks.forEach(workers::submit);
            }
        }
    }
    /** Canciones con versiones pendientes de elegir, por numero de WhatsApp. */
    record NoteVersion(String id, String name) {}
    record NoteChoice(String song, List<NoteVersion> versions) {}
    private record PendingNotes(List<NoteChoice> choices, String folderId,
                                java.time.LocalDate sunday, java.time.Instant expires) {}
    private final Map<String, PendingNotes> versionesPendientes = new ConcurrentHashMap<>();

    NoteChoice pendingNoteChoice(String from) {
        PendingNotes pending = versionesPendientes.get(from);
        if (pending == null) return null;
        if (pending.expires().isBefore(java.time.Instant.now()) || !pending.sunday().equals(Fechas.proximoDomingo())) {
            versionesPendientes.remove(from, pending);
            return null;
        }
        return pending.choices().get(0);
    }

    void cancelNoteChoice(String from) {
        draftChoices.remove(from);
        var pending = versionesPendientes.remove(from);
        persistNoteChoices();
        if (pending != null) pending.choices().forEach(choice -> workState.note(choice.song(), "Eleccion de version cancelada; no se confirmo la copia"));
        synchronized (downloads) { afterDownloads.remove(from); }
    }

    /**
     * Descargas de canciones en paralelo. El 80% del tiempo por cancion es
     * espera de red (YouTube/proxy/Drive), asi que traslaparlas reduce el
     * tiempo total de una playlist de ~N*40s a ~max(40s..60s).
     */
    private ExecutorService downloadPool;

    @jakarta.annotation.PostConstruct
    void init() {
        workState.attach(contextStore);
        if (contextStore != null) {
            var saved = contextStore.read("noteChoices", new com.fasterxml.jackson.core.type.TypeReference<Map<String, PendingNotes>>() {});
            if (saved != null) saved.forEach((from, value) -> {
                if (value.expires().isAfter(java.time.Instant.now()) && value.sunday().equals(Fechas.proximoDomingo())) versionesPendientes.put(from, value);
            });
        }
        downloadPool = Executors.newFixedThreadPool(Math.max(1, downloadConcurrency));
    }

    private void persistNoteChoices() { if (contextStore != null) contextStore.save("noteChoices", versionesPendientes); }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        workers.shutdown();
        conversations.shutdown();
        progress.shutdownNow();
        if (downloadPool != null) downloadPool.shutdown();
    }

    /** Dedupe de message IDs: Meta reintenta webhooks si no respondes rapido. */
    private final Set<String> seenMessageIds =
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(512) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 500;
                }
            });

    public void handleIncoming(WebhookPayload.Message msg) {
        if (msg == null || msg.body() == null) return;

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

        String body = msg.body();
        if (whatsApp != null) whatsApp.rememberIncoming(from, body);
        if (playlistTone != null && playlistTone.isConfirmation(body)) {
            workers.submit(() -> playlistTone.handle(from, body));
            return;
        }

        // Los links tienen prioridad aunque el mensaje empiece con "notas" u otro comando.
        List<String> urls = downloader.extractYouTubeUrls(body).stream()
                .map(YtDlpDownloader::cleanUrl).distinct().toList();
        if (!urls.isEmpty()) {
            final int semitones = pitchShifter.parseSemitones(body);
            String aviso = semitones == 0
                    ? "Descargando %d cancion(es), te aviso...".formatted(urls.size())
                    : "Descargando %d cancion(es) y ajustando tono %s%d, te aviso..."
                    .formatted(urls.size(), semitones > 0 ? "+" : "", semitones);
            whatsApp.replyText(from, aviso);
            synchronized (downloads) {
                downloads.computeIfAbsent(from, ignored -> new java.util.concurrent.atomic.AtomicInteger()).addAndGet(urls.size());
            }
            workers.submit(() -> {
                try { process(from, urls, semitones, body); }
                finally { downloadFinished(from, urls.size()); }
            });
            return;
        }

        // Respuesta corta a la pregunta de una nota con varias versiones.
        DraftChoice draftChoice = pendingDraftChoice(from);
        if (draftChoice != null) {
            String decision = draftDecision(body, draftChoice);
            if (decision != null) { workers.submit(() -> chooseDraft(from, draftChoice, decision)); return; }
        }
        if (body != null && RESPUESTA_VERSION.matcher(body.strip()).matches()) {
            NoteChoice expected = pendingNoteChoice(from);
            if (lyricsInProgress.contains(from)) whatsApp.replyText(from,
                    "Recibi tu eleccion de notas. El documento de letras sigue en proceso; te avisare al terminar.");
            workers.submit(() -> copiarVersionPendiente(from, body.strip(), expected));
            return;
        }

        // La pregunta pendiente tambien da contexto a "la segunda", "2" o un nombre de archivo.
        if (body != null && pendingNoteChoice(from) != null && playlistTone.naturalLanguageEnabled()
                && !body.strip().matches("(?i)^(letras|notas|indexar|cancelar|cambiar tonalidad)$")) {
            conversations.submit(() -> playlistTone.handleNatural(from, body));
            return;
        }

        if (playlistTone.accepts(body)) {
            workers.submit(() -> playlistTone.handle(from, body));
            return;
        }

        // Comando: generar el doc de letras del domingo
        if (body != null && body.strip().equalsIgnoreCase("letras")) {
            requestDocuments(from, false, true, null);
            return;
        }

        // Comando: copiar los acordeorios del historico a Notas/ del domingo.
        //   "notas"                -> todas las canciones (las de version unica se copian;
        //                             las de varias versiones piden eleccion)
        //   "notas en ti version 2" -> copia la version 2 de "en ti"
        //   "notas en ti todas"    -> copia todas las versiones de "en ti"
        if (body != null && body.strip().toLowerCase().startsWith("notas")) {
            String resto = body.strip().substring(5).strip();
            if (resto.isEmpty()) {
                whatsApp.replyText(from, "Buscando las notas en el historico...");
                requestDocuments(from, true, false, null);
            } else if (playlistTone.naturalLanguageEnabled()
                    && !resto.matches("(?i).+\\s+(?:(?:version|versión)\\s+\\d+|todas)")) {
                conversations.submit(() -> playlistTone.handleNatural(from, body));
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

        if (body != null && !body.isBlank() && playlistTone.naturalLanguageEnabled()) {
            conversations.submit(() -> playlistTone.handleNatural(from, body));
        }
    }

    static String accompanyingInstructions(String body, int appliedSemitones) {
        if (body == null) return "";
        String text = body.replaceAll("(?i)https?://\\S+", " ");
        // Esta indicacion ya fue aplicada al descargar; no pedir a Gemini que la repita.
        if (appliedSemitones != 0) text = text.replaceAll("(?i)\\btono\\s*[+-]?\\d{1,2}\\b", " ");
        text = text.replaceAll("\\s+", " ").strip();
        return text.codePoints().anyMatch(Character::isLetterOrDigit) ? text : "";
    }

    /** Resultado de procesar un link: etiqueta si salio bien, o la url que fallo. */
    private record Resultado(String etiqueta, String urlFallida, String audioId) {}

    void process(String from, List<String> urls, int semitones, String originalMessage) {
        try {
            String instructions = accompanyingInstructions(originalMessage, semitones);
            boolean followUp = !instructions.isEmpty() && playlistTone.naturalLanguageEnabled();
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);

            // Lanzar todas las canciones en paralelo (limitado por downloadPool)
            List<java.util.concurrent.Future<Resultado>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(downloadPool.submit(
                        () -> procesarUna(url, semitones, estructura)));
            }

            // Recoger en el mismo orden en que llegaron los links
            List<String> ok = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            List<String> uploadedIds = new ArrayList<>();
            for (var f : futures) {
                Resultado r = f.get();
                if (r.etiqueta() != null) {
                    ok.add(r.etiqueta());
                    uploadedIds.add(r.audioId());
                }
                else failed.add(r.urlFallida());
            }

            var sb = new StringBuilder();
            if (!ok.isEmpty()) {
                // Una subida multiple no define inequivocamente "esa cancion".
                playlistTone.rememberUpload(from, uploadedIds.size() == 1 ? uploadedIds.get(0) : null);
                sb.append("Subidas a *").append(carpeta).append("/Playlist*:\n");
                ok.forEach(t -> sb.append("\u2022 ").append(t).append('\n'));
                sb.append('\n').append(estructura.link());
                if (semitones == 0 && !followUp) {
                    sb.append("\n\nEscribe \"cambiar tonalidad\" y te preguntare que cancion quieres ajustar y cuantos semitonos subir o bajar.");
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
                if (!ok.isEmpty() && !followUp) {
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

            // Las letras/notas deben consultarse despues de que todas las subidas hayan terminado.
            if (followUp && !uploadedIds.isEmpty()) {
                playlistTone.handleAfterUpload(from, instructions, List.copyOf(uploadedIds));
            }

        } catch (Exception e) {
            LOG.error("Error en pipeline", e);
            whatsApp.replyText(from, "Error al procesar: " + e.getMessage());
        }
    }

    void generarLetras(String from) {
        generarLetras(from, null);
    }

    void generarLetras(String from, String selectedSong) {
        if (!lyricsInProgress.add(from)) {
            whatsApp.replyText(from, "El documento de letras sigue en proceso. Te avisare cuando termine.");
            return;
        }
        java.util.concurrent.ScheduledFuture<?> reminder = null;
        try {
            workState.document("En preparacion: buscando letras en el historico");
            whatsApp.replyText(from, "Estoy preparando el documento de letras y buscando en el historico. Puede tardar varios minutos. Puedes seguir eligiendo las versiones de notas; te avisare cuando el documento este listo.");
            reminder = progress.scheduleAtFixedRate(() -> {
                try {
                    whatsApp.replyText(from, "Sigo preparando el documento de letras; aun no termina. Te avisare cuando este listo.");
                } catch (Exception e) { LOG.warn("No se pudo enviar aviso de progreso de letras"); }
            }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);
            String docName = "Letras - " + carpeta + ".docx";

            List<String> mp3s = driveService.listMp3Names(estructura.playlistId()).stream()
                    .filter(name -> selectedSong == null || name.equals(selectedSong)).toList();
            if (mp3s.isEmpty()) {
                workState.document("Sin canciones para preparar el documento");
                whatsApp.replyText(from, "Aun no hay canciones en la playlist de " + carpeta + ".");
                return;
            }
            List<TitleCleaner.Titulo> titulos = mp3s.stream().map(TitleCleaner::clean).toList();
            mp3s.forEach(song -> workState.lyric(song, "En preparacion"));

            // Buscar letras en docs historicos del Drive (una pasada por doc)
            var letras = lyricsHistory.buscar(titulos.stream().map(TitleCleaner.Titulo::nombre).toList());

            var existente = driveService.findFile(docName, estructura.domingoId());

            if (existente == null) {
                // Crear el esqueleto por primera vez
                byte[] docx = lyricsDocx.build(titulos, letras);
                var subido = driveService.uploadBytes(docName, docx, DriveService.DOCX_MIME, estructura.domingoId());
                workState.document("Listo: " + subido.getWebViewLink());
                titulos.forEach(t -> workState.lyric(t.nombre(),
                        letras.containsKey(t.nombre()) && !letras.get(t.nombre()).parrafos().isEmpty()
                                ? "Incluida desde el historico; revisar version" : "Titulo incluido, sin letra encontrada en el historico"));
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
                workState.document("Al dia: " + existente.getWebViewLink());
                mp3s.forEach(song -> workState.lyric(song, "Titulo presente en el documento; contenido conservado sin verificar"));
                whatsApp.replyText(from,
                        "El doc ya esta al dia. Editalo aqui:\n" + existente.getWebViewLink());
                return;
            }
            driveService.updateBytes(existente.getId(), sync.bytes(), DriveService.DOCX_MIME);
            workState.document("Actualizado: " + existente.getWebViewLink());
            mp3s.forEach(song -> workState.lyric(song, "Titulo presente en el documento; revisar contenido"));
            var sb = new StringBuilder("Titulos agregados al doc:\n");
            sync.agregados().forEach(t -> sb.append("\u2022 ").append(t).append('\n'));
            sb.append("\nEditalo aqui: ").append(existente.getWebViewLink());
            whatsApp.replyText(from, sb.toString());

        } catch (Exception e) {
            LOG.error("Error generando doc de letras", e);
            workState.document("Fallo la preparacion del documento; no se confirmo el resultado");
            whatsApp.replyText(from, "No pude crear el doc de letras: " + e.getMessage());
        } finally {
            if (reminder != null) reminder.cancel(false);
            lyricsInProgress.remove(from);
        }
    }

    private void generarNotas(String from) {
        generarNotas(from, null);
    }

    void generarNotas(String from, String selectedSong) {
        List<String> requestedSongs = new ArrayList<>();
        try {
            versionesPendientes.remove(from);
            persistNoteChoices();
            var domingo = Fechas.proximoDomingo();
            var carpeta = Fechas.nombreCarpeta(domingo);
            var estructura = driveService.ensureSundayStructure(domingo);

            List<String> mp3s = driveService.listMp3Names(estructura.playlistId()).stream()
                    .filter(name -> selectedSong == null || name.equals(selectedSong)).toList();
            if (mp3s.isEmpty()) {
                whatsApp.replyText(from, "Aun no hay canciones en la playlist de " + carpeta + ".");
                return;
            }

            List<String> copiadas = new ArrayList<>();
            requestedSongs.addAll(mp3s);
            mp3s.forEach(song -> workState.note(song, "Buscando notas en el historico"));
            List<String> sinNotas = new ArrayList<>();
            List<NoteChoice> elecciones = new ArrayList<>();
            var refs = lyricsHistory.refs(mp3s.stream()
                    .map(n -> TitleCleaner.clean(n).nombre()).toList());

            for (String mp3 : mp3s) {
                String nombre = TitleCleaner.clean(mp3).nombre();
                var ref = refs.get(nombre);
                var variantes = ref == null ? List.<mx.salvador.wabot.media.LyricsHistoryService.Acordeorio>of()
                        : ref.acordeorios;
                if (variantes.isEmpty()) {
                    workState.note(nombre, "No se encontraron notas en el historico");
                    sinNotas.add(nombre);
                } else if (variantes.size() == 1) {
                    var a = variantes.get(0);
                    var copy = driveService.copyTo(a.id, a.name, estructura.notasId());
                    if (copy != null) workState.copied(nombre, copy.getId(), a.name, estructura.notasId());
                    workState.note(nombre, "Copiadas: " + a.name);
                    copiadas.add(nombre + " \u2192 " + a.name);
                } else {
                    workState.note(nombre, "Pendiente de elegir entre " + variantes.size() + " versiones");
                    // Varias versiones: se preguntan una por una para que la
                    // respuesta pueda ser solo "version 1" o "version 2".
                    elecciones.add(new NoteChoice(nombre, variantes.stream()
                            .map(a -> new NoteVersion(a.id, a.name)).toList()));
                }
            }

            var sb = new StringBuilder();
            if (!copiadas.isEmpty()) {
                sb.append("Notas copiadas a *Notas/*:\n");
                copiadas.forEach(c -> sb.append("\u2022 ").append(c).append('\n'));
            }
            if (!elecciones.isEmpty()) {
                versionesPendientes.put(from, new PendingNotes(List.copyOf(elecciones), estructura.notasId(),
                        domingo, java.time.Instant.now().plusSeconds(1800)));
                persistNoteChoices();
                sb.append(sb.length() > 0 ? "\n" : "");
                appendPreguntaDeVersion(sb, elecciones.get(0));
            }
            if (!sinNotas.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n" : "");
                sb.append("Sin notas en el historico:\n");
                sinNotas.forEach(s -> sb.append("\u2022 ").append(s).append('\n'));
                if (chordDrafts != null && chordDrafts.available()) sb.append("Puedo buscar una version base si lo autorizas.\n");
                else sb.append("La busqueda web no esta configurada; no generare notas sin fuentes.\n");
            }
            whatsApp.replyText(from, sb.toString());
            if (chordDrafts != null && chordDrafts.available()) {
                for (String song : sinNotas) createNoteDraft(from, song, "", estructura);
            }

        } catch (Exception e) {
            requestedSongs.forEach(song -> workState.noteFailed(song));
            LOG.error("Error copiando notas", e);
            whatsApp.replyText(from, "No pude copiar las notas: " + e.getMessage());
        }
    }

    void requestNoteDraft(String from, String song, String targetKey) {
        workers.submit(() -> {
            try { createNoteDraft(from, song, targetKey, driveService.ensureSundayStructure(Fechas.proximoDomingo())); }
            catch (Exception e) { whatsApp.replyText(from, "No pude preparar la carpeta para el borrador de notas."); }
        });
    }

    void createNoteDraft(String from, String song, String targetKey, DriveService.EstructuraDomingo folder) {
        offerNoteDraft(from, song, targetKey, folder);
    }

    private void offerNoteDraft(String from, String song, String targetKey, DriveService.EstructuraDomingo folder) {
        if (chordDrafts == null || !chordDrafts.available()) {
            whatsApp.replyText(from, "La investigacion web de notas no esta habilitada. Configura NOTES_WEB_ENABLED y TAVILY_API_KEY; tambien requiere Gemini.");
            return;
        }
        String title = MusicWorkState.key(song);
        pendingDraftChoice(from);
        var choice = new DraftChoice(title, targetKey, folder, Fechas.proximoDomingo(), java.time.Instant.now().plusSeconds(1800), false);
        draftChoices.compute(from, (key, list) -> {
            var queue = new ArrayList<>(list == null ? List.<DraftChoice>of() : list);
            if (queue.stream().noneMatch(item -> item.song().equals(title)) && queue.size() < 20) queue.add(choice);
            return List.copyOf(queue);
        });
        workState.note(title, "Sin notas; esperando permiso para buscar una version base en internet");
        if (pendingDraftChoice(from) == choice) {
            askDraftChoice(from, choice);
        }
    }

    /** Copia la version indicada para la cancion pendiente y, si aplica, pregunta la siguiente. */
    private void copiarVersionPendiente(String from, String respuesta, NoteChoice expected) {
        try {
            Matcher matcher = RESPUESTA_VERSION.matcher(respuesta);
            if (!matcher.matches()) return;
            int seleccion = Integer.parseInt(matcher.group(1));
            chooseNoteVersion(from, expected, seleccion);
        } catch (NumberFormatException e) {
            repeatNoteQuestion(from);
        }
    }

    /** Copia el ID de la opcion mostrada, aunque el indice del historico haya cambiado. */
    synchronized void chooseNoteVersion(String from, NoteChoice expected, int selection) {
        try {
            NoteChoice current = pendingNoteChoice(from);
            if (current == null) {
                whatsApp.replyText(from, "No hay una eleccion de notas pendiente. Pideme las notas para buscar sus versiones.");
                return;
            }
            if (current != expected) return; // Respuesta a una pregunta que ya cambio.
            if (selection < 1 || selection > current.versions().size()) {
                repeatNoteQuestion(from);
                return;
            }
            PendingNotes pending = versionesPendientes.get(from);
            var elegida = current.versions().get(selection - 1);
            var copy = driveService.copyTo(elegida.id(), elegida.name(), pending.folderId());
            if (copy != null) workState.copied(current.song(), copy.getId(), elegida.name(), pending.folderId());
            workState.note(current.song(), "Copiadas: " + elegida.name());

            List<NoteChoice> restantes = pending.choices().subList(1, pending.choices().size());
            var sb = new StringBuilder("Copiado a *Notas/*:\n\u2022 ").append(elegida.name());
            if (restantes.isEmpty()) {
                versionesPendientes.remove(from, pending);
            } else {
                var next = new PendingNotes(List.copyOf(restantes), pending.folderId(), pending.sunday(), pending.expires());
                if (versionesPendientes.replace(from, pending, next)) {
                    sb.append("\n\n");
                    appendPreguntaDeVersion(sb, restantes.get(0));
                }
            }
            whatsApp.replyText(from, sb.toString());
        } catch (Exception e) {
            LOG.error("Error copiando version pendiente", e);
            whatsApp.replyText(from, "No pude copiar la version: " + e.getMessage());
        } finally {
            persistNoteChoices();
        }
    }

    void repeatNoteQuestion(String from) {
        NoteChoice choice = pendingNoteChoice(from);
        if (choice == null) return;
        var message = new StringBuilder("¿Cual de estas versiones prefieres?\n");
        appendPreguntaDeVersion(message, choice);
        whatsApp.replyText(from, message.toString());
    }

    private void appendPreguntaDeVersion(StringBuilder sb, NoteChoice choice) {
        sb.append("\"").append(choice.song()).append("\" tiene ").append(choice.versions().size())
                .append(" versiones. ¿Cual prefieres? Puedes decir la segunda, version 2 o el nombre del archivo:\n");
        for (int i = 0; i < choice.versions().size(); i++) {
            sb.append("\u2022 version ").append(i + 1).append(" \u2192 ")
                    .append(choice.versions().get(i).name()).append('\n');
        }
    }

    /** "en ti version 2" o "en ti todas": copia la(s) version(es) elegida(s) de una cancion. */
    private void copiarNotaElegida(String from, String consulta) {
        try {
            String[] tokens = consulta.strip().split("\\s+");
            String ultimo = tokens[tokens.length - 1].toLowerCase();
            boolean todas = ultimo.equals("todas");
            int seleccion = -1;
            int tokensDeCancion = tokens.length - 1;
            if (!todas && tokens.length >= 2
                    && (tokens[tokens.length - 2].equalsIgnoreCase("version")
                    || tokens[tokens.length - 2].equalsIgnoreCase("versi\u00f3n"))
                    && ultimo.matches("\\d+")) {
                seleccion = Integer.parseInt(ultimo);
                tokensDeCancion = tokens.length - 2;
            }
            if (!todas && seleccion < 1) {
                whatsApp.replyText(from,
                        "No entendi la eleccion. Usa: notas <cancion> version <numero> o notas <cancion> todas");
                return;
            }
            String cancion = String.join(" ",
                    java.util.Arrays.copyOfRange(tokens, 0, tokensDeCancion)).strip();
            if (cancion.isBlank()) {
                whatsApp.replyText(from, "Falta la cancion. Ej: notas en ti version 1");
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
                    var copy = driveService.copyTo(a.id, a.name, estructura.notasId());
                    if (copy != null) workState.copied(cancion, copy.getId(), a.name, estructura.notasId());
                    sb.append("\u2022 ").append(a.name).append('\n');
                    workState.note(cancion, "Versiones copiadas por peticion explicita");
                }
            } else {
                var a = variantes.get(seleccion - 1);
                var copy = driveService.copyTo(a.id, a.name, estructura.notasId());
                if (copy != null) workState.copied(cancion, copy.getId(), a.name, estructura.notasId());
                workState.note(cancion, "Copiadas: " + a.name);
                sb.append("\u2022 ").append(a.name).append('\n');
            }
            whatsApp.replyText(from, sb.toString().strip());

        } catch (Exception e) {
            LOG.error("Error copiando nota elegida", e);
            whatsApp.replyText(from, "No pude copiar: " + e.getMessage());
        }
    }

    /** Baja, ajusta tono y sube UNA cancion. Corre en downloadPool. */
    private Resultado procesarUna(String url, int semitones,
                                  DriveService.EstructuraDomingo estructura) {
        Path descargado = null;
        Path aSubir = null;
        try {
            var descarga = downloader.downloadMp3(url);
            descargado = descarga.archivo();

            aSubir = descargado;
            if (semitones != 0) {
                aSubir = pitchShifter.shift(descargado, semitones);
            }

            var uploaded = driveService.uploadMp3(aSubir, estructura.playlistId());
            workState.reference(aSubir.getFileName().toString(), url);

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
            return new Resultado(etiqueta, null, uploaded.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Fallo con %s", url);
            return new Resultado(null, url, null);
        } finally {
            deleteQuietly(descargado);
            if (aSubir != null && !aSubir.equals(descargado)) {
                deleteQuietly(aSubir);
            }
            // Cada descarga vive en su subdirectorio unico; borrarlo al terminar
            if (descargado != null) {
                deleteQuietly(descargado.getParent());
            }
        }
    }

    private static String stripMp3(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mp3") || n.endsWith(".m4a")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }
}
