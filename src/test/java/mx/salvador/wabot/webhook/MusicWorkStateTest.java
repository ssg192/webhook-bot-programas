package mx.salvador.wabot.webhook;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mx.salvador.wabot.media.YtDlpDownloader;
import mx.salvador.wabot.media.PitchShifter;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import static org.junit.jupiter.api.Assertions.*;

class MusicWorkStateTest {
    @Test
    void unknownIsNotReportedAsMissingAndSnapshotsAreImmutable() {
        var state = new MusicWorkState();
        var before = state.snapshot(List.of("Ingrid.m4a"), 1);
        state.note("Ingrid.m4a", "Copiadas: Ingrid.pdf");
        assertTrue(MusicWorkState.describe(before, "status_notes", 1).contains("No se ha confirmado"));
        assertTrue(MusicWorkState.describe(state.snapshot(List.of("Ingrid.m4a"), 0), "status_notes", 1).contains("Copiadas: Ingrid.pdf"));
    }

    @Test
    void documentsWaitForUploadsWithoutBlockingStatusOrOtherSenders() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var pipeline = new SongPipeline() {
            @Override void process(String from, List<String> urls, int semitones, String original) {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            @Override void generarLetras(String from) { done.countDown(); }
        };
        pipeline.allowedNumbers = Optional.empty();
        pipeline.downloader = new YtDlpDownloader();
        pipeline.pitchShifter = new PitchShifter();
        pipeline.whatsApp = new WhatsAppService() {
            @Override public void replyText(String to, String text) {}
        };
        try {
            pipeline.handleIncoming(new WebhookPayload.Message("download", "a", "text",
                    new WebhookPayload.Text("https://youtu.be/abcdefghi01")));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            pipeline.requestDocuments("a", false, true, null);
            assertEquals(1, pipeline.context("a", List.of()).get("descargasPendientes"));
            assertEquals(1, pipeline.context("a", List.of()).get("peticionesDocumentosEnEspera"));
            assertEquals(0, pipeline.context("b", List.of()).get("descargasPendientes"));
            assertEquals(1, done.getCount());
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, pipeline.context("a", List.of()).get("descargasPendientes"));
        } finally { release.countDown(); pipeline.shutdown(); }
    }
}
