package com.chithira.printhub.jobs;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * What a desktop reports after sending a job (JSON body of {@code POST /api/v1/jobs}).
 *
 * @param clientJobUuid made by the desktop once per job; the same value on a retry returns the job already saved
 * @param outcome       SENT (the Fiery or driver accepted it) or FAILED (rejected; {@code error} says why)
 * @param fieryJobId    job id returned by the Fiery API; null for the printer-driver method
 * @param sheets        copies sent to the Fiery (sheets for imposed jobs)
 * @param fileWidthMm / fileHeightMm  the original file's own size, measured by the desktop (optional)
 * @param fileSizeSource how the desktop found that size, e.g. "image at its 600 DPI" (optional)
 */
public record JobReport(
        @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "must be a UUID") String clientJobUuid,
        @NotNull @Pattern(regexp = "SENT|FAILED") String outcome,
        @Size(max = 40) String fieryJobId,
        Long customerId,
        @NotNull SendMethod sendMethod,
        @NotBlank @Size(max = 255) String fileName,
        @Size(max = 255) String backFileName,
        @Size(max = 255) String jobName,
        @Size(max = 40) String paperId,
        @Size(max = 150) String paperName,
        boolean imposed,
        @Min(0) Integer quantity,
        @Min(0) Integer piecesPerSheet,
        @Min(0) int sheets,
        boolean duplex,
        @Size(max = 2000) String instructions,
        @Size(max = 80) String operator,
        @NotNull Instant sentAt,
        @Size(max = 2000) String error,
        @DecimalMin("0.1") @DecimalMax("100000") Double fileWidthMm,
        @DecimalMin("0.1") @DecimalMax("100000") Double fileHeightMm,
        @Size(max = 80) String fileSizeSource) {

    /** A report without the file size (older desktops, and tests). */
    public JobReport(String clientJobUuid, String outcome, String fieryJobId, Long customerId, SendMethod sendMethod,
                     String fileName, String backFileName, String jobName, String paperId, String paperName,
                     boolean imposed, Integer quantity, Integer piecesPerSheet, int sheets, boolean duplex,
                     String instructions, String operator, Instant sentAt, String error) {
        this(clientJobUuid, outcome, fieryJobId, customerId, sendMethod, fileName, backFileName, jobName, paperId,
                paperName, imposed, quantity, piecesPerSheet, sheets, duplex, instructions, operator, sentAt, error,
                null, null, null);
    }

    public boolean failed() { return "FAILED".equals(outcome); }
}
