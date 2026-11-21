package com.chithira.printhub.jobs;

import com.chithira.printhub.audit.AuditLogRepository;
import com.chithira.printhub.auth.AppUser;
import com.chithira.printhub.auth.AppUserRepository;
import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.Role;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.devices.DeviceRepository;
import com.chithira.printhub.devices.DeviceService;
import com.chithira.printhub.fiery.FieryApiClient;
import com.chithira.printhub.fiery.FieryStatusMapper;
import com.chithira.printhub.fiery.JobStatusPollerTestAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A job from desktop report to counted: Fiery states, removal, counts, corrections, cancel and page access. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JobStatusPollerTestAccess.class)
class PrintJobLifecycleTest {

    @MockitoBean private FieryApiClient fiery;

    @Autowired private MockMvc mvc;
    @Autowired private PrintJobService jobs;
    @Autowired private FieryStatusMapper mapper;
    @Autowired private JobStatusPollerTestAccess poller;
    @Autowired private DeviceService deviceService;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private long deviceId;

    @BeforeEach
    void registerDesktop() {
        String name = "Room-PC-" + UUID.randomUUID().toString().substring(0, 6);
        deviceService.register(name, null, "test");
        deviceId = deviceRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    @Test
    void statusMapReadsTheConfiguredPathAndSpacedKeys() throws Exception {
        var body = json.readTree("{\"data\":{\"item\":{\"state\":\"Done Printing\"}}}");

        assertThat(mapper.rawState(body)).contains("Done Printing");
        assertThat(mapper.map("Done Printing")).contains(PrintJobStatus.PRINTED);
        assertThat(mapper.map("HELD")).contains(PrintJobStatus.HELD);
        assertThat(mapper.map("something new")).isEmpty();
    }

    @Test
    void fieryStatesOnlyMoveTheJobForward() {
        long id = newApiJob();

        assertThat(jobs.applyFieryState(id, "held", PrintJobStatus.HELD)).isTrue();
        assertThat(jobs.applyFieryState(id, "printing", PrintJobStatus.PRINTING)).isTrue();
        assertThat(jobs.applyFieryState(id, "printed", PrintJobStatus.PRINTED)).isTrue();
        assertThat(jobs.applyFieryState(id, "held", PrintJobStatus.HELD)).isFalse();     // no going back after printed
        assertThat(jobs.applyFieryState(id, "odd", null)).isFalse();

        PrintJob job = jobs.get(id);
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.PRINTED);
        assertThat(job.getFieryStateRaw()).isEqualTo("odd");
        assertThat(job.getPrintedAt()).isNotNull();
        assertThat(jobs.history(id)).extracting(JobStatusHistory::getToStatus)
                .containsExactly(PrintJobStatus.SENT, PrintJobStatus.HELD, PrintJobStatus.PRINTING, PrintJobStatus.PRINTED);
    }

