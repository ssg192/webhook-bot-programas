package mx.salvador.wabot.media;

import java.util.Map;
import java.util.regex.Pattern;

/** Transposicion determinista: raiz, calidad y bajo separado, sin cambiar el modo. */
public final class ChordTransposer {
    private static final Pattern CHORD = Pattern.compile("^([A-G](?:#|b)?)(m?(?:maj|dim|aug|sus|add)?[0-9]*(?:[#b][0-9]+)?)(?:/([A-G](?:#|b)?))?$");
    private static final Map<String, Integer> NOTES = Map.ofEntries(
            Map.entry("C", 0), Map.entry("B#", 0), Map.entry("C#", 1), Map.entry("Db", 1),
            Map.entry("D", 2), Map.entry("D#", 3), Map.entry("Eb", 3), Map.entry("E", 4), Map.entry("Fb", 4),
            Map.entry("F", 5), Map.entry("E#", 5), Map.entry("F#", 6), Map.entry("Gb", 6), Map.entry("G", 7),
            Map.entry("G#", 8), Map.entry("Ab", 8), Map.entry("A", 9), Map.entry("A#", 10), Map.entry("Bb", 10), Map.entry("B", 11), Map.entry("Cb", 11));
    private ChordTransposer() {}
    public static boolean validKey(String key) { return key != null && key.matches("[A-G][#b]?m?") && NOTES.containsKey(key.replace("m", "")); }
    public static boolean validChord(String chord) { return chord != null && CHORD.matcher(chord).matches(); }
    public static int distance(String from, String to) {
        if (!validKey(from) || !validKey(to) || from.endsWith("m") != to.endsWith("m"))
            throw new IllegalArgumentException("No se puede transponer sin tonalidad de origen o cambiando mayor/menor");
        return Math.floorMod(NOTES.get(to.replace("m", "")) - NOTES.get(from.replace("m", "")), 12);
    }
    public static String transpose(String chord, int semitones, boolean flats) {
        var match = CHORD.matcher(chord);
        if (!match.matches()) throw new IllegalArgumentException("Acorde no reconocido");
        return note(match.group(1), semitones, flats) + match.group(2)
                + (match.group(3) == null ? "" : "/" + note(match.group(3), semitones, flats));
    }
    private static String note(String note, int amount, boolean flats) {
        String[] names = (flats ? "C Db D Eb E F Gb G Ab A Bb B" : "C C# D D# E F F# G G# A A# B").split(" ");
        return names[Math.floorMod(NOTES.get(note) + amount, 12)];
    }
}
