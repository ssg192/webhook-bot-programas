package mx.salvador.wabot.media;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Limpia nombres de archivo/título de YouTube para usarlos en el doc de letras:
 */
public final class TitleCleaner {

    /** Palabras basura típicas de títulos de YouTube (se quitan del final). */
    private static final Set<String> JUNK = Set.of(
            "video", "oficial", "official", "videoclip", "clip", "mv",
            "lyric", "lyrics", "letra", "letras", "audio", "visualizer",
            "vivo", "live", "hd", "4k", "full", "remaster", "remasterizado",
            "cover", "version", "versión", "completo", "completa", "musica", "música");

    private static final Pattern TONE_SUFFIX = Pattern.compile("\\s*\\(([+-]\\d{1,2})\\)\\s*$");

    public record Titulo(String nombre, String tono) {
        public String display() {
            return tono == null ? nombre : nombre + " (" + tono + ")";
        }
    }

    private TitleCleaner() {}

    public static Titulo clean(String fileName) {
        String s = fileName;
        String lower = s.toLowerCase();
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a")) s = s.substring(0, s.length() - 4);

        // Extraer sufijo de tono "(-2)" si existe (lo agregamos nosotros, se preserva)
        String tono = null;
        Matcher m = TONE_SUFFIX.matcher(s);
        if (m.find()) {
            tono = m.group(1);
            s = s.substring(0, m.start());
        }

        // restrict-filenames convierte espacios/símbolos en _ ; revertir
        s = s.replace('_', ' ').replaceAll("\\s+", " ").strip();

        // Quitar tokens basura del final, iterativamente:
        // "... - Lloviendo Estrellas Cover Audio" -> "... - Lloviendo Estrellas"
        String[] tokens = s.split(" ");
        int end = tokens.length;
        while (end > 1 && JUNK.contains(tokens[end - 1].toLowerCase())) {
            end--;
        }
        s = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, end));

        // Limpieza final: guiones sueltos al final, espacios
        s = s.replaceAll("[\\s\\-|]+$", "").strip();

        return new Titulo(s.isBlank() ? fileName : s, tono);
    }
}