package mx.salvador.wabot.media;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class YtDlpDownloader {

    private static final String P_FILE = "@@FILE::";
    private static final String P_TITLE = "@@TITLE::";

    private static final Pattern YT_URL = Pattern.compile(
            "https?://(?:www\\.|music\\.)?(?:youtube\\.com/(?:watch\\?\\S*v=|shorts/)|youtu\\.be/)[\\w\\-]{6,}\\S*");

    @ConfigProperty(name = "bot.tmp-dir", defaultValue = "/tmp/wa-drive-bot")
    String tmpDir;

    @ConfigProperty(name = "yt.cookies-file", defaultValue = "/etc/secrets/cookies.txt")
    String cookiesFile;

    @ConfigProperty(name = "yt.proxy-url", defaultValue = "")
    String proxyUrl;

    public record Descarga(Path archivo, String titulo) {
    }

    public List<String> extractYouTubeUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher m = YT_URL.matcher(text);
        return m.results().map(r -> r.group()).toList();
    }

    public Descarga downloadMp3(String url) throws IOException, InterruptedException {

        url = cleanUrl(url);

        Files.createDirectories(Path.of(tmpDir));

        var cmd = new ArrayList<String>(List.of(
                "yt-dlp",
                // Preferir el AAC/m4a nativo de YouTube: -x solo re-empaqueta el
                // contenedor (~1s). Fallback /best: si YouTube esconde los formatos
                // de solo-audio (experimento SABR), baja el video y extrae el audio.
                "-f", "bestaudio[ext=m4a]/bestaudio/best",
                "-x",
                "--audio-format", "m4a",
                "--no-playlist",
                "--restrict-filenames",

                // OJO: sin player_client forzado. Los defaults de yt-dlp se curan en
                // cada release para esquivar experimentos de YouTube (p. ej. SABR);
                // fijar "android" nos dejo sin formatos. Mantener yt-dlp actualizado.

                // Velocidad: baja fragmentos DASH/HLS en paralelo (clave con proxy residencial)
                "--concurrent-fragments", "8",
                // Resiliencia: los proxies residenciales cortan conexiones seguido
                "--retries", "10",
                "--fragment-retries", "10",
                "--socket-timeout", "20",

                // Progreso por linea: lo usamos para cronometrar fases (el parseo
                // del resultado es por prefijo, asi que no estorba)
                "--progress",
                "--newline",

                "-o", tmpDir + "/%(title)s.%(ext)s",
                // Prefijos únicos: el orden/contenido de stdout NO es confiable
                // (yt-dlp puede intercalar lineas [download], warnings, etc.)
                "--print", "after_move:" + P_FILE + "%(filepath)s",
                "--print", "after_move:" + P_TITLE + "%(title)s",
                "--no-simulate"
        ));

        String writableCookies = resolveWritableCookies();

        if (writableCookies != null) {
            cmd.add("--cookies");
            cmd.add(writableCookies);
        }

        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }

        cmd.add("--no-quiet"); // --print activa modo quiet; lo revertimos para ver los marcadores de fase
        cmd.add(url);

        Log.infof("Iniciando descarga: %s", url);
        Log.debugf("Comando: %s", cmd);

        Instant inicio = Instant.now();

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();
        var fases = new PhaseClock();

        Thread outT = new Thread(() -> drainWithPhases(p.getInputStream(), stdoutBuf, fases));
        Thread errT = new Thread(() -> drain(p.getErrorStream(), stderrBuf));

        outT.start();
        errT.start();

        boolean finished = p.waitFor(10, TimeUnit.MINUTES);

        outT.join(5000);
        errT.join(5000);

        Instant fin = Instant.now();

        long tiempoMs = Duration.between(inicio, fin).toMillis();

        if (!finished) {
            p.destroyForcibly();
            throw new IOException("yt-dlp timeout con " + url);
        }

        String stdout = stdoutBuf.toString();
        String stderr = stderrBuf.toString();

        Log.debug("--- stdout ---\n" + stdout);
        Log.debug("--- stderr ---\n" + stderr);

        if (p.exitValue() != 0) {
            throw new IOException("yt-dlp falló (" + p.exitValue() + ")\n\nSTDERR:\n" + stderr);        }

        String filepath = null;
        String titulo = null;

        for (String line : stdout.split("\n")) {
            String l = line.strip();
            if (l.startsWith(P_FILE)) {
                filepath = l.substring(P_FILE.length()).strip();
            } else if (l.startsWith(P_TITLE)) {
                titulo = l.substring(P_TITLE.length()).strip();
            }
        }

        if (filepath == null || filepath.isBlank()) {
            throw new IOException("yt-dlp no regresó filepath.\nSTDOUT:\n" + stdout
                    + "\nSTDERR:\n" + lastLine(stderr));
        }
        if (titulo == null || titulo.isBlank()) {
            titulo = Path.of(filepath).getFileName().toString();
        }

        Path archivo = Path.of(filepath);

        if (!Files.exists(archivo)) {
            throw new IOException("yt-dlp reportó el archivo pero no existe: " + archivo
                    + "\nSTDERR:\n" + lastLine(stderr));
        }

        long bytes = Files.size(archivo);

        double mb = bytes / 1024d / 1024d;
        double segundos = tiempoMs / 1000d;

        Log.info("==========================================");
        Log.info("Descarga finalizada");
        Log.infof("Título      : %s", titulo);
        Log.infof("Archivo     : %s", archivo);
        Log.infof("Tamaño      : %.2f MB", mb);
        Log.infof("Tiempo      : %.2f s", segundos);
        Log.infof("Desglose    : %s", fases.desglose(inicio, fin));
        Log.info("==========================================");

        return new Descarga(archivo, titulo);
    }

    private static String lastLine(String s) {
        String[] lines = s.strip().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    /**
     * Deja la URL en su forma minima (solo el video). Los parametros
     * &list=RD..., &start_radio, &index obligan a yt-dlp a resolver la
     * playlist/radio: peticiones extra que por el proxy cuestan segundos.
     */
    static String cleanUrl(String url) {
        Matcher m = Pattern.compile("(?:youtube\\.com/watch\\?\\S*?v=|youtu\\.be/|youtube\\.com/shorts/)([\\w\\-]{6,})")
                .matcher(url);
        if (m.find()) {
            return "https://www.youtube.com/watch?v=" + m.group(1);
        }
        return url;
    }

    /** Marca cuando arranca cada fase, segun los mensajes de yt-dlp en stdout. */
    private static final class PhaseClock {
        volatile Instant descargaInicio;   // primer "[download]"
        volatile Instant conversionInicio; // primer "[ExtractAudio]"

        void observar(String line) {
            if (descargaInicio == null && line.startsWith("[download]")) {
                descargaInicio = Instant.now();
            } else if (conversionInicio == null && line.startsWith("[ExtractAudio]")) {
                conversionInicio = Instant.now();
            }
        }

        String desglose(Instant inicio, Instant fin) {
            if (descargaInicio == null) return "sin datos de fases";
            double extraccion = ms(inicio, descargaInicio) / 1000d;
            if (conversionInicio == null) {
                return String.format("extraccion %.1fs | descarga+conversion %.1fs",
                        extraccion, ms(descargaInicio, fin) / 1000d);
            }
            return String.format("extraccion %.1fs | descarga %.1fs | conversion %.1fs",
                    extraccion,
                    ms(descargaInicio, conversionInicio) / 1000d,
                    ms(conversionInicio, fin) / 1000d);
        }

        private static long ms(Instant a, Instant b) {
            return Duration.between(a, b).toMillis();
        }
    }

    /** Lee stdout linea por linea: acumula el texto y cronometra las fases. */
    private static void drainWithPhases(java.io.InputStream in, StringBuilder out, PhaseClock fases) {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fases.observar(line);
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            out.append("[error leyendo stream: ").append(e.getMessage()).append("]");
        }
    }

    private static void drain(java.io.InputStream in, StringBuilder out) {
        try {
            out.append(new String(in.readAllBytes()));
        } catch (IOException e) {
            out.append("[error leyendo stream: ").append(e.getMessage()).append("]");
        }
    }

    private String resolveWritableCookies() {

        try {

            Path source = Path.of(cookiesFile);

            if (!Files.exists(source)) {
                Log.infof("No existe archivo de cookies: %s", cookiesFile);
                return null;
            }

            Path copy = Path.of(tmpDir, "cookies-writable.txt");

            if (!Files.exists(copy)) {
                Files.copy(source, copy);
                Log.infof("Cookies copiadas a %s", copy);
            }

            return copy.toString();

        } catch (IOException e) {

            Log.warnf("No se pudieron preparar las cookies: %s", e.getMessage());
            return null;

        }
    }
}