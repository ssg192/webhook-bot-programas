package mx.salvador.wabot.media;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Doc de letras del domingo: titulos en negritas, un salto de linea, sin
 * espaciados extra. Si hay letra historica encontrada, se pega debajo del
 * titulo CON su formato original (parrafos CTP clonados).
 * Las actualizaciones son SOLO aditivas: nunca se toca lo ya escrito.
 */
@ApplicationScoped
public class LyricsDocxService {

    public byte[] build(List<TitleCleaner.Titulo> canciones,
                        Map<String, LyricsHistoryService.Letra> letras) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (TitleCleaner.Titulo cancion : canciones) {
                addTitle(doc, cancion.display());
                pegarLetra(doc, letras.get(cancion.nombre()));
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    public record Sync(byte[] bytes, List<String> agregados) {}

    /**
     * Agrega al final los titulos (y su letra historica si existe) que aun
     * no aparezcan en el doc. Compara por nombre sin tono para no duplicar.
     */
    public Sync appendMissing(byte[] existingDocx,
                              List<TitleCleaner.Titulo> canciones,
                              Map<String, LyricsHistoryService.Letra> letras) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(existingDocx));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Centrar titulos existentes del bot que hayan quedado a la izquierda
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText() == null ? "" : p.getText().strip();
                if (!t.isBlank() && t.length() <= 60 && esNegritaCompleta(p)
                        && p.getAlignment() != org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER) {
                    p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
                }
            }

            StringBuilder allText = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                allText.append(LyricsHistoryService.normalizar(p.getText())).append('\n');
            }
            String texto = allText.toString();

            List<String> agregados = new ArrayList<>();
            for (TitleCleaner.Titulo cancion : canciones) {
                if (!texto.contains(LyricsHistoryService.normalizar(cancion.nombre()))) {
                    addTitle(doc, cancion.display());
                    pegarLetra(doc, letras.get(cancion.nombre()));
                    agregados.add(cancion.display());
                }
            }

            doc.write(out);
            return new Sync(out.toByteArray(), agregados);
        }
    }

    private static void pegarLetra(XWPFDocument doc, LyricsHistoryService.Letra letra) {
        if (letra == null || letra.parrafos().isEmpty()) return;
        for (CTP ctp : letra.parrafos()) {
            XWPFParagraph p = doc.createParagraph();
            p.getCTP().set(ctp);
        }
    }

    private static boolean esNegritaCompleta(XWPFParagraph p) {
        var runs = p.getRuns();
        if (runs.isEmpty()) return false;
        for (XWPFRun r : runs) {
            String t = r.text();
            if (t != null && !t.isBlank() && !r.isBold()) return false;
        }
        return true;
    }

    private static void addTitle(XWPFDocument doc, String texto) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
        p.setSpacingAfter(0);
        p.setSpacingBefore(0);
        XWPFRun r = p.createRun();
        r.setText(texto);
        r.setBold(true);
        r.setFontSize(13);
        r.setFontFamily("Calibri");
    }
}