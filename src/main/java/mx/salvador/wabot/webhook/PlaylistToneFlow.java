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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Conversacion: cambiar tonalidad -> una cancion -> subir/bajar semitonos. */
@ApplicationScoped
public class PlaylistToneFlow {
    private static final Logger LOG = Logger.getLogger(PlaylistToneFlow.class);
    private static final Pattern SONGS = Pattern.compile("^canci[oó]n(?:es)?(?:\\s+.*)?$");
    private static final Pattern AMOUNT = Pattern.compile("^([+-]?\\d+)(?:\\s+semitonos?)?$");
    private static final Pattern ADJUSTMENT = Pattern.compile("^(subir|bajar)\\s+([+-]?\\d+)(?:\\s+semitonos?)?$");
    private static final Pattern PREVIOUS_SHIFT = Pattern.compile("^(.*) \\(([+-]\\d+)\\)$");

    @Inject DriveService drive;
    @Inject PitchShifter shifter;
    @Inject WhatsAppService whatsApp;
    @Inject GeminiSongInterpreter interpreter;
    @Inject SongPipeline pipeline;
    @Inject ContextStore contextStore;

    @jakarta.annotation.PostConstruct
    void loadContext() {
        var saved = contextStore.read("recentSongs", new com.fasterxml.jackson.core.type.TypeReference<Map<String, RecentSong>>() {});
        if (saved != null) saved.forEach((key, value) -> {
            if (value.expires().isAfter(Instant.now()) && value.sunday().equals(Fechas.proximoDomingo())) recentSongs.put(key, value);
        });
    }

    private record Pending(DriveService.EstructuraDomingo folder,
                           LocalDate sunday, List<AudioFile> songs, AudioFile selected,
                           Instant expires, String action) {
        Pending(DriveService.EstructuraDomingo folder, LocalDate sunday, List<AudioFile> songs,
                AudioFile selected, Instant expires) {
            this(folder, sunday, songs, selected, expires, "tone");
        }
    }

    private record RecentSong(String id, LocalDate sunday, Instant expires) {}
    private final Map<String, RecentSong> recentSongs = new ConcurrentHashMap<>();
    private record History(List<String> messages, Instant expires) {}
    private final Map<String, History> histories = new ConcurrentHashMap<>();

    private List<String> history(String from) {
        History history = histories.get(from);
        if (history == null || history.expires().isBefore(Instant.now())) return List.of();
        return history.messages();
    }

    private void rememberMessage(String from, String message) {
        histories.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        var messages = new java.util.ArrayList<>(history(from));
        messages.add(message.substring(0, Math.min(message.length(), 1500)));
        if (messages.size() > 6) messages.remove(0);
        histories.put(from, new History(List.copyOf(messages), Instant.now().plusSeconds(1800)));
    }

    public void rememberSong(String from, String id) {
        recentSongs.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        if (id == null) recentSongs.remove(from);
        else recentSongs.put(from, new RecentSong(id, Fechas.proximoDomingo(), Instant.now().plusSeconds(1800)));
        if (contextStore != null) contextStore.save("recentSongs", recentSongs);
    }

    public void rememberUpload(String from, String id) {
        pending.remove(from);
        confirmations.remove(from);
        rememberSong(from, id);
    }

