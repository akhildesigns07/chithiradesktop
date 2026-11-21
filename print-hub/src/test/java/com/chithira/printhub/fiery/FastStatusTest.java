package com.chithira.printhub.fiery;

import com.chithira.printhub.auth.AppUser;
import com.chithira.printhub.auth.AppUserRepository;
import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.Role;
import com.chithira.printhub.devices.DeviceRepository;
import com.chithira.printhub.devices.DeviceService;
import com.chithira.printhub.jobs.JobReport;
import com.chithira.printhub.jobs.PrintJobService;
import com.chithira.printhub.jobs.PrintJobStatus;
import com.chithira.printhub.jobs.SendMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Status changes reach the hub within seconds: quick check of new jobs, quick re-check, Check now, live endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FastStatusTest {

    @MockitoBean private FieryApiClient fiery;

    @Autowired private MockMvc mvc;
    @Autowired private FieryProperties properties;
    @Autowired private PrintJobService jobs;
    @Autowired private JobStatusPoller poller;
    @Autowired private DeviceService deviceService;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private long deviceId;

    @BeforeEach
    void connectFiery() {
        properties.setEnabled(true);
        properties.setApiKey("key");
        properties.setUsername("user");
        properties.setPassword("password");
        String name = "Fast-PC-" + UUID.randomUUID().toString().substring(0, 6);
        deviceService.register(name, null, "test");
        deviceId = deviceRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    @AfterEach
    void disconnectFiery() { properties.setEnabled(false); }

    @Test
    void newJobIsCheckedWithinSecondsOfBeingReported() throws Exception {
        when(fiery.getJob(anyString())).thenReturn(found("held"));

        long id = newJob();

        assertThat(waitUntil(() -> jobs.get(id).getStatus() == PrintJobStatus.HELD, 6000)).isTrue();
    }

    @Test
    void firstNotFoundIsConfirmedQuicklyInsteadOfAfterAFullInterval() throws Exception {
        when(fiery.getJob(anyString())).thenReturn(found("held"));
        long id = newJob();
        assertThat(waitUntil(() -> jobs.get(id).getStatus() == PrintJobStatus.HELD, 6000)).isTrue();

        when(fiery.getJob(anyString())).thenReturn(new FieryApiClient.JobLookup(false, null));
        JobStatusPoller.CheckResult first = poller.checkNow(id);

        assertThat(first.message()).contains("checking again");
        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.HELD);
        assertThat(waitUntil(() -> jobs.get(id).getStatus() == PrintJobStatus.AWAITING_COUNTS, 8000)).isTrue();
    }

    @Test
    void checkNowButtonAsksTheFieryImmediately() throws Exception {
        when(fiery.getJob(anyString())).thenReturn(found("printing"));
        long id = newJob();
        HubUser printRoom = staff(Role.PRINT_ROOM);

        mvc.perform(post("/jobs/" + id + "/check").with(user(printRoom)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("ok", org.hamcrest.Matchers.containsString("Fiery says: printing")));
        assertThat(jobs.get(id).getStatus()).isEqualTo(PrintJobStatus.PRINTING);
    }

    @Test
    void liveEndpointReportsStatusesForStaffOnly() throws Exception {
        when(fiery.getJob(anyString())).thenReturn(found("held"));
        long id = newJob();
        assertThat(waitUntil(() -> jobs.get(id).getStatus() == PrintJobStatus.HELD, 6000)).isTrue();

        mvc.perform(get("/jobs/live").param("ids", id + ",abc,").with(user(staff(Role.PRINT_ROOM))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses['" + id + "']").value("HELD"))
                .andExpect(jsonPath("$.openJobs").isNumber())
                .andExpect(jsonPath("$.latestJobId").value(org.hamcrest.Matchers.greaterThanOrEqualTo((int) id)));
        mvc.perform(get("/jobs/live").param("ids", String.valueOf(id)).with(user(staff(Role.CUSTOMER))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private long newJob() {
        JobReport report = new JobReport(UUID.randomUUID().toString(), "SENT", "3001", null, SendMethod.API,
                "fast.jpg", null, "fast.jpg", "5598", "13x19", false, null, null, 2, false, null, "staff", Instant.now(), null);
        return jobs.recordFromDesktop(deviceId, report).job().getId();
    }

    private FieryApiClient.JobLookup found(String state) throws Exception {
        return new FieryApiClient.JobLookup(true, json.readTree("{\"data\":{\"item\":{\"state\":\"" + state + "\"}}}"));
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
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
