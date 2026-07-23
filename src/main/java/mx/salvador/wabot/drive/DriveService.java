package mx.salvador.wabot.drive;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class DriveService {

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    public static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @ConfigProperty(name = "drive.oauth.client-id")
    String clientId;

    @ConfigProperty(name = "drive.oauth.client-secret")
    String clientSecret;

    @ConfigProperty(name = "drive.oauth.refresh-token")
    String refreshToken;

    @ConfigProperty(name = "drive.root-folder-id")
    String rootFolderId;

    /** Carpeta con los docs historicos de letras del equipo (opcional). */
    @ConfigProperty(name = "drive.lyrics-history-folder-id")
    java.util.Optional<String> lyricsHistoryFolderId;

    private Drive drive;

    @PostConstruct
    void init() throws Exception {
        var credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();
        drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("wa-drive-bot")
                .build();
    }

    /** ID de la carpeta raiz del bot (para el indice de letras). */
    public String rootFolder() {
        return rootFolderId;
    }

    public record EstructuraDomingo(String playlistId, String notasId, String domingoId, String link) {}

    /**
     * Crea (si no existe):
     *   root/Domingo YYYY-MM-DD/{Playlist, Notas}
     */
    private final Object folderLock = new Object();

    /**
     * Crea (si no existe) la jerarquia estilo del equipo:
     *   root/2026/Julio/19 de Julio 2026/{Playlist, Notas}
     * Sincronizado para que rafagas de mensajes no dupliquen carpetas.
     */
    public EstructuraDomingo ensureSundayStructure(java.time.LocalDate domingo) throws Exception {
        synchronized (folderLock) {
            String anioId = findOrCreateFolder(mx.salvador.wabot.util.Fechas.anio(domingo), rootFolderId);
            String mesId = findOrCreateFolder(mx.salvador.wabot.util.Fechas.mes(domingo), anioId);
            String fechaId = findOrCreateFolder(mx.salvador.wabot.util.Fechas.nombreCarpeta(domingo), mesId);
            String playlistId = findOrCreateFolder("Playlist", fechaId);
            String notasId = findOrCreateFolder("Notas", fechaId);
            return new EstructuraDomingo(playlistId, notasId, fechaId,
                    "https://drive.google.com/drive/folders/" + fechaId);
        }
    }

    /** Sube el audio (m4a o mp3); si ya existe uno con el mismo nombre, no lo duplica. */
    public File uploadMp3(Path localFile, String parentId) throws Exception {
        String name = localFile.getFileName().toString();

        File existing = findFile(name, parentId);
        if (existing != null) {
            return existing;
        }

        String mime = name.toLowerCase().endsWith(".mp3") ? "audio/mpeg" : "audio/mp4";
        File metadata = new File().setName(name).setParents(List.of(parentId));
        FileContent content = new FileContent(mime, localFile.toFile());
        return drive.files().create(metadata, content)
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink")
                .execute();
    }

    /** Sube un archivo desde bytes (p. ej. el docx de letras). */
    public File uploadBytes(String name, byte[] data, String mimeType, String parentId) throws Exception {
        File metadata = new File().setName(name).setParents(List.of(parentId));
        ByteArrayContent content = new ByteArrayContent(mimeType, data);
        return drive.files().create(metadata, content)
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink")
                .execute();
    }

    /** Descarga el contenido de un archivo (p. ej. el docx para actualizarlo). */
    public byte[] downloadBytes(String fileId) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        drive.files().get(fileId).executeMediaAndDownloadTo(out);
        return out.toByteArray();
    }

    /** Reemplaza el contenido de un archivo existente conservando su id/link. */
    public File updateBytes(String fileId, byte[] data, String mimeType) throws Exception {
        ByteArrayContent content = new ByteArrayContent(mimeType, data);
        return drive.files().update(fileId, new File(), content)
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink")
                .execute();
    }

    /**
     * Copia un archivo existente de Drive a otra carpeta (server-side, sin
     * descargar). No duplica si ya hay uno con ese nombre en el destino.
     */
    public File copyTo(String fileId, String fileName, String destFolderId) throws Exception {
        File existente = findFile(fileName, destFolderId);
        if (existente != null) return existente;
        return drive.files().copy(fileId, new File()
                        .setName(fileName)
                        .setParents(List.of(destFolderId)))
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink")
                .execute();
    }

    private static boolean esAudio(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".m4a") || n.endsWith(".mp3");
    }

    /** Busca un archivo por nombre exacto dentro de una carpeta; null si no existe. */
    public File findFile(String name, String parentId) throws Exception {
        FileList list = drive.files().list()
                .setQ("name = '%s' and '%s' in parents and trashed = false"
                        .formatted(escape(name), parentId))
                .setFields("files(id, name, webViewLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();
        return list.getFiles().isEmpty() ? null : list.getFiles().get(0);
    }

    /** Nombres de todos los audios (m4a/mp3) de una carpeta (para contar canciones y armar el docx). */
    public List<String> listMp3Names(String parentId) throws Exception {
        FileList list = drive.files().list()
                .setQ("'%s' in parents and trashed = false".formatted(parentId))
                .setFields("files(name)")
                .setOrderBy("createdTime")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();
        List<String> names = new ArrayList<>();
        for (File f : list.getFiles()) {
            if (esAudio(f.getName())) {
                names.add(f.getName());
            }
        }
        return names;
    }

    /**
     * Manda a la papelera todas las variantes de una cancion en la carpeta:
     * "Base.m4a", "Base (-2).m4a" (o .mp3), etc., excepto la recien subida (exceptName).
     */
    public List<String> trashVariants(String parentId, String baseName, String exceptName) throws Exception {
        FileList list = drive.files().list()
                .setQ("'%s' in parents and trashed = false".formatted(parentId))
                .setFields("files(id, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();

        List<String> trashed = new ArrayList<>();
        for (File f : list.getFiles()) {
            String n = f.getName();
            boolean esVariante = esAudio(n)
                    && (n.equals(baseName + ".m4a") || n.equals(baseName + ".mp3")
                    || n.startsWith(baseName + " ("));
            if (esVariante && !n.equals(exceptName)) {
                drive.files().update(f.getId(), new File().setTrashed(true))
                        .setSupportsAllDrives(true)
                        .execute();
                trashed.add(n);
            }
        }
        return trashed;
    }

    /** Doc candidato con su origen: programa (letras) o carpeta notas (acordeorios). */
    public record LyricsDoc(File file, boolean esDeNotas) {}

    /**
     * Docs candidatos donde buscar letras, mas recientes primero:
     * 1) la carpeta historica del equipo (si esta configurada)
     * 2) los docs dentro de las carpetas "Domingo *" que maneja el bot
     */
    public List<LyricsDoc> listLyricsDocs() throws Exception {
        List<LyricsDoc> docs = new ArrayList<>();
        if (lyricsHistoryFolderId.isPresent() && !lyricsHistoryFolderId.get().isBlank()) {
            collectDocx(lyricsHistoryFolderId.get(), 4, docs);
        }
        // estructura anio/mes/fecha del propio bot
        collectDocx(rootFolderId, 4, docs);
        docs.sort((a, b) -> {
            var ma = a.file().getModifiedTime();
            var mb = b.file().getModifiedTime();
            if (ma == null || mb == null) return 0;
            return Long.compare(mb.getValue(), ma.getValue()); // recientes primero
        });
        return docs;
    }

    private void collectDocx(String parentId, int depth, List<LyricsDoc> out) throws Exception {
        List<File> children = new ArrayList<>();
        String pageToken = null;
        do {
            FileList list = drive.files().list()
                    .setQ("'%s' in parents and trashed = false".formatted(parentId))
                    .setFields("nextPageToken, files(id, name, mimeType, modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute();
            children.addAll(list.getFiles());
            pageToken = list.getNextPageToken();
        } while (pageToken != null);

        boolean nivelPrograma = children.stream().anyMatch(f ->
                FOLDER_MIME.equals(f.getMimeType()) && esCarpetaMarcador(f.getName()));

        for (File f : children) {
            if (FOLDER_MIME.equals(f.getMimeType())) {
                if (nivelPrograma) {
                    if (esCarpetaNotas(f.getName())) {
                        collectPlano(f.getId(), out);
                    }
                } else if (depth > 0) {
                    collectDocx(f.getId(), depth - 1, out);
                }
            } else if (nivelPrograma && esDocLike(f) && !esDocDelBot(f.getName())) {
                out.add(new LyricsDoc(f, false)); // doc-programa: fuente de LETRAS
            }
        }
    }

    /** Recolecta docs de una carpeta notas (acordeorios), sin bajar mas niveles. */
    private void collectPlano(String parentId, List<LyricsDoc> out) throws Exception {
        String pageToken = null;
        do {
            FileList list = drive.files().list()
                    .setQ("'%s' in parents and trashed = false".formatted(parentId))
                    .setFields("nextPageToken, files(id, name, mimeType, modifiedTime)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute();
            for (File f : list.getFiles()) {
                if (esDocLike(f)) out.add(new LyricsDoc(f, true)); // notas: solo ACORDEORIO
            }
            pageToken = list.getNextPageToken();
        } while (pageToken != null);
    }

    /** Docs generados por el bot: JAMAS son fuente (evita contaminacion circular). */
    private static boolean esDocDelBot(String nombre) {
        return nombre.startsWith("Letras - ") || nombre.startsWith("_letras-index");
    }

    private static boolean esCarpetaMarcador(String nombre) {
        String s = nombre.strip().toLowerCase();
        return s.equals("notas") || s.equals("playlist") || s.equals("notes");
    }

    private static boolean esCarpetaNotas(String nombre) {
        String s = nombre.strip().toLowerCase();
        return s.equals("notas") || s.equals("notes");
    }

    /** docx, Google Doc nativo o PDF. */
    public static boolean esDocLike(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".docx") || n.endsWith(".pdf")
                || DOCX_MIME.equals(f.getMimeType())
                || "application/pdf".equals(f.getMimeType())
                || "application/vnd.google-apps.document".equals(f.getMimeType());
    }

    /** true si el archivo es un PDF (solo aporta tono por nombre, no letra). */
    public static boolean esPdf(File f) {
        return f.getName().toLowerCase().endsWith(".pdf")
                || "application/pdf".equals(f.getMimeType());
    }

    /** Baja un doc como bytes .docx; si es Google Doc nativo, lo exporta a docx. */
    public byte[] downloadDocx(File f) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if ("application/vnd.google-apps.document".equals(f.getMimeType())) {
            drive.files().export(f.getId(), DOCX_MIME).executeMediaAndDownloadTo(out);
        } else {
            drive.files().get(f.getId()).executeMediaAndDownloadTo(out);
        }
        return out.toByteArray();
    }

    /**
     * Busca la carpeta comparando el nombre NORMALIZADO (ver norm()): tolera espacios
     * sobrantes, acentos y mayusculas. El equipo crea carpetas como "Julio " con espacio
     * al final, y un "name = 'Julio'" exacto no las encontraba -> duplicados.
     * Si varias empatan, gana la mas antigua (setOrderBy createdTime).
     */
    private String findOrCreateFolder(String name, String parentId) throws Exception {
        String buscado = norm(name);
        String pageToken = null;

        do {
            FileList list = drive.files().list()
                    .setQ("mimeType = '%s' and '%s' in parents and trashed = false"
                            .formatted(FOLDER_MIME, parentId))
                    .setFields("nextPageToken, files(id, name)")
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .setOrderBy("createdTime")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute();

            for (File f : list.getFiles()) {
                if (norm(f.getName()).equals(buscado)) {
                    return f.getId();
                }
            }
            pageToken = list.getNextPageToken();
        } while (pageToken != null);

        File folder = new File()
                .setName(name)
                .setMimeType(FOLDER_MIME)
                .setParents(List.of(parentId));
        return drive.files().create(folder)
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
                .getId();
    }

    private static String escape(String s) {
        return s.replace("'", "\\'");
    }

    /**
     * Normaliza nombres de carpeta para comparar. Tolera lo que la gente escribe a mano:
     * acentos de mas/de menos, espacios sobrantes al inicio/final, espacios dobles en
     * medio, espacio duro (nbsp) al copiar de web, mayusculas y distinta codificacion
     * Unicode (Mac usa NFD, Windows NFC).
     * NO tolera typos ("Jullio") ni otro formato ("26 Julio 2026" vs "26 de Julio 2026").
     */
    private static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")        // quita acentos ya separados por NFD
                .replace('\u00A0', ' ')          // nbsp -> espacio normal
                .replaceAll("\\s+", " ")         // colapsa espacios internos
                .strip()
                .toLowerCase(java.util.Locale.ROOT);
    }
}