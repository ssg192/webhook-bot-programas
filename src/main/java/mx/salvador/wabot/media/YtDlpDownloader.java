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
                "--extractor-args", "youtube:player_client=web,mweb,android",
                "-o", tmpDir + "/%(title)s.%(ext)s",
                "--print", "after_move:filepath",
                "--print", "after_move:title",
                "--no-simulate"));

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

        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();

        String stdout = new String(p.getInputStream().readAllBytes());
        String stderr = new String(p.getErrorStream().readAllBytes());

        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("yt-dlp timeout con " + url);
        }
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