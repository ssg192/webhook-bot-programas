package mx.salvador.wabot.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.drive.model.File;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import mx.salvador.wabot.drive.DriveService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.jboss.logging.Logger;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Busca letras en el historico de docs del Drive usando un INDICE persistente
 * (_letras-index.json en la carpeta raiz del bot):
 *
 *  - "indexar": barre TODO el historico (aunque sean años) y mapea
 *    cancion -> doc donde esta su letra. Se corre una vez y luego solo
 *    se actualiza incrementalmente con los docs nuevos/modificados.
 *  - buscar(): consulta el indice y descarga UNICAMENTE el doc que
 *    contiene cada cancion. Rapido sin importar el tamano del historico.
 *
 * Heuristica del formato del equipo: titulos de cancion en negritas,
 * secciones (Verso, Coro...) en texto normal. Los parrafos se copian
 * como XML (CTP) para conservar formato: resaltados, colores, centrado.
 */
@ApplicationScoped
public class LyricsHistoryService {

    private static final Logger LOG = Logger.getLogger(LyricsHistoryService.class);
    private static final String INDEX_NAME = "_letras-index.json";
    /** Docs nuevos que se escanean por corrida normal (fuera de "indexar"). */
    private static final int SCAN_INCREMENTAL = 15;

    private static final Set<String> SECCIONES = Set.of(
            "verso", "coro", "precoro", "pre coro", "puente", "intro", "inter",
            "outro", "final", "bridge", "chorus", "verse", "tag", "interludio",
            "instrumental", "estrofa", "rap", "vamp", "refran", "letra");

    /** Acorde al final del nombre de archivo: "TU NOMBRE Am", "TE SEGUIRE C". */
    private static final java.util.regex.Pattern ACORDE_FINAL =
            java.util.regex.Pattern.compile("\\s+([A-G][#b]?m?)\\s*$");

