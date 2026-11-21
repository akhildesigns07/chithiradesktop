package com.chithira.printhub.jobs;

import com.chithira.printhub.audit.AuditLogRepository;
import com.chithira.printhub.devices.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/jobs}: what the desktop reports after each send. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintJobApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private DeviceService deviceService;
    @Autowired private PrintJobRepository jobs;
    @Autowired private JobStatusHistoryRepository history;
    @Autowired private AuditLogRepository auditRepository;

    private String apiKey;

    @BeforeEach
    void registerDesktop() {
        apiKey = deviceService.register("Jobs-PC-" + UUID.randomUUID().toString().substring(0, 6), null, "test");
    }

    @Test
    void newJobIsSavedOnceEvenWhenTheOutboxRetries() throws Exception {
        String uuid = UUID.randomUUID().toString();
        String body = report(uuid, "SENT", "API", "\"1004\"", null);

        report(body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.status").value("SENT"))
                .andExpect(jsonPath("$.duplicate").value(false));
        report(body).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));

        long saved = jobs.findAll().stream().filter(job -> job.getClientJobUuid().equals(uuid)).count();
        assertThat(saved).isEqualTo(1);
        PrintJob job = jobs.findAll().stream().filter(j -> j.getClientJobUuid().equals(uuid)).findFirst().orElseThrow();
        assertThat(job.getFieryJobId()).isEqualTo("1004");
        assertThat(job.getSheetsRequested()).isEqualTo(9);
        assertThat(history.findByJobIdOrderById(job.getId())).hasSize(1);
        assertThat(auditRepository.findByAction("JOB_SENT")).anySatisfy(row ->
                assertThat(row.getEntityId()).isEqualTo(String.valueOf(job.getId())));
    }

    @Test
    void originalFileSizeIsStoredAndShownOnTheJobPage() throws Exception {
        String uuid = UUID.randomUUID().toString();
        String body = report(uuid, "SENT", "API", "\"1009\"", null)
                .replace("\"error\":null", "\"error\":null,\"fileWidthMm\":90.0,\"fileHeightMm\":50.0,\"fileSizeSource\":\"image at its 600 DPI\"");

        report(body).andExpect(status().isCreated());

        PrintJob job = jobs.findAll().stream().filter(j -> j.getClientJobUuid().equals(uuid)).findFirst().orElseThrow();
        assertThat(job.getFileSizeLabel()).isEqualTo("90 × 50 mm (3.54 × 1.97 in)");
        assertThat(job.getFileSizeSource()).isEqualTo("image at its 600 DPI");
    }

    @Test
    void reportWithoutFileSizeStillWorks() throws Exception {
        String uuid = UUID.randomUUID().toString();
        report(report(uuid, "SENT", "API", "\"1010\"", null)).andExpect(status().isCreated());

        PrintJob job = jobs.findAll().stream().filter(j -> j.getClientJobUuid().equals(uuid)).findFirst().orElseThrow();
        assertThat(job.getFileSizeLabel()).isNull();
    }

    @Test
    void driverJobHasNoFieryIdAndWaitsForCounts() throws Exception {
        report(report(UUID.randomUUID().toString(), "SENT", "DRIVER", "null", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.status").value("AWAITING_COUNTS"));
    }

    @Test
    void failedSendIsKeptWithItsError() throws Exception {
        report(report(UUID.randomUUID().toString(), "FAILED", "API", "null", "\"Job rejected: unsupported PDL\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.status").value("FAILED"));
        assertThat(auditRepository.findByAction("JOB_FAILED")).isNotEmpty();
    }

    @Test
    void apiJobWithoutFieryIdOrWithUnknownCustomerIsRefused() throws Exception {
        report(report(UUID.randomUUID().toString(), "SENT", "API", "null", null))
                .andExpect(status().isUnprocessableEntity());
        report(report(UUID.randomUUID().toString(), "SENT", "API", "\"1\"", null).replace("\"customerId\":null", "\"customerId\":999999"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Unknown customerId 999999"));
    }

    @Test
    void invalidReportGetsFieldErrors() throws Exception {
        report("{\"clientJobUuid\":\"nope\",\"outcome\":\"MAYBE\",\"sheets\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.clientJobUuid").exists())
                .andExpect(jsonPath("$.fields.fileName").exists());
    }

    private ResultActions report(String json) throws Exception {
        return mvc.perform(post("/api/v1/jobs").header("X-Api-Key", apiKey).header("X-Operator", "staff")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** A report like the desktop's {@code JobReport.toJson()}; fieryJobId and error are raw JSON values. */
    private static String report(String uuid, String outcome, String method, String fieryJobId, String error) {
        return """
                {"clientJobUuid":"%s","outcome":"%s","fieryJobId":%s,"customerId":null,"sendMethod":"%s",
                 "fileName":"card_front.jpg","backFileName":"card_back.jpg","jobName":"card_front.jpg",
                 "paperId":"5598","paperName":"13x19 300 GSM","imposed":true,"quantity":200,"piecesPerSheet":24,
                 "sheets":9,"duplex":true,"instructions":null,"operator":"staff","sentAt":"2026-11-21T10:00:00Z",
                 "error":%s}""".formatted(uuid, outcome, fieryJobId, method, error == null ? "null" : error);
    }
}
