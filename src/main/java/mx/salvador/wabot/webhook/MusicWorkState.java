package mx.salvador.wabot.webhook;

import mx.salvador.wabot.media.TitleCleaner;
import mx.salvador.wabot.util.Fechas;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hechos observados, nunca inferidos por el modelo. Se reinician al cambiar de domingo. */
final class MusicWorkState {
    private ContextStore store;
    record Saved(LocalDate sunday, Map<String, String> notes, Map<String, String> lyrics,
                 Map<String, List<NoteCopy>> copies, String document, Map<String, String> references) {}
    synchronized void attach(ContextStore store) {
        this.store = store;
        if (store == null) return;
        Saved saved = store.read("music", new com.fasterxml.jackson.core.type.TypeReference<Saved>() {});
        if (saved != null && Fechas.proximoDomingo().equals(saved.sunday()) && saved.notes() != null
                && saved.lyrics() != null && saved.copies() != null && saved.document() != null) {
            notes.putAll(saved.notes()); lyrics.putAll(saved.lyrics()); copies.putAll(saved.copies());
            if (saved.references() != null) references.putAll(saved.references());
            document = saved.document();
            notes.replaceAll((song, status) -> status.startsWith("Buscando") ? "Busqueda interrumpida al reiniciar; pidemela otra vez" : status);
            lyrics.replaceAll((song, status) -> status.equals("En preparacion") ? "Preparacion interrumpida al reiniciar" : status);
            if (document.startsWith("En preparacion")) document = "Preparacion interrumpida al reiniciar; resultado sin confirmar";
        }
    }
    private void persist() {
        if (store != null) store.save("music", new Saved(sunday, Map.copyOf(notes), Map.copyOf(lyrics), Map.copyOf(copies), document, Map.copyOf(references)));
    }
    private LocalDate sunday = Fechas.proximoDomingo();
    private final Map<String, String> notes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> verifiedNotes = new ConcurrentHashMap<>();
    synchronized void verifiedNotes(String song, Boolean present) {
        refresh();
        if (present == null) verifiedNotes.remove(key(song)); else verifiedNotes.put(key(song), present);
    }
    private final Map<String, String> lyrics = new ConcurrentHashMap<>();
    record NoteCopy(String id, String name, String folder) {}
    private final Map<String, List<NoteCopy>> copies = new ConcurrentHashMap<>();
    private final Map<String, String> references = new ConcurrentHashMap<>();
    synchronized void reference(String song, String url) { refresh(); references.put(key(song), url); persist(); }
    synchronized String reference(String song) { refresh(); return references.getOrDefault(key(song), ""); }
    private String document = "Sin estado confirmado en esta sesion";

    private void refresh() {
        var current = Fechas.proximoDomingo();
        if (!current.equals(sunday)) {
            notes.clear(); lyrics.clear(); copies.clear(); references.clear(); verifiedNotes.clear();
            document = "Sin estado confirmado en esta sesion";
            sunday = current;
        }
    }

    static String key(String song) { return TitleCleaner.clean(song).nombre(); }
    synchronized void note(String song, String status) { refresh(); notes.put(key(song), status); persist(); }
    synchronized void copied(String song, String id, String name, String folder) {
        refresh();
        if (id == null) return;
        var list = new java.util.ArrayList<>(copies.getOrDefault(key(song), List.of()));
        list.removeIf(copy -> copy.id().equals(id));
        list.add(new NoteCopy(id, name, folder));
        copies.put(key(song), List.copyOf(list));
        verifiedNotes.remove(key(song));
        persist();
    }
    synchronized List<NoteCopy> copies(String song) { refresh(); return copies.getOrDefault(key(song), List.of()); }
    synchronized void removedCopy(String song, NoteCopy copy) {
        refresh();
        copies.computeIfPresent(key(song), (key, list) -> list.stream().filter(item -> !item.id().equals(copy.id())).toList());
        verifiedNotes.remove(key(song));
        notes.put(key(song), copies.getOrDefault(key(song), List.of()).isEmpty()
                ? "Copias de notas enviadas a la papelera; audio conservado" : "Eliminacion parcial de notas; quedan copias");
        persist();
    }
    synchronized void lyric(String song, String status) { refresh(); lyrics.put(key(song), status); persist(); }
    synchronized void noteFailed(String song) {
        refresh();
        notes.replace(key(song), "Buscando notas en el historico", "Fallo la busqueda o copia; resultado sin confirmar");
        persist();
    }
    synchronized void document(String status) {
        refresh(); document = status;
        if (status.startsWith("Fallo")) lyrics.replaceAll((song, value) -> value.equals("En preparacion")
                ? "No se confirmo su inclusion: fallo la preparacion" : value);
        persist();
    }

