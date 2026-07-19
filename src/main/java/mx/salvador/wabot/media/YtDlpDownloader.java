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

        Files.createDirectories(Path.of(tmpDir));

        var cmd = new ArrayList<String>(List.of(
                "yt-dlp",
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "0",
                "--no-playlist",
                "--restrict-filenames",
                "--extractor-args", "youtube:player_client=mweb,web,android",
                "-o", tmpDir + "/%(title)s.%(ext)s",
                "--print", "after_move:filepath",
                "--print", "after_move:title",
                "--no-simulate",
                "--verbose"
        ));

        String writableCookies = resolveWritableCookies();

        if (writableCookies != null) {
            cmd.add("--cookies");
            cmd.add(writableCookies);
        }

        /*
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }

         */

        cmd.add(url);

        Log.infof("Iniciando descarga: %s", url);
        Log.debugf("Comando: %s", cmd);

        Instant inicio = Instant.now();

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();

        Thread outT = new Thread(() -> drain(p.getInputStream(), stdoutBuf));
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

        String[] lines = stdout.strip().split("\n");

        if (lines.length < 2) {
            throw new IOException("yt-dlp no regresó filepath/título: " + stdout);
        }

        Path archivo = Path.of(lines[0].strip());
        String titulo = lines[1].strip();

        long bytes = Files.size(archivo);

        double mb = bytes / 1024d / 1024d;
        double segundos = tiempoMs / 1000d;
        double velocidad = segundos > 0 ? mb / segundos : 0;

        Log.info("==========================================");
        Log.info("Descarga finalizada");
        Log.infof("Título      : %s", titulo);
        Log.infof("Archivo     : %s", archivo);
        Log.infof("Tamaño      : %.2f MB", mb);
        Log.infof("Tiempo      : %.2f s", segundos);
        Log.infof("Velocidad   : %.2f MB/s", velocidad);
        Log.info("==========================================");

        return new Descarga(archivo, titulo);
    }

    private static String lastLine(String s) {
        String[] lines = s.strip().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1];
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