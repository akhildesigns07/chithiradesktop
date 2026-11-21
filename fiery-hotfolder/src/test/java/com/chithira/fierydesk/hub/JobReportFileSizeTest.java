package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.CustomerRef;
import com.chithira.fierydesk.document.FilePageSize;
import com.chithira.fierydesk.fiery.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The job report carries the original file's size (measured before sending) to the Print Hub. */
class JobReportFileSizeTest {

    @Test
    void fileSizeIsSentInMillimetres() {
        FilePageSize card = new FilePageSize(255.12, 141.73, "image at its 600 DPI");   // 90 × 50 mm in points

        JobReport report = report().withFileSize(Optional.of(card));
        Map<?, ?> json = (Map<?, ?>) JsonParser.parse(report.toJson());

        assertEquals("90.0", json.get("fileWidthMm"));
        assertEquals("50.0", json.get("fileHeightMm"));
        assertEquals("image at its 600 DPI", json.get("fileSizeSource"));
    }

    @Test
    void unknownSizeLeavesTheReportUnchanged() {
        JobReport report = report();

        assertSame(report, report.withFileSize(Optional.empty()));
        assertNull(((Map<?, ?>) JsonParser.parse(report.toJson())).get("fileWidthMm"));
    }

    private static JobReport report() {
        return new JobReport(UUID.randomUUID().toString(), JobReport.SENT, "1001", new CustomerRef(1, "C-0001", "Anu"),
                "API", "card.jpg", null, "card.jpg", "5598", "13x19", false, null, null, 1, false, null, "staff",
                Instant.now(), null);
    }
}
