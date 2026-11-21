package com.chithira.fierydesk.core;

import com.chithira.fierydesk.document.FilePageSize;
import com.chithira.fierydesk.fiery.FieryClient;
import com.chithira.fierydesk.fiery.JobOptions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The job tells the Fiery the file's page size only for "Actual size". */
class FilePageSizeAttributesTest {

    @TempDir
    Path folder;

    @Test
    void actualSizeSetsTheConfiguredAttributes() throws Exception {
        Path sticker = pdf(252, 144);
        JobProcessor processor = new JobProcessor(new FieryClient(new AppConfig().fiery), new AppConfig());
        JobOptions options = new JobOptions();

        String info = processor.applyFilePageSize(request(sticker, Sizing.ACTUAL), options);

        assertEquals("3.5x2", options.attributes().get("PageSize"));
        assertEquals("3.5x2", options.attributes().get("EFPrintSize"));
        assertTrue(info.contains("3.5 × 2 in"), info);
    }

    @Test
    void otherSizingAndEmptyAttributeListSendNothing() throws Exception {
        Path sticker = pdf(252, 144);
        JobProcessor processor = new JobProcessor(new FieryClient(new AppConfig().fiery), new AppConfig());
        JobOptions fit = new JobOptions();
        assertNull(processor.applyFilePageSize(request(sticker, Sizing.FIT), fit));
        assertTrue(fit.attributes().isEmpty());

        AppConfig millimetres = new AppConfig();
        millimetres.pageSizeAttributes = List.of("PageSize");
        millimetres.pageSizeUnit = FilePageSize.Unit.MM;
        JobOptions mm = new JobOptions();
        new JobProcessor(new FieryClient(millimetres.fiery), millimetres).applyFilePageSize(request(sticker, Sizing.ACTUAL), mm);
        assertEquals("88.9x50.8", mm.attributes().get("PageSize"));

        AppConfig off = new AppConfig();
        off.pageSizeAttributes = List.of();
        JobOptions none = new JobOptions();
        assertNull(new JobProcessor(new FieryClient(off.fiery), off).applyFilePageSize(request(sticker, Sizing.ACTUAL), none));
        assertTrue(none.attributes().isEmpty());
    }

    private Path pdf(float widthPt, float heightPt) throws Exception {
        Path file = folder.resolve("sticker.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(widthPt, heightPt)));
            pdf.save(file.toFile());
        }
        return file;
    }

    private static JobRequest request(Path file, Sizing sizing) {
        return new JobRequest(file, "sticker", null, sizing, 1, null, false);
    }
}