    @Test
    void jobIsRemovedOnlyAfterTwoNotFoundAnswersInARow() throws Exception {
        long id = newApiJob();
        when(fiery.getJob(anyString())).thenReturn(new FieryApiClient.JobLookup(false, null));

        assertThat(poller.check(jobs.get(id))).isFalse();
        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.SENT);
        assertThat(poller.check(jobs.get(id))).isTrue();

        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.AWAITING_COUNTS);
        assertThat(jobs.history(id)).extracting(JobStatusHistory::getToStatus)
                .containsExactly(PrintJobStatus.SENT, PrintJobStatus.REMOVED, PrintJobStatus.AWAITING_COUNTS);
        assertThat(jobs.waitingForCounts()).extracting(PrintJob::getId).contains(id);
    }

    @Test
    void pollerReadsTheStateFromTheFieryAnswer() throws Exception {
        long id = newApiJob();
        when(fiery.getJob(anyString())).thenReturn(new FieryApiClient.JobLookup(true,
                json.readTree("{\"data\":{\"kind\":\"job\",\"item\":{\"id\":\"1001\",\"state\":\"printing\"}}}")));

        assertThat(poller.check(jobs.get(id))).isTrue();
        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.PRINTING);
    }

    @Test
    void countsNeedAPrintedJobAndAReasonForWaste() {
        long id = newApiJob();
        assertThatThrownBy(() -> jobs.enterCounts(id, counts(10, 0, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("once the job has printed");

        jobs.markRemovedFromFiery(id);
        assertThatThrownBy(() -> jobs.enterCounts(id, counts(9, 2, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("why sheets were wasted");

        jobs.enterCounts(id, counts(9, 2, "Paper jam"));
        PrintJob counted = jobs.get(id);
        assertThat(counted.getStatus()).isEqualTo(PrintJobStatus.COUNTED);
        assertThat(counted.getTotalSheets()).isEqualTo(11);

        jobs.enterCounts(id, counts(10, 1, "Misprint"));       // correction
        assertThat(jobs.get(id).getPrintedCount()).isEqualTo(10);
        assertThat(auditRepository.findByAction("JOB_COUNTS_CORRECTED")).anySatisfy(row ->
                assertThat(row.getSummary()).contains("was printed 9, waste 2"));
    }

    @Test
    void cancelNeedsAReasonAndClosesTheJob() {
        long id = newApiJob();
        assertThatThrownBy(() -> jobs.cancel(id, " ")).isInstanceOf(BusinessException.class);

        jobs.cancel(id, "Customer cancelled");

        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.CANCELLED);
        assertThatThrownBy(() -> jobs.cancel(id, "again")).isInstanceOf(BusinessException.class);
        assertThat(jobs.applyFieryState(id, "printed", PrintJobStatus.PRINTED)).isFalse();
    }

    @Test
    void printRoomEntersCountsButFrontStaffCannot() throws Exception {
        long id = newApiJob();
        jobs.markRemovedFromFiery(id);
        HubUser printRoom = staff(Role.PRINT_ROOM), frontStaff = staff(Role.FRONT_STAFF);

        mvc.perform(get("/print-room").with(user(printRoom))).andExpect(status().isOk());
        mvc.perform(get("/jobs/" + id).with(user(printRoom))).andExpect(status().isOk());
        mvc.perform(post("/print-room/" + id + "/counts").with(user(frontStaff)).with(csrf())
                .param("printed", "5").param("waste", "0")).andExpect(status().isForbidden());
        mvc.perform(post("/print-room/" + id + "/counts").with(user(printRoom)).with(csrf())
                .param("printed", "5").param("waste", "1").param("wasteReason", "Misprint"))
                .andExpect(status().is3xxRedirection());
        assertThat(jobs.get(id).getPrintedCount()).isEqualTo(5);

        // print room may correct from the job page; front staff may open it and cancel others, not count
        mvc.perform(post("/jobs/" + id + "/counts").with(user(printRoom)).with(csrf())
                .param("printed", "6").param("waste", "0")).andExpect(status().is3xxRedirection());
        assertThat(jobs.get(id).getPrintedCount()).isEqualTo(6);
        mvc.perform(post("/jobs/" + id + "/counts").with(user(frontStaff)).with(csrf())
                .param("printed", "7").param("waste", "0")).andExpect(status().isForbidden());

        long other = newApiJob();
        mvc.perform(post("/jobs/" + other + "/cancel").with(user(frontStaff)).with(csrf()).param("reason", "Duplicate"))
                .andExpect(status().is3xxRedirection());
        assertThat(jobs.get(other).getStatus()).isEqualTo(PrintJobStatus.CANCELLED);
    }

    @Test
    void jobPagesRender() throws Exception {
        long id = newApiJob();
        HubUser admin = staff(Role.ADMIN);

        mvc.perform(get("/jobs").with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/jobs").with(user(admin)).param("q", "card").param("status", "SENT")).andExpect(status().isOk());
        mvc.perform(get("/jobs/" + id).with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/print-room").with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/papers").with(user(admin))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private long newApiJob() {
        JobReport report = new JobReport(UUID.randomUUID().toString(), "SENT", "1001", null, SendMethod.API,
                "card_front.jpg", null, "card_front.jpg", "5598", "13x19 300 GSM", false, null, null, 10, false,
                null, "staff", Instant.now(), null);
        return jobs.recordFromDesktop(deviceId, report).job().getId();
    }

    private static JobCounts counts(int printed, int waste, String reason) {
        JobCounts counts = new JobCounts();
        counts.setPrinted(printed);
        counts.setWaste(waste);
        counts.setWasteReason(reason);
        return counts;
    }

    private HubUser staff(Role role) {
        AppUser account = new AppUser();
        account.setUsername(role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        account.setDisplayName("Test " + role.label());
        account.setRoles(EnumSet.of(role));
        account.setPasswordHash(passwordEncoder.encode("secret-password"));
        return new HubUser(users.save(account));
    }
}
