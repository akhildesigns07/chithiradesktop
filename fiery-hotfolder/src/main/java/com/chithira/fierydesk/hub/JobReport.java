package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.CustomerRef;
import com.chithira.fierydesk.core.JobProcessor;
import com.chithira.fierydesk.core.JobRequest;
import com.chithira.fierydesk.core.SheetJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the desktop tells the hub about one job ({@code POST /api/v1/jobs}).
 * The {@code clientJobUuid} is made once here and kept in the outbox file, so a retry
 * after a network failure never creates the job twice in the hub.
 *
 * @param outcome    SENT (accepted by the Fiery or the printer driver) or FAILED
 * @param sendMethod API, DRIVER_API or DRIVER
 * @param sheets     copies the Fiery / printer was asked for (sheets when imposed)
 */
public record JobReport(String clientJobUuid, String outcome, String fieryJobId, CustomerRef customer,
                        String sendMethod, String fileName, String backFileName, String jobName,
                        String paperId, String paperName, boolean imposed, Integer quantity, Integer piecesPerSheet,
                        int sheets, boolean duplex, String instructions, String operator, Instant sentAt, String error) {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    /** A job the Fiery API accepted. */
    public static JobReport apiSent(JobRequest request, JobProcessor.Result result) {
        return fromRequest(request, SENT, result.jobId(), result.copiesSent(), result.piecesPerSheet(), null);
    }

    /** A job the Fiery API (or the conversion before it) rejected. */
    public static JobReport apiFailed(JobRequest request, String error) {
        return fromRequest(request, FAILED, null, request.copies(), null, error);
    }

    /**
     * A file printed through the Windows printer driver (no Fiery job id).
     *
     * @param sheet the imposed / front-back sheet job, or null for a plain file
     */
    public static JobReport driver(Path front, SheetJob sheet, CustomerRef customer, String paperName, int copies,
                                   Integer piecesPerSheet, boolean ok, String error) {
        boolean imposed = sheet != null && sheet.impose() != null;
        return new JobReport(newUuid(), ok ? SENT : FAILED, null, customer, "DRIVER", name(front),
                sheet == null || sheet.back() == null ? null : name(sheet.back()), name(front), null, paperName,
                imposed, imposed ? sheet.impose().quantity() : null, piecesPerSheet, copies,
                sheet != null && sheet.duplex(), null, currentWindowsUser(), Instant.now(), ok ? null : error);
    }

    private static JobReport fromRequest(JobRequest request, String outcome, String fieryJobId, int sheets,
                                         Integer piecesPerSheet, String error) {
        SheetJob sheet = request.sheet();
        boolean imposed = sheet != null && sheet.impose() != null;
        return new JobReport(newUuid(), outcome, fieryJobId, request.customer(),
                request.converter() == null ? "API" : "DRIVER_API",
                name(request.file()), request.back() == null ? null : name(request.back()), request.jobName(),
                request.paper() == null ? null : request.paper().id(), request.paper() == null ? null : request.paper().name(),
                imposed, imposed ? sheet.impose().quantity() : null, piecesPerSheet, sheets,
                sheet != null && sheet.duplex(), blankToNull(request.instructions()), currentWindowsUser(), Instant.now(), error);
    }

    /** The JSON body the hub expects. */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("clientJobUuid", clientJobUuid);
        json.put("outcome", outcome);
        json.put("fieryJobId", fieryJobId);
        json.put("customerId", customer == null ? null : customer.id());
        json.put("sendMethod", sendMethod);
        json.put("fileName", fileName);
        json.put("backFileName", backFileName);
        json.put("jobName", jobName);
        json.put("paperId", paperId);
        json.put("paperName", paperName);
        json.put("imposed", imposed);
        json.put("quantity", quantity);
        json.put("piecesPerSheet", piecesPerSheet);
        json.put("sheets", sheets);
        json.put("duplex", duplex);
        json.put("instructions", instructions);
        json.put("operator", operator);
        json.put("sentAt", sentAt.toString());
        json.put("error", error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error);
        return JsonWriter.write(json);
    }

    private static String newUuid() { return UUID.randomUUID().toString(); }

    private static String currentWindowsUser() { return System.getProperty("user.name", ""); }

    private static String name(Path file) { return file.getFileName().toString(); }

    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text; }
}