    private int recentSelection(String from, List<AudioFile> songs) {
        RecentSong recent = recentSongs.get(from);
        if (recent == null) return 0;
        if (recent.expires().isBefore(Instant.now()) || !recent.sunday().equals(Fechas.proximoDomingo())) {
            recentSongs.remove(from, recent);
            return 0;
        }
        for (int i = 0; i < songs.size(); i++) if (songs.get(i).id().equals(recent.id())) return i + 1;
        return 0;
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private record Confirmation(String token, String summary, Instant expires, LocalDate sunday, Runnable execute) {}
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();
    private record Undo(String summary, Instant expires, LocalDate sunday, Runnable action) {}
    private final Map<String, Undo> undo = new ConcurrentHashMap<>();

    private void rememberUndo(String from, String summary, Runnable action) {
        undo.put(from, new Undo(summary, Instant.now().plusSeconds(1800), Fechas.proximoDomingo(), action));
    }

    private void askUndo(String from) {
        Undo last = undo.get(from);
        if (last == null || last.expires().isBefore(Instant.now()) || !last.sunday().equals(Fechas.proximoDomingo())) {
            whatsApp.replyText(from, "No tengo una operacion reciente que pueda deshacer con seguridad. No hice cambios.");
            return;
        }
        askConfirmation(from, "¿Deshago la ultima operacion? " + last.summary(), () -> {
            if (!undo.remove(from, last)) { whatsApp.replyText(from, "La ultima operacion cambio; pide deshacer de nuevo."); return; }
            last.action().run();
        });
    }

    boolean isConfirmation(String body) {
        String text = normalize(body);
        return text.equals("confirmar") || text.startsWith("confirm:") || text.startsWith("cancel:");
    }

    private void askConfirmation(String from, String summary, Runnable action) {
        String token = java.util.UUID.randomUUID().toString();
        confirmations.put(from, new Confirmation(token, summary, Instant.now().plusSeconds(300), Fechas.proximoDomingo(), action));
        whatsApp.confirmButtons(from, summary, token);
    }

    private boolean handleConfirmation(String from, String body) {
        if (!isConfirmation(body) && !body.startsWith("cancel:")) return false;
        Confirmation choice = confirmations.get(from);
        if (choice == null || choice.expires().isBefore(Instant.now()) || !choice.sunday().equals(Fechas.proximoDomingo())) {
            if (choice != null) confirmations.remove(from, choice);
            whatsApp.replyText(from, "No hay una confirmacion vigente. Pideme de nuevo lo que quieres hacer.");
            return true;
        }
        if (body.contains(":") && !body.substring(body.indexOf(':') + 1).equals(choice.token())) {
            whatsApp.replyText(from, "Ese boton pertenece a una pregunta anterior; usa la confirmacion mas reciente.");
            return true;
        }
        if (!confirmations.remove(from, choice)) return true;
        if (body.startsWith("cancel:")) whatsApp.replyText(from, "Cancelado; no hice cambios.");
        else choice.execute().run();
        return true;
    }

    public boolean accepts(String body) {
        if (body == null) return false;
        String text = normalize(body);
        return text.equals("cambiar tonalidad") || text.equals("bajar tono") || text.equals("subir tono")
                || isConfirmation(text) || text.equals("deshacer")
                || text.equals("todas") || text.equals("cancelar")
                || SONGS.matcher(text).matches() || AMOUNT.matcher(text).matches()
                || ADJUSTMENT.matcher(text).matches() || text.equals("subir") || text.equals("bajar");
    }

    public void handle(String from, String body) {
        String text = normalize(body);
        pending.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        try {
            if (handleConfirmation(from, text)) return;
            confirmations.remove(from);
            if (text.equals("deshacer")) { askUndo(from); return; }
            if (text.equals("cambiar tonalidad") || text.equals("bajar tono") || text.equals("subir tono")) {
                start(from);
                return;
            }
            if (text.equals("cancelar")) {
                confirmations.remove(from);
                pending.remove(from);
                histories.remove(from);
                pipeline.cancelNoteChoice(from);
                whatsApp.replyText(from, "Solicitud pendiente cancelada. Si una operacion ya comenzo, terminara de procesarse.");
                return;
            }
            Pending choice = pending.get(from);
            if (choice == null || !choice.sunday().equals(Fechas.proximoDomingo())) {
                if (choice != null) pending.remove(from, choice);
                whatsApp.replyText(from, "Escribe \"cambiar tonalidad\" para comenzar.");
                return;
            }
            if (choice.action().equals("clarify")) { handleNatural(from, body); return; }
            if (choice.selected() == null) {
                AudioFile selected = select(text, choice.songs());
                Pending next = new Pending(choice.folder(), choice.sunday(),
                        choice.songs(), selected, choice.expires(), choice.action());
                if (pending.replace(from, choice, next)) {
                    rememberSong(from, selected.id());
                    if (next.action().equals("remove")) confirmRemoval(from, next);
                    else askAmount(from, next);
                }
                return;
            }
            var match = ADJUSTMENT.matcher(text);
            if (!match.matches()) {
                if (naturalLanguageEnabled()) handleNatural(from, body);
                else askAmount(from, choice);
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(match.group(2));
            } catch (NumberFormatException e) {
                amount = 0;
            }
            if (amount < 1 || amount > 12) {
                whatsApp.replyText(from, "Escribe subir o bajar y un numero del 1 al 12. Por ejemplo: subir 1 o bajar 2.");
                return;
            }
            int semitones = amount * (match.group(1).equals("bajar") ? -1 : 1);
            apply(from, choice, semitones);
        } catch (IllegalArgumentException e) {
            if (naturalLanguageEnabled()) handleNatural(from, body);
            else whatsApp.replyText(from, "Elige una sola cancion de la lista, por ejemplo: cancion 1. Cada cancion lleva su propio ajuste.");
        } catch (Exception e) {
            LOG.error("Error en seleccion de tono", e);
            whatsApp.replyText(from, "No pude preparar el cambio de tono. Vuelve a escribir cambiar tonalidad.");
        }
    }

    private void apply(String from, Pending choice, int semitones) {
        // Consume exactamente la seleccion interpretada; nunca una nueva o cancelada.
        if (!pending.remove(from, choice)) return;
        whatsApp.replyText(from, "Ajustando " + choice.selected().name() + ", te aviso...");
        var result = new StringBuilder("• ")
                .append(replaceAudio(from, choice.selected(), choice.folder().playlistId(), semitones));
        result.append("\n\n").append(choice.folder().link());
        result.append("\n\nPara ajustar otra cancion, escribe cambiar tonalidad.");
        whatsApp.replyText(from, result.toString());
    }

    public boolean naturalLanguageEnabled() {
        return interpreter.available();
    }

    /** Usa la misma seleccion y las mismas validaciones de archivos del flujo guiado. */
    public void handleNatural(String from, String body) {
        handleNatural(from, body, null);
    }

    public void handleAfterUpload(String from, String instructions, List<String> uploadedIds) {
        handleNatural(from, instructions, uploadedIds);
    }

    private void handleNatural(String from, String body, List<String> uploadedIds) {
        if (!naturalLanguageEnabled()) return;
        try {
            Pending previous = pending.get(from);
            if (previous != null && (previous.expires().isBefore(Instant.now())
                    || !previous.sunday().equals(Fechas.proximoDomingo()))) {
                pending.remove(from, previous);
                previous = null;
            }
            LocalDate sunday = previous == null ? Fechas.proximoDomingo() : previous.sunday();
            var folder = previous == null ? drive.ensureSundayStructure(sunday) : previous.folder();
            List<AudioFile> songs = drive.listAudioFiles(folder.playlistId());
            if (uploadedIds != null) {
                // "La segunda" en un mensaje con links sigue el orden de esos links.
                Map<String, AudioFile> byId = songs.stream().collect(java.util.stream.Collectors.toMap(AudioFile::id, song -> song));
                songs = uploadedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
            }
            if (previous == null) {
                previous = new Pending(folder, sunday, songs, null, Instant.now().plusSeconds(1800));
                if (pending.putIfAbsent(from, previous) != null) return;
            }
            int selected = previous.selected() == null ? recentSelection(from, songs) : songs.indexOf(previous.selected()) + 1;
            var noteChoice = uploadedIds == null ? pipeline.pendingNoteChoice(from) : null;
            var names = songs.stream().map(AudioFile::name).toList();
            var result = interpreter.interpret(body, names, selected, history(from),
                    noteChoice != null ? "note_version" : uploadedIds == null ? previous.action() : "after_upload",
                    noteChoice, pipeline.context(from, names));
            if (List.of("status", "status_notes", "status_lyrics").contains(result.intent())) {
                // Consulta sin efectos sobre archivos ni preguntas pendientes. No espera al indice de letras.
                rememberMessage(from, body);
                if (result.song() > 0) rememberSong(from, songs.get(result.song() - 1).id());
                if (!result.intent().equals("status_lyrics")) pipeline.verifyNotes(
                        result.song() == 0 ? names : List.of(names.get(result.song() - 1)), folder.notasId());
                whatsApp.replyText(from, MusicWorkState.describe(pipeline.context(from, names), result.intent(), result.song()));
                return;
            }
            // Una respuesta tardia de la IA no debe sobreescribir un menu nuevo o cancelado.
            if (pending.get(from) != previous) return;
            if (noteChoice != null && pipeline.pendingNoteChoice(from) != noteChoice) return;
            rememberMessage(from, body);
            confirmations.remove(from); // Una correccion invalida la propuesta anterior.
            if (result.intent().equals("draft_notes")) {
                if (!pending.remove(from, previous)) return;
                var song = songs.get(result.song() - 1);
                rememberSong(from, song.id());
                pipeline.requestNoteDraft(from, song.name(), result.targetKey());
                return;
            }
            if (List.of("remove", "remove_notes").contains(result.intent()) && ambiguousNamedTarget(body, songs)) {
                pending.replace(from, previous, new Pending(folder, sunday, songs, null, Instant.now().plusSeconds(1800), "clarify"));
                var menu = new StringBuilder("Hay varias canciones que coinciden. ¿Cual quieres y te refieres al audio o a las notas?\n");
                for (int i = 0; i < songs.size(); i++) menu.append(i + 1).append(". ").append(songs.get(i).name()).append('\n');
                whatsApp.replyText(from, menu.toString());
                return;
            }
            if (result.intent().equals("undo")) { askUndo(from); return; }
            if (result.intent().equals("tone_batch")) {
                if (!pending.remove(from, previous)) return;
                var snapshot = List.copyOf(songs);
                var summary = new StringBuilder("Aplicare estos cambios sobre el tono actual:\n");
                for (var adjustment : result.adjustments()) summary.append("• ").append(snapshot.get(adjustment.song() - 1).name())
                        .append(": ").append(adjustment.semitones() > 0 ? "subir " : "bajar ")
                        .append(Math.abs(adjustment.semitones())).append(" semitonos\n");
                askConfirmation(from, summary.toString(), () -> {
                    whatsApp.replyText(from, "Aplicando los cambios confirmados; te avisare del resultado de cada cancion.");
                    var reply = new StringBuilder();
                    var undoSteps = new java.util.ArrayList<Undo>();
                    for (var adjustment : result.adjustments()) {
                        var before = undo.get(from);
                        reply.append("• ").append(replaceAudio(from, snapshot.get(adjustment.song() - 1), folder.playlistId(), adjustment.semitones())).append('\n');
                        var after = undo.get(from);
                        if (after != null && after != before) undoSteps.add(after);
                    }
                    if (!undoSteps.isEmpty()) rememberUndo(from, "Recuperar los audios anteriores del ultimo lote (" + undoSteps.size() + ").", () -> {
                        for (int i = undoSteps.size() - 1; i >= 0; i--) undoSteps.get(i).action().run();
                    });
                    whatsApp.replyText(from, reply.toString());
                });
                return;
            }
            if (result.intent().equals("remove_notes")) {
                if (!pending.remove(from, previous)) return;
                var song = songs.get(result.song() - 1);
                var copies = pipeline.workState.copies(song.name());
                if (copies.isEmpty()) { removeNotes(from, song, folder.notasId()); return; }
                askConfirmation(from, "¿Borro solo las notas de " + song.name() + "?\n"
                        + copies.stream().map(MusicWorkState.NoteCopy::name).collect(java.util.stream.Collectors.joining("\n"))
                        + "\nEl audio y el historico se conservan.",
                        () -> removeNotesSnapshot(from, song, folder.notasId(), copies));
                return;
            }
            if (result.intent().equals("note_version")) {
                if (!pending.remove(from, previous)) return;
                pipeline.chooseNoteVersion(from, noteChoice, result.song());
                return;
            }
            if (noteChoice != null && result.intent().equals("clarify")
                    && !normalize(body).matches(".*\\b(borra|borrar|elimina|eliminar|quita|quitar)\\b.*")) {
                pending.remove(from, previous);
                pipeline.repeatNoteQuestion(from);
                return;
            }
            if (result.intent().equals("cancel")) {
                handle(from, "cancelar");
                return;
            }
            if (result.intent().equals("list")) {
                if (!pending.remove(from, previous)) return;
                // Mostrar estado actual aunque la conversacion anterior tuviera un menu viejo.
                List<AudioFile> current = drive.listAudioFiles(folder.playlistId());
                var list = new StringBuilder("Canciones en la playlist:\n");
                for (int i = 0; i < current.size(); i++) list.append(i + 1).append(". ").append(current.get(i).name()).append('\n');
                list.append('\n').append(folder.link());
                whatsApp.replyText(from, list.toString());
                return;
            }
            if (result.intent().equals("notes_lyrics")) {
                if (!pending.remove(from, previous)) return;
                whatsApp.replyText(from, "Voy a buscar las notas y preparar el documento de letras de las canciones de la playlist.");
                // Cada flujo informa su resultado y maneja sus errores de forma independiente.
                pipeline.requestDocuments(from, true, true, null);
                return;
            }
            if (result.intent().equals("lyrics")) {
                if (!pending.remove(from, previous)) return;
                pipeline.requestDocuments(from, false, true, null);
                return;
            }
            if (result.intent().equals("lyrics_song")) {
                if (!pending.remove(from, previous)) return;
                var song = songs.get(result.song() - 1);
                rememberSong(from, song.id());
                whatsApp.replyText(from, "Voy a agregar al documento de letras: " + song.name() + ". Conservare el contenido que ya tiene.");
                pipeline.requestDocuments(from, false, true, song.name());
                return;
            }
            if (result.intent().equals("unrelated")) {
                if (uploadedIds != null) {
                    pending.remove(from, previous);
                    return; // Solo pedia subir los links; esa parte ya termino.
                }
                whatsApp.replyText(from, "Puedo mostrar la playlist, buscar notas, armar letras, cambiar el tono o quitar una cancion. Dime que necesitas; para agregar canciones, envia sus links de YouTube.");
                return;
            }
            if (result.intent().equals("notes")) {
                if (!pending.remove(from, previous)) return;
                pipeline.requestDocuments(from, true, false, result.song() == 0 ? null : songs.get(result.song() - 1).name());
                return;
            }
            if (result.intent().equals("clarify")) {
                pending.replace(from, previous, new Pending(folder, sunday, songs, null, Instant.now().plusSeconds(1800), "clarify"));
                var menu = new StringBuilder("¿A cual cancion o tarea te refieres?\n");
                for (int i = 0; i < songs.size(); i++) menu.append(i + 1).append(". ").append(songs.get(i).name()).append('\n');
                menu.append("Dime el titulo o numero y si hablas del audio, las notas o las letras.");
                whatsApp.replyText(from, menu.toString());
                return;
            }
            if (result.intent().equals("remove")) {
                // Defensa independiente del modelo: documentos nunca se enrutan a borrar audio.
                if (normalize(body).matches("(?s).*\\b(notas?|acordes?|acordeorios?|partituras?|letras?|docx?|documentos?|pdf)\\b.*")) {
                    whatsApp.replyText(from, "No borre el audio. ¿Quieres eliminar las notas o el documento de letras, y de que cancion?");
                    return;
                }
                Pending removal = new Pending(folder, sunday, songs,
                        result.song() == 0 ? null : songs.get(result.song() - 1),
                        Instant.now().plusSeconds(1800), "remove");
                if (!pending.replace(from, previous, removal)) return;
                if (removal.selected() != null) confirmRemoval(from, removal);
                else {
                    var menu = new StringBuilder("¿Que cancion quieres quitar de la playlist?\n");
                    for (int i = 0; i < songs.size(); i++) menu.append(i + 1).append(". ").append(songs.get(i).name()).append('\n');
                    menu.append("Responde cancion 1, cancion 2, etc. La mandare a la papelera. Para salir, escribe cancelar.");
                    whatsApp.replyText(from, menu.toString());
                }
                return;
            }
            if (result.intent().equals("tone_notes")) {
                // Cumplir las dos peticiones; las notas pueden requerir elegir version por separado.
                Pending claimed = new Pending(folder, sunday, songs, previous.selected(),
                        Instant.now().plusSeconds(1800));
                if (!pending.replace(from, previous, claimed)) return;
                previous = claimed;
                pipeline.generarNotas(from, null);
                if (pending.get(from) != previous) return;
            }
            if (!List.of("tone", "tone_notes").contains(result.intent()) || result.song() == 0) {
                whatsApp.replyText(from, "Necesito identificar una sola cancion y su ajuste. Elige una de la lista y luego indica subir o bajar y los semitonos.");
                start(from);
                return;
            }
            Pending choice = new Pending(folder, sunday, songs, songs.get(result.song() - 1),
                    Instant.now().plusSeconds(1800));
            boolean installed = pending.replace(from, previous, choice);
            if (!installed) return;
            rememberSong(from, choice.selected().id());
            if (result.semitones() == 0) {
                askAmount(from, choice);
                return;
            }
            apply(from, choice, result.semitones());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // No registrar texto, respuesta del proveedor, credenciales ni datos del usuario.
            String code = e instanceof GeminiSongInterpreter.Failure failure
                    ? failure.code() : e.getClass().getSimpleName();
            LOG.warnf("No se pudo interpretar con Gemini (%s)", code);
            String message = e instanceof GeminiSongInterpreter.Failure failure ? failure.userMessage()
                    : e instanceof java.net.http.HttpTimeoutException
                    ? "Gemini tardo demasiado en responder. Intentalo de nuevo en unos momentos."
                    : "No pude interpretar el mensaje por un fallo del servicio. Intentalo de nuevo en unos momentos.";
            whatsApp.replyText(from, message);
        }
    }

    private synchronized void removeNotes(String from, AudioFile song, String notesFolder) {
        var copies = pipeline.workState.copies(song.name());
        if (copies.isEmpty()) {
            whatsApp.replyText(from, "No tengo identificadas las copias de notas de " + song.name()
                    + ". No borre ningun archivo. Necesito identificar la copia exacta en Notas/.");
            return;
        }
        removeNotesSnapshot(from, song, notesFolder, copies);
    }

    private synchronized void removeNotesSnapshot(String from, AudioFile song, String notesFolder, List<MusicWorkState.NoteCopy> copies) {
        try {
            var snapshots = new java.util.ArrayList<DriveService.TrashedFile>();
            for (var copy : copies) {
                if (!copy.folder().equals(notesFolder)) throw new IllegalStateException("Carpeta de notas distinta");
                drive.trashNoteCopy(copy.id(), notesFolder);
                pipeline.workState.removedCopy(song.name(), copy);
                try {
                    snapshots.add(drive.trashedSnapshot(copy.id(), notesFolder));
                    var restore = List.copyOf(snapshots);
                    rememberUndo(from, "Recuperar las notas de " + song.name(), () -> {
                        try {
                            for (var file : restore) {
                                drive.restoreTrashed(file);
                                pipeline.workState.copied(song.name(), file.id(), file.name(), file.folder());
                            }
                            pipeline.workState.note(song.name(), "Notas recuperadas de la papelera");
                            whatsApp.replyText(from, "Notas recuperadas. El audio no se modifico.");
                        } catch (Exception e) { whatsApp.replyText(from, "No pude recuperar todas las notas. Revisa Notas/ y la papelera."); }
                    });
                } catch (Exception e) { undo.remove(from); }
            }
            rememberSong(from, song.id());
            whatsApp.replyText(from, "Notas de " + song.name() + " enviadas a la papelera. El audio sigue en la playlist y el historico no se modifico.");
        } catch (Exception e) {
            whatsApp.replyText(from, "No pude completar la eliminacion de notas. El audio no se modifico; revisa Notas/ antes de reintentar.");
        }
    }

    /** Quita solo el ID elegido, nunca variantes por nombre ni otros archivos. */
    private void confirmRemoval(String from, Pending choice) {
        askConfirmation(from, "¿Quito el AUDIO de " + choice.selected().name()
                + " de la playlist? Las notas y letras se conservan.", () -> removeSelected(from, choice));
    }

    private synchronized void removeSelected(String from, Pending choice) {
        if (!pending.remove(from, choice)) return;
        try {
            if (!drive.audioUnchanged(choice.selected(), choice.folder().playlistId())) {
                whatsApp.replyText(from, "Esa cancion cambio o ya no esta en la playlist. Dime de nuevo cual quieres quitar.");
                return;
            }
            drive.trashFile(choice.selected().id());
            try {
                var removed = drive.trashedSnapshot(choice.selected().id(), choice.folder().playlistId());
                rememberUndo(from, "Recuperar el audio " + choice.selected().name(), () -> {
                    try {
                        drive.restoreTrashed(removed);
                        rememberSong(from, removed.id());
                        whatsApp.replyText(from, "Audio recuperado en la playlist: " + removed.name());
                    } catch (Exception e) { whatsApp.replyText(from, "No pude recuperar ese audio; cambio o ya no esta en la papelera."); }
                });
            } catch (Exception e) { undo.remove(from); }
            RecentSong recent = recentSongs.get(from);
            if (recent != null && recent.id().equals(choice.selected().id())) recentSongs.remove(from, recent);
            whatsApp.replyText(from, "Quite de la playlist: " + choice.selected().name()
                    + ". Esta en la papelera de Drive y se puede recuperar.");
        } catch (Exception e) {
            LOG.warn("No se pudo quitar la cancion de la playlist", e);
            whatsApp.replyText(from, "No pude quitar esa cancion. Revisa la playlist antes de intentarlo de nuevo.");
        }
    }

    private void start(String from) throws Exception {
        pending.remove(from);
        LocalDate sunday = Fechas.proximoDomingo();
        var folder = drive.ensureSundayStructure(sunday);
        List<AudioFile> songs = drive.listAudioFiles(folder.playlistId());
        if (songs.isEmpty()) {
            whatsApp.replyText(from, "Aun no hay canciones en la playlist. Envia primero sus links de YouTube.");
            return;
        }
        Pending choice = new Pending(folder, sunday, songs,
                songs.size() == 1 ? songs.get(0) : null, Instant.now().plusSeconds(1800));
        pending.put(from, choice);
        if (songs.size() == 1) {
            askAmount(from, choice);
            return;
        }
        var menu = new StringBuilder("¿A que cancion quieres cambiar la tonalidad?\n");
        for (int i = 0; i < songs.size(); i++) {
            menu.append(i + 1).append(". ").append(songs.get(i).name()).append('\n');
        }
        menu.append("\nResponde cancion 1, cancion 2, etc. Para salir, escribe cancelar.");
        whatsApp.replyText(from, menu.toString());
    }

    private void askAmount(String from, Pending choice) {
        var message = new StringBuilder("Cancion seleccionada:\n• ")
                .append(choice.selected().name()).append('\n');
        message.append("\n¿Que quieres hacer con esta cancion?\n")
                .append("Escribe subir 1 para subir un semitono o bajar 2 para bajar dos semitonos (de 1 a 12).\n")
                .append("Se aplica sobre el tono actual de esta cancion. Para salir, escribe cancelar.");
        whatsApp.replyText(from, message.toString());
    }

    static AudioFile select(String text, List<AudioFile> songs) {
        if (!SONGS.matcher(text).matches()) throw new IllegalArgumentException();
        String[] parts = text.split("\\s+");
        if (parts.length != 2) throw new IllegalArgumentException();
        int index = Integer.parseInt(parts[1]);
        if (index < 1 || index > songs.size()) throw new IllegalArgumentException();
        return songs.get(index - 1);
    }

    /** Serializa reemplazos locales; otro menu que apunte al archivo viejo se rechaza. */
    private synchronized String replaceAudio(String from, AudioFile song, String folderId, int semitones) {
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
            var uploaded = drive.uploadNewAudio(shifted, name, folderId);
            uploadedName = name;
            rememberSong(from, uploaded.getId());
            drive.trashFile(song.id());
            try {
                var original = drive.trashedSnapshot(song.id(), folderId);
                var replacement = drive.listAudioFiles(folderId).stream().filter(file -> file.id().equals(uploaded.getId())).findFirst().orElseThrow();
                rememberUndo(from, "Recuperar el tono anterior de " + song.name(), () -> {
                    try {
                        if (!drive.audioUnchanged(replacement, folderId)) throw new IllegalStateException("El audio cambio");
                        drive.restoreTrashed(original);
                        drive.trashFile(replacement.id());
                        rememberSong(from, original.id());
                        whatsApp.replyText(from, "Recupere el audio anterior: " + original.name());
                    } catch (Exception e) { whatsApp.replyText(from, "No pude completar deshacer. Revisa la playlist; podria contener ambas versiones."); }
                });
            } catch (Exception e) { undo.remove(from); }
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

    static boolean ambiguousNamedTarget(String body, List<AudioFile> songs) {
        var match = Pattern.compile("(?i).*\\b(?:de|por)\\s+([\\p{L}\\p{N} ]+)[?.!]*$").matcher(normalize(body));
        if (!match.matches()) return false;
        String query = match.group(1).strip();
        return songs.stream().filter(song -> normalize(song.name().replace('_', ' ')).contains(query)).count() > 1;
    }
}
