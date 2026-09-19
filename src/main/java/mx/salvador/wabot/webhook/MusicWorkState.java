package mx.salvador.wabot.webhook;

import mx.salvador.wabot.media.TitleCleaner;
import mx.salvador.wabot.util.Fechas;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hechos observados, nunca inferidos por el modelo. Se reinician al cambiar de domingo. */
final class MusicWorkState {
    private LocalDate sunday = Fechas.proximoDomingo();
    private final Map<String, String> notes = new ConcurrentHashMap<>();
    private final Map<String, String> lyrics = new ConcurrentHashMap<>();
    private String document = "Sin estado confirmado en esta sesion";

    private void refresh() {
        var current = Fechas.proximoDomingo();
        if (!current.equals(sunday)) {
            notes.clear(); lyrics.clear();
            document = "Sin estado confirmado en esta sesion";
            sunday = current;
        }
    }

    static String key(String song) { return TitleCleaner.clean(song).nombre(); }
    synchronized void note(String song, String status) { refresh(); notes.put(key(song), status); }
    synchronized void lyric(String song, String status) { refresh(); lyrics.put(key(song), status); }
    synchronized void noteFailed(String song) {
        refresh();
        notes.replace(key(song), "Buscando notas en el historico", "Fallo la busqueda o copia; resultado sin confirmar");
    }
    synchronized void document(String status) {
        refresh(); document = status;
        if (status.startsWith("Fallo")) lyrics.replaceAll((song, value) -> value.equals("En preparacion")
                ? "No se confirmo su inclusion: fallo la preparacion" : value);
    }

    synchronized Map<String, Object> snapshot(List<String> songs, int downloads) {
        refresh();
        var items = songs.stream().map(song -> Map.of(
                "cancion", song,
                "notas", notes.getOrDefault(key(song), "No se ha confirmado la copia en esta sesion"),
                "letra", lyrics.getOrDefault(key(song), "No se ha confirmado su inclusion en esta sesion"))).toList();
        return Map.of("canciones", items, "documentoLetras", document,
                "descargasPendientes", downloads,
                "alcance", "Registro de esta sesion; un estado desconocido no significa que el archivo no exista en Drive");
    }

    @SuppressWarnings("unchecked")
    static String describe(Map<String, Object> state, String intent, int song) {
        var out = new StringBuilder();
        var items = (List<Map<String, String>>) state.get("canciones");
        for (int i = 0; i < items.size(); i++) {
            if (song != 0 && song != i + 1) continue;
            var item = items.get(i);
            out.append("• ").append(item.get("cancion")).append('\n');
            if (!intent.equals("status_lyrics")) out.append("Notas: ").append(item.get("notas")).append('\n');
            if (!intent.equals("status_notes")) out.append("Letra: ").append(item.get("letra")).append('\n');
        }
        out.append("\nDocumento de letras: ").append(state.get("documentoLetras"));
        out.append("\nDescargas pendientes: ").append(state.get("descargasPendientes"));
        if (state.containsKey("peticionesDocumentosEnEspera"))
            out.append("\nPeticiones de documentos esperando las descargas: ").append(state.get("peticionesDocumentosEnEspera"));
        return out.toString();
    }
}