    synchronized Map<String, Object> snapshot(List<String> songs, int downloads) {
        refresh();
        var items = songs.stream().map(song -> Map.of(
                "cancion", song,
                "notasVerificadas", verifiedNotes.containsKey(key(song)) ? verifiedNotes.get(key(song)).toString() : "unknown",
                "notas", notes.getOrDefault(key(song), "No se ha confirmado la copia en esta sesion"),
                "letra", lyrics.getOrDefault(key(song), "No se ha confirmado su inclusion en esta sesion"))).toList();
        long confirmed = songs.stream().filter(song -> Boolean.TRUE.equals(verifiedNotes.get(key(song)))).count();
        long unknown = songs.stream().filter(song -> !verifiedNotes.containsKey(key(song))).count();
        return Map.of("canciones", items, "documentoLetras", document,
                "totalCanciones", songs.size(), "conNotasConfirmadas", confirmed, "notasSinVerificar", unknown,
                "descargasPendientes", downloads,
                "alcance", "Registro de esta sesion; un estado desconocido no significa que el archivo no exista en Drive");
    }

    @SuppressWarnings("unchecked")
    static String describe(Map<String, Object> state, String intent, int song) {
        var out = new StringBuilder();
        var items = (List<Map<String, String>>) state.get("canciones");
        if (song == 0 && !intent.equals("status_lyrics")) {
            long confirmed = items.stream().filter(item -> "true".equals(item.get("notasVerificadas"))).count();
            out.append("Llevamos ").append(items.size()).append(" canciones. ").append(confirmed)
                    .append(" tienen notas confirmadas en Drive.");
            if (confirmed == items.size() && !items.isEmpty()) out.append(" Todas tienen notas; no hace falta crear mas.");
            else out.append(" En ").append(items.size() - confirmed).append(" no hay notas confirmadas; puede haber archivos sin asociar o una consulta sin verificar.");
            out.append("\n\n");
        }
        for (int i = 0; i < items.size(); i++) {
            if (song != 0 && song != i + 1) continue;
            var item = items.get(i);
            out.append("• ").append(item.get("cancion")).append('\n');
            if (!intent.equals("status_lyrics")) out.append("Notas: ").append(item.get("notas")).append('\n');
            if (!intent.equals("status_notes")) out.append("Letra: ").append(item.get("letra")).append('\n');
        }
        if (song > 0) return out.toString().strip();
        if (state.get("driveActual") instanceof Map<?, ?> inventory) {
            if (inventory.get("notasSinAsociar") instanceof List<?> unassigned && !unassigned.isEmpty())
                out.append("\nArchivos de notas sin asociar: ").append(String.join(", ", unassigned.stream().map(Object::toString).toList())).append(". Dime a que cancion corresponden.\n");
            if (inventory.get("documentos") instanceof List<?> documents) {
                out.append("\nDocumentos actuales de la fecha: ");
                if (documents.isEmpty()) out.append("ninguno");
                else out.append(String.join(", ", documents.stream().map(item -> String.valueOf(((Map<?, ?>) item).get("nombre"))).toList()));
                out.append("\n");
            }
            if (inventory.containsKey("errorDocumentos")) out.append('\n').append(inventory.get("errorDocumentos")).append('\n');
        }
        out.append("\nDocumento de letras: ").append(state.get("documentoLetras"));
        out.append("\nDescargas pendientes: ").append(state.get("descargasPendientes"));
        if (state.get("peticionesDocumentosEnEspera") instanceof Number count && count.intValue() > 0)
            out.append("\nPeticiones de documentos esperando las descargas: ").append(state.get("peticionesDocumentosEnEspera"));
        return out.toString();
    }
}