    /** "TONO A", "tono Gm", "Tono: D" — en nombres de doc o dentro del bloque. */
    private static final java.util.regex.Pattern TONO_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\btono\\s*[:\\-]?\\s*([A-Ga-g][#b]?m?)\\b");

    /** Charts descargados: "...Titulo.E.7" o "...Titulo.Gm" -> tono antes del ".numero" final. */
    private static final java.util.regex.Pattern KEY_DOT_SUFFIX =
            java.util.regex.Pattern.compile("\\.([A-G][#b]?m?)(?:\\.\\d+)?$");

    /** Tono entre parentesis al final: "(G)", "(tono A)", "(Tono: Gm)". */
    private static final java.util.regex.Pattern PAREN_ACORDE_FINAL =
            java.util.regex.Pattern.compile("(?i)\\(\\s*(?:tono\\s*[:\\-]?\\s*)?([A-Ga-g][#b]?m?)\\s*\\)\\s*$");

    /** Tono entre corchetes al final: "[A]", "[tono A]". */
    private static final java.util.regex.Pattern BRACKET_ACORDE_FINAL =
            java.util.regex.Pattern.compile("(?i)\\[\\s*(?:tono\\s*[:\\-]?\\s*)?([A-Ga-g][#b]?m?)\\s*\\]\\s*$");

    /** Tono envuelto en guiones al final: "-A-", "- Gm -". */
    private static final java.util.regex.Pattern DASH_ACORDE_FINAL =
            java.util.regex.Pattern.compile("-\\s*([A-G][#b]?m?)\\s*-\\s*$");

    /** Sufijo automatico de Google Drive al duplicar un nombre: "(1)", "(2)"... NO es tono. */
    private static final java.util.regex.Pattern DRIVE_DUP_SUFFIX =
            java.util.regex.Pattern.compile("\\s*\\(\\d+\\)\\s*$");

    /**
     * Marcador de voz/genero del equipo (NO es tono musical, es a que voz
     * corresponde el arreglo). Formas observadas en el historico real:
     * "(MUJER)", "(TONO MUJER)", "(version mujer)", "VERSION MUJER",
     * "TONO MUJER" (sin parentesis). Solo se quita cuando viene con ese
     * contexto (parentesis, o precedido de "tono"/"version") para no
     * arriesgarse a borrar la palabra si formara parte real de un titulo
     * (ej. un futuro "Hombre Nuevo.docx").
     */
    private static final java.util.regex.Pattern GENERO_VOZ =
            java.util.regex.Pattern.compile(
                    "(?i)\\(\\s*(?:tono\\s+|versi[oó]n\\s+)?(?:mujer|hombre|var[oó]n)\\s*\\)"
                            + "|\\b(?:tono|versi[oó]n)\\s+(?:mujer|hombre|var[oó]n)\\b");

    /**
     * Sufijos de tono reconocidos al final del nombre, en distintos "envoltorios".
     * Se prueban en orden porque son mutuamente excluyentes (uno solo por archivo).
     */
    private static final List<java.util.regex.Pattern> SUFIJOS_TONO_FINAL = List.of(
            PAREN_ACORDE_FINAL, BRACKET_ACORDE_FINAL, DASH_ACORDE_FINAL, ACORDE_FINAL);

    /** Quita ruido de Drive y el tono final (en cualquier envoltorio), en bucle hasta estabilizar. */
    private static String limpiarSufijos(String s) {
        String prev;
        int vueltas = 0;
        do {
            prev = s;
            s = DRIVE_DUP_SUFFIX.matcher(s.strip()).replaceAll("");
            for (var p : SUFIJOS_TONO_FINAL) {
                s = p.matcher(s.strip()).replaceAll("");
            }
        } while (!s.equals(prev) && ++vueltas < 5);
        return s;
    }

    @Inject DriveService driveService;
    @Inject ObjectMapper mapper;

    public record Letra(List<CTP> parrafos, String fuente, String tono) {}

    // ---------- modelo del indice (se serializa a JSON en Drive) ----------
    public static class Index {
        public Map<String, String> scanned = new HashMap<>(); // docId -> modifiedTime
        public Map<String, SongRef> songs = new HashMap<>();  // nombreNormalizado -> ref
    }
    public static class SongRef {
        public String docId;
        public String docName;
        public String tono; // ej. "A", "Gm", "F#" — referencia de sus docs, sin calculos
        public boolean soloTono; // true = fuente sin letra parseable (PDF o solo nombre)
        /** Archivos acordeorio de esta cancion (puede haber varias versiones). */
        public List<Acordeorio> acordeorios = new ArrayList<>();
    }

    public static class Acordeorio {
        public String id;
        public String name;
        public Acordeorio() {}
        public Acordeorio(String id, String name) { this.id = id; this.name = name; }
    }

    /** Barre TODO el historico y reconstruye el indice. Puede tardar. */
    public synchronized String indexarTodo() {
        try {
            Index index = new Index();
            var docs = driveService.listLyricsDocs();
            LOG.infof("Docs candidatos para indexar: %d", docs.size());
            int escaneados = 0;
            for (var d : docs) {
                escaneados += scanDoc(d, index) ? 1 : 0;
            }
            saveIndex(index);
            return "Indice listo: %d docs revisados, %d canciones encontradas."
                    .formatted(escaneados, index.songs.size());
        } catch (Exception e) {
            LOG.error("Fallo la indexacion completa", e);
            return "No pude indexar: " + e.getMessage();
        }
    }

    /**
     * Busca letras: actualiza el indice incrementalmente (docs nuevos o
     * modificados, con presupuesto acotado) y luego resuelve cada nombre
     * descargando solo el doc que lo contiene.
     */
    public synchronized Map<String, Letra> buscar(List<String> nombres) {
        Map<String, Letra> out = new HashMap<>();
        try {
            Index index = loadIndex();

            // actualizacion incremental acotada (solo si el indice ya existe;
            // si esta vacio, que lo construya "indexar", no este comando)
            if (!index.scanned.isEmpty()) {
                int budget = SCAN_INCREMENTAL;
                int nuevos = 0;
                for (var d : driveService.listLyricsDocs()) {
                    if (budget <= 0) break;
                    var f = d.file();
                    String mod = f.getModifiedTime() == null ? "" : f.getModifiedTime().toStringRfc3339();
                    if (!mod.equals(index.scanned.get(f.getId()))) {
                        if (scanDoc(d, index)) { budget--; nuevos++; }
                    }
                }
                if (nuevos > 0) {
                    LOG.infof("Incremental: %d docs nuevos escaneados", nuevos);
                    saveIndex(index);
                }
            }

            // resolucion: un download por doc (cacheado por corrida)
            Map<String, List<Bloque>> cacheDocs = new HashMap<>();
            for (String nombre : nombres) {
                SongRef ref = resolveRef(index, nombre);
                if (ref == null) continue;
                if (ref.soloTono) {
                    // Cancion sin letra en docs-programa: se queda sin letra.
                    // REGLA: el carril de notas JAMAS alimenta el de letras.
                    continue;
                }
                List<Bloque> bloques = cacheDocs.computeIfAbsent(ref.docId, id -> {
                    try {
                        File f = new File();
                        f.setId(ref.docId);
                        f.setMimeType(null);
                        byte[] data = driveService.downloadDocx(f);
                        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
                            return extraerBloques(doc);
                        }
                    } catch (Exception e) {
                        LOG.warnf("No pude leer doc %s: %s", ref.docName, e.getMessage());
                        return List.of();
                    }
                });
                Bloque b = matchBloque(bloques, nombre);
                if (b != null && !b.parrafos.isEmpty()) {
                    String tono = b.tono != null ? b.tono : ref.tono;
                    out.put(nombre, new Letra(b.parrafos, ref.docName, tono));
                }
            }
        } catch (Exception e) {
            LOG.warn("Fallo la busqueda de letras", e);
        }
        return out;
    }

    /**
     * Lookup barato: resuelve nombres contra el indice SIN escaneo incremental
     * ni descarga de docs. Para consultar tono/acordeorio al subir canciones.
     */
    public synchronized Map<String, SongRef> refs(List<String> nombres) {
        Map<String, SongRef> out = new HashMap<>();
        try {
            Index index = loadIndex();
            for (String nombre : nombres) {
                SongRef ref = resolveRef(index, nombre);
                if (ref != null) out.put(nombre, ref);
            }
        } catch (Exception e) {
            LOG.warn("Fallo el lookup de refs", e);
        }
        return out;
    }

    // ---------- indice ----------

    private boolean scanDoc(mx.salvador.wabot.drive.DriveService.LyricsDoc d, Index index) {
        File f = d.file();
        try {
            LOG.infof("Escaneando%s: %s", d.esDeNotas() ? " (notas)" : "", f.getName());

            if (d.esDeNotas()) {
                // ARCHIVO DE NOTAS: es un acordeorio por-cancion. Se registra por
                // su NOMBRE (titulo + tono si trae) y NUNCA se parsea su contenido
                // como fuente de letras (traen lineas en negritas que contaminan).
                String tituloNombre = tituloDesdeNombre(f.getName());
                if (tituloNombre.length() >= 3) {
                    SongRef ref = new SongRef();
                    ref.docId = f.getId();
                    ref.docName = f.getName();
                    ref.tono = tonoDesdeNombre(f.getName());
                    ref.soloTono = true;
                    ref.acordeorios.add(new Acordeorio(f.getId(), f.getName()));
                    registrar(index, tituloNombre, ref);
                }
            } else if (!mx.salvador.wabot.drive.DriveService.esPdf(f)) {
                // DOC-PROGRAMA: unica fuente valida de LETRAS (bloques con formato).
                byte[] data = driveService.downloadDocx(f);
                String tonoDelDoc = extraerTono(f.getName());
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
                    for (Bloque b : extraerBloques(doc)) {
                        if (!b.parrafos.isEmpty()) {
                            SongRef ref = new SongRef();
                            ref.docId = f.getId();
                            ref.docName = f.getName();
                            ref.tono = b.tono != null ? b.tono : tonoDelDoc;
                            ref.soloTono = false;
                            registrar(index, b.tituloNorm, ref);
                        }
                    }
                }
            }

            String mod = f.getModifiedTime() == null ? "" : f.getModifiedTime().toStringRfc3339();
            index.scanned.put(f.getId(), mod);
            return true;
        } catch (Exception e) {
            LOG.warnf("No pude escanear %s: %s", f.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * "Digno es el Senor TONO A.pdf" -> "digno es el senor"
     * "TE SEGUIRE C.docx" -> "te seguire"  (acorde final = convencion del equipo)
     * "En Ti-Marco Barrientos- Director Creativo-En Ti.E.7.pdf" -> "en ti"
     *   (charts descargados: Titulo-Artista-Rol-Titulo.Tono.Version; el titulo
     *    real es siempre lo que hay ANTES del primer guion)
     */
    static String tituloDesdeNombre(String fileName) {
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);

        // Guion(es) sueltos al inicio (charts mal exportados, ej. "-Te Exalto)-...")
        // no cuentan como el separador titulo/artista; se descartan primero.
        s = s.replaceFirst("^[\\s\\-)]+", "");

        int guion = s.indexOf('-');
        if (guion > 0 && s.substring(0, guion).strip().matches("\\d+")) {
            // Prefijo puramente numerico: es un ID de Scribd/descarga, no titulo.
            // Se sigue con el resto del nombre en vez de quedarse con el numero.
            s = s.substring(guion + 1);
            s = TONO_PATTERN.matcher(s).replaceAll(" ");
            s = limpiarSufijos(s);
        } else if (guion > 0) {
            s = s.substring(0, guion);
        } else {
            s = TONO_PATTERN.matcher(s).replaceAll(" ");
            s = limpiarSufijos(s);
        }
        s = GENERO_VOZ.matcher(s).replaceAll(" ");
        return normalizar(s);
    }

    /** Tono desde el nombre: "TONO X" en cualquier parte, si no, el sufijo final en su envoltorio. */
    static String tonoDesdeNombre(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String t = extraerTono(base);
        if (t != null) return t;
        base = DRIVE_DUP_SUFFIX.matcher(base.strip()).replaceAll("");
        for (var p : SUFIJOS_TONO_FINAL) {
            var m = p.matcher(base.strip());
            if (m.find()) return normalizarAcorde(m.group(1));
        }
        var mk = KEY_DOT_SUFFIX.matcher(base.strip());
        if (mk.find()) {
            return normalizarAcorde(mk.group(1));
        }
        return null;
    }

    private static String normalizarAcorde(String c) {
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(c.charAt(0)));
        for (int i = 1; i < c.length(); i++) {
            char ch = c.charAt(i);
            sb.append(ch == '#' ? ch : Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /**
     * Registro con prioridades:
     * - entrada nueva si no existia
     * - una entrada CON letra siempre le gana a una solo-tono
     * - los tonos se completan mutuamente cuando faltan
     */
    private static void registrar(Index index, String tituloNorm, SongRef nuevo) {
        SongRef prev = index.songs.get(tituloNorm);
        if (prev == null) {
            index.songs.put(tituloNorm, nuevo);
            return;
        }
        // completar datos faltantes en ambas direcciones
        if (prev.tono == null && nuevo.tono != null) prev.tono = nuevo.tono;
        // acumular acordeorios sin duplicar POR NOMBRE: el equipo copiaba el mismo
        // archivo a las notas de cada domingo, asi que mismo nombre = misma version
        // (recientes primero por el orden de escaneo => queda la copia mas nueva)
        // OJO: comparamos con forma Unicode normalizada (NFC) porque el mismo nombre
        // visual puede llegar en distinta forma (ej. "Ñ" compuesta vs N+tilde
        // combinante segun el origen del archivo), y equalsIgnoreCase() a secas
        // los trata como distintos aunque se vean identicos.
        for (Acordeorio a : nuevo.acordeorios) {
            String nombre = nombreComparable(a.name);
            boolean existe = prev.acordeorios.stream()
                    .anyMatch(x -> nombreComparable(x.name).equalsIgnoreCase(nombre));
            if (!existe) prev.acordeorios.add(a);
        }
        if (prev.soloTono && !nuevo.soloTono) {
            if (nuevo.tono == null) nuevo.tono = prev.tono;
            nuevo.acordeorios = prev.acordeorios; // ya acumulados arriba
            index.songs.put(tituloNorm, nuevo);
        }
    }

    private static String nombreComparable(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.strip(), Normalizer.Form.NFC);
    }

    /**
     * Resuelve por substring, pero cuando hay varias claves candidatas (ej. la
     * generica "digno" y la especifica "digno de adorar" para una busqueda larga
     * tipo YouTube "Digno de Adorar en vivo Miel San Marcos x Waleska Morales"),
     * SIEMPRE gana la coincidencia mas larga/especifica — no la que el HashMap
     * itere primero, que es no determinista y puede traer la cancion equivocada.
     */
    private SongRef resolveRef(Index index, String nombre) {
        String objetivo = normalizar(nombre);
        if (objetivo.length() < 3) return null;
        SongRef exacto = index.songs.get(objetivo);
        if (exacto != null) return exacto;

        SongRef mejor = null;
        int mejorScore = -1;
        for (Map.Entry<String, SongRef> e : index.songs.entrySet()) {
            String k = e.getKey();
            if (k.length() < 3) continue;
            int score;
            if (objetivo.contains(k)) {
                score = k.length();          // clave completa cabe en la busqueda: mas larga = mas especifica
            } else if (k.contains(objetivo)) {
                score = objetivo.length();    // busqueda completa cabe en la clave (caso inverso)
            } else {
                continue;
            }
            if (score > mejorScore) {
                mejorScore = score;
                mejor = e.getValue();
            }
        }
        return mejor;
    }

    private Index loadIndex() {
        try {
            var f = driveService.findFile(INDEX_NAME, driveService.rootFolder());
            if (f == null) return new Index();
            byte[] data = driveService.downloadBytes(f.getId());
            return mapper.readValue(data, Index.class);
        } catch (Exception e) {
            LOG.warn("Indice ilegible, empezando uno nuevo", e);
            return new Index();
        }
    }

    private void saveIndex(Index index) {
        try {
            byte[] data = mapper.writeValueAsBytes(index);
            var f = driveService.findFile(INDEX_NAME, driveService.rootFolder());
            if (f == null) {
                driveService.uploadBytes(INDEX_NAME, data, "application/json", driveService.rootFolder());
            } else {
                driveService.updateBytes(f.getId(), data, "application/json");
            }
        } catch (Exception e) {
            LOG.warn("No pude guardar el indice", e);
        }
    }

    // ---------- parseo ----------

    private static class Bloque {
        String tituloNorm;
        String tono; // primera mencion "Tono: X" dentro del bloque
        List<CTP> parrafos = new ArrayList<>();
    }

    /** Extrae el tono de un texto ("TONO A" -> "A", "tono gm" -> "Gm"); null si no hay. */
    static String extraerTono(String texto) {
        if (texto == null) return null;
        var m = TONO_PATTERN.matcher(texto);
        if (!m.find()) return null;
        String t = m.group(1);
        // normalizar presentacion: nota en mayuscula, m/b en minuscula, # tal cual
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(t.charAt(0)));
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            sb.append(c == '#' ? c : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private List<Bloque> extraerBloques(XWPFDocument doc) {
        List<Bloque> bloques = new ArrayList<>();
        Bloque actual = null;
        for (XWPFParagraph p : doc.getParagraphs()) {
            String text = p.getText() == null ? "" : p.getText().strip();
            if (esTitulo(p, text)) {
                String norm = normalizar(text);
                // basura tipica: claves muy cortas o puras palabras de seccion
                boolean basura = norm.length() < 3
                        || java.util.Arrays.stream(norm.split(" "))
                        .allMatch(t -> SECCIONES.contains(t) || t.matches("\\d+")
                                || t.equals("mod") || t.equals("minist")
                                || t.equals("cambio") || t.equals("voluntad"));
                if (basura) {
                    actual = null; // lo que siga no pertenece a ninguna cancion
                    continue;
                }
                actual = new Bloque();
                actual.tituloNorm = norm;
                bloques.add(actual);
            } else if (actual != null) {
                actual.parrafos.add((CTP) p.getCTP().copy());
                if (actual.tono == null) {
                    actual.tono = extraerTono(text);
                }
            }
        }
        return bloques;
    }

    private boolean esTitulo(XWPFParagraph p, String text) {
        if (text.isBlank() || text.length() > 60) return false;
        if (esSeccion(text)) return false;
        List<XWPFRun> runs = p.getRuns();
        if (runs.isEmpty()) return false;
        for (XWPFRun r : runs) {
            String t = r.text();
            if (t != null && !t.isBlank() && !r.isBold()) return false;
        }
        return true;
    }

    private boolean esSeccion(String text) {
        String n = normalizar(text).replaceAll("\\d+$", "").strip();
        return SECCIONES.contains(n);
    }

    private Bloque matchBloque(List<Bloque> bloques, String nombre) {
        String objetivo = normalizar(nombre);
        if (objetivo.length() < 3) return null;
        for (Bloque b : bloques) {
            if (b.tituloNorm.equals(objetivo)) return b;
        }
        for (Bloque b : bloques) {
            if (b.tituloNorm.length() >= 3
                    && (b.tituloNorm.contains(objetivo) || objetivo.contains(b.tituloNorm))) {
                return b;
            }
        }
        return null;
    }

    static String normalizar(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        n = n.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").strip();
        return n;
    }
}