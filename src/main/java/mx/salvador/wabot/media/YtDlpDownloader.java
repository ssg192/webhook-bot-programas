package mx.salvador.wabot.media;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // En Render: Secret File llamado "cookies.txt" (se monta en /etc/secrets/cookies.txt).
    // En local, si no pones esta variable o el archivo no existe, simplemente se omite.
    @ConfigProperty(name = "yt.cookies-file", defaultValue = "/etc/secrets/cookies.txt")
    String cookiesFile;

    // Proxy residencial opcional, ej: http://usuario:password@proxy.iproyal.com:12321
    // Si no se define esta variable, yt-dlp corre sin proxy (comportamiento normal).
    @ConfigProperty(name = "yt.proxy-url", defaultValue = "")
    String proxyUrl;

    public record Descarga(Path archivo, String titulo) {}

    /** Extrae todas las URLs de YouTube de un texto. */
    public List<String> extractYouTubeUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher m = YT_URL.matcher(text);
        return m.results().map(r -> r.group()).toList();
    }

    /**
     * Descarga y convierte a mp3. Bloqueante — llamar desde un hilo worker.
     * yt-dlp imprime (en este orden por los --print): filepath final y título.
     */
    public Descarga downloadMp3(String url) throws IOException, InterruptedException {
        Files.createDirectories(Path.of(tmpDir));

        var cmd = new ArrayList<String>(List.of(
                "yt-dlp",
                "-x", "--audio-format", "mp3", "--audio-quality", "0",
                "--no-playlist",
                "--restrict-filenames",
                "--extractor-args", "youtube:player_client=mweb,web,android",
                "-o", tmpDir + "/%(title)s.%(ext)s",
                "--print", "after_move:filepath",
                "--print", "after_move:title",
                "--no-simulate",
                "--verbose"));

        String writableCookies = resolveWritableCookies();
        if (writableCookies != null) {
            cmd.add("--cookies");
            cmd.add(writableCookies);
        }

        if (proxyUrl != null && !proxyUrl.isBlank()) {
            cmd.add("--proxy");
            cmd.add(proxyUrl);
        }

        cmd.add(url);

        System.out.println("[YtDlpDownloader] cmd=" + cmd);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();

        // Leemos stdout y stderr en hilos separados y en paralelo: si se lee uno
        // completo antes de tocar el otro, el buffer del que no se lee se llena
        // (más ahora con --verbose) y el proceso se cuelga esperando que alguien
        // lo vacíe, mientras Java espera algo que nunca llega (deadlock clásico).
        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();
        Thread outT = new Thread(() -> drain(p.getInputStream(), stdoutBuf));
        Thread errT = new Thread(() -> drain(p.getErrorStream(), stderrBuf));
        outT.start();
        errT.start();

        boolean finished = p.waitFor(10, TimeUnit.MINUTES);
        outT.join(5000);
        errT.join(5000);

        if (!finished) {
            p.destroyForcibly();
            throw new IOException("yt-dlp timeout con " + url);
        }

        String stdout = stdoutBuf.toString();
        String stderr = stderrBuf.toString();

        System.out.println("[YtDlpDownloader] --- stdout ---\n" + stdout);
        System.out.println("[YtDlpDownloader] --- stderr ---\n" + stderr);

        if (p.exitValue() != 0) {
            throw new IOException("yt-dlp falló (" + p.exitValue() + "): " + lastLine(stderr));
        }

        String[] lines = stdout.strip().split("\n");
        if (lines.length < 2) {
            throw new IOException("yt-dlp no regresó filepath/título: " + stdout);
        }
        return new Descarga(Path.of(lines[0].strip()), lines[1].strip());
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

    /**
     * El Secret File de Render (/etc/secrets/cookies.txt) es de solo lectura,
     * pero yt-dlp necesita reescribirlo para refrescar la sesión. Lo copiamos
     * una vez a tmpDir (escribible) y usamos esa copia de ahí en adelante.
     */
    private String resolveWritableCookies() {
        try {
            Path source = Path.of(cookiesFile);
            if (!Files.exists(source)) {
                System.out.println("[YtDlpDownloader] cookiesFile=" + cookiesFile + " no existe, se omite");
                return null;
            }
            Path copy = Path.of(tmpDir, "cookies-writable.txt");
            if (!Files.exists(copy)) {
                Files.copy(source, copy);
                System.out.println("[YtDlpDownloader] cookies copiadas a " + copy);
            }
            return copy.toString();
        } catch (IOException e) {
            System.out.println("[YtDlpDownloader] no se pudo preparar cookies escribibles: " + e.getMessage());
            return null;
        }
    }
}
