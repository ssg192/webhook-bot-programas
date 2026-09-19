package mx.salvador.wabot.webhook;

import mx.salvador.wabot.drive.DriveService;
import mx.salvador.wabot.media.YtDlpDownloader;
import mx.salvador.wabot.whatsapp.WhatsAppService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SongPipelineFeedbackTest {
    @Test
    void repeatedVersionWithNewlineDoesNotEnterToneFlow() throws Exception {
        var done = new CountDownLatch(1);
        var selected = new java.util.concurrent.atomic.AtomicInteger();
        var pipeline = new SongPipeline() {
            @Override void chooseNoteVersion(String from, NoteChoice expected, int selection) {
                selected.set(selection);
                done.countDown();
            }
        };
        pipeline.allowedNumbers = Optional.empty();
        pipeline.downloader = new YtDlpDownloader();
        try {
            pipeline.handleIncoming(new WebhookPayload.Message("choice", "525500000000", "text",
                    new WebhookPayload.Text("version\n la version 2.")));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(2, selected.get());
        } finally { pipeline.shutdown(); }
    }

    @Test
    void lyricsAnnouncesWorkAndClearsBusyStateAfterFailure() {
        List<String> replies = new ArrayList<>();
        var pipeline = new SongPipeline();
        pipeline.whatsApp = new WhatsAppService() {
            @Override public void replyText(String to, String body) { replies.add(body); }
        };
        pipeline.driveService = new DriveService() {
            @Override public EstructuraDomingo ensureSundayStructure(LocalDate date) {
                throw new IllegalStateException("test failure");
            }
        };
        try {
            pipeline.generarLetras("sender");
            pipeline.generarLetras("sender");
            assertEquals(4, replies.size());
            assertTrue(replies.get(0).contains("varios minutos"));
            assertTrue(replies.get(1).startsWith("No pude crear"));
            assertTrue(replies.get(2).contains("varios minutos"));
        } finally { pipeline.shutdown(); }
    }
}
