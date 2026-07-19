package mx.salvador.wabot.media;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cambia el tono de un mp3 con ffmpeg sin alterar la velocidad.
 * Intenta primero el filtro rubberband (mejor calidad); si el ffmpeg
 * instalado no lo trae, cae al método asetrate+atempo.
 */
@ApplicationScoped
public class PitchShifter {

    /** Detecta "tono -2", "tono +1", "tono 3" en el texto del mensaje. */
    private static final Pattern TONO = Pattern.compile("(?i)\\btono\\s*([+-]?\\d{1,2})\\b");

    /** Regresa los semitonos pedidos en el texto, o 0 si no hay indicación válida. */
    public int parseSemitones(String text) {
        if (text == null) return 0;
        Matcher m = TONO.matcher(text);
        if (!m.find()) return 0;
        int st = Integer.parseInt(m.group(1));
        return (st >= -12 && st <= 12) ? st : 0;
    }

    /**
     * Genera un archivo nuevo con el tono cambiado: "Cancion (-2).mp3".
     * Bloqueante — llamar desde un hilo worker.
     */
    public Path shift(Path input, int semitones) throws IOException, InterruptedException {
        double factor = Math.pow(2, semitones / 12.0);
        String name = input.getFileName().toString();
        String base = name.endsWith(".mp3") ? name.substring(0, name.length() - 4) : name;
        String signed = (semitones > 0 ? "+" : "") + semitones;
        Path output = input.resolveSibling(base + " (" + signed + ").mp3");

        // Intento 1: rubberband (preserva mejor la voz)
        String rubberband = String.format("rubberband=pitch=%.6f", factor);
        if (runFfmpeg(input, output, rubberband)) {
            return output;
        }

        // Fallback: asetrate + aresample + atempo (ffmpeg sin librubberband)
        String fallback = String.format(
                "asetrate=44100*%.6f,aresample=44100,atempo=%.6f", factor, 1.0 / factor);
        if (runFfmpeg(input, output, fallback)) {
            return output;
        }

        throw new IOException("ffmpeg no pudo cambiar el tono (ni rubberband ni fallback)");
    }

    private boolean runFfmpeg(Path in, Path out, String audioFilter)
            throws IOException, InterruptedException {
        var cmd = List.of("ffmpeg", "-y", "-i", in.toString(),
                "-af", audioFilter, "-codec:a", "libmp3lame", "-q:a", "2",
                out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); // drenar para que no se bloquee
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg timeout cambiando tono de " + in);
        }
        return p.exitValue() == 0;
    }
}