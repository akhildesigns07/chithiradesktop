package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.CustomerRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Job reports must survive a hub outage and be delivered once, oldest first. */
class OutboxTest {

    @TempDir
    Path directory;

    @Test
    void keepsReportsWhileHubIsDownAndDeliversThemLater() throws Exception {
        Outbox outbox = new Outbox(directory);
        FakeHubClient hub = new FakeHubClient();
        outbox.add(report("first.jpg", 1000));
        outbox.add(report("second.jpg", 2000));

        hub.nextReportFailures.add(new HubException(0, "Print Hub not reachable", null));
        Outbox.FlushResult offline = outbox.deliver(hub);

        assertEquals(0, offline.delivered());
        assertEquals(2, offline.waiting());
        assertNotNull(offline.stoppedBy());

        Outbox.FlushResult online = outbox.deliver(hub);

        assertEquals(2, online.delivered());
        assertEquals(0, outbox.waiting());
        assertTrue(hub.reportedJobs.get(0).contains("first.jpg"), "oldest first");
        assertTrue(hub.reportedJobs.get(1).contains("second.jpg"));
    }

    @Test
    void reportTheHubRefusesIsSetAsideAndDoesNotBlockTheRest() throws Exception {
        Outbox outbox = new Outbox(directory);
        FakeHubClient hub = new FakeHubClient();
        outbox.add(report("bad.jpg", 1000));
        outbox.add(report("good.jpg", 2000));

        hub.nextReportFailures.add(new HubException(422, "Unknown customerId 9", "{\"error\":\"Unknown customerId 9\"}"));
        Outbox.FlushResult result = outbox.deliver(hub);

        assertEquals(1, result.rejected());
        assertEquals(1, result.delivered());
        assertEquals(0, result.waiting());
        try (var rejected = Files.list(directory.resolve("rejected"))) {
            assertEquals(2, rejected.count(), "report + .error.txt");
        }
    }

    @Test
    void wrongApiKeyStopsDeliveryAndKeepsEverything() throws Exception {
        Outbox outbox = new Outbox(directory);
        FakeHubClient hub = new FakeHubClient();
        outbox.add(report("card.jpg", 1000));

        hub.nextReportFailures.add(new HubException(401, "Print Hub rejected the API key", null));
        Outbox.FlushResult result = outbox.deliver(hub);

        assertEquals(1, result.waiting());
        assertTrue(result.stoppedBy().isKeyProblem());
    }

    private static JobReport report(String fileName, long sentAtMillis) {
        return new JobReport(java.util.UUID.randomUUID().toString(), JobReport.SENT, "1001",
                new CustomerRef(1, "C-0001", "Anu"), "API", fileName, null, fileName, "5598", "13x19 300gsm",
                false, null, null, 2, false, null, "staff", Instant.ofEpochMilli(sentAtMillis), null);
    }
}
