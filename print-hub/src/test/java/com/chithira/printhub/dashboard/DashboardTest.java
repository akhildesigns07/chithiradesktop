package com.chithira.printhub.dashboard;

import com.chithira.printhub.auth.AppUser;
import com.chithira.printhub.auth.AppUserRepository;
import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.Role;
import com.chithira.printhub.customers.Customer;
import com.chithira.printhub.customers.CustomerForm;
import com.chithira.printhub.customers.CustomerService;
import com.chithira.printhub.devices.DeviceRepository;
import com.chithira.printhub.devices.DeviceService;
import com.chithira.printhub.jobs.JobCounts;
import com.chithira.printhub.jobs.JobReport;
import com.chithira.printhub.jobs.PrintJobService;
import com.chithira.printhub.jobs.SendMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dashboard figures on known data, and every dashboard page for each role. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardTest {

    @Autowired private MockMvc mvc;
    @Autowired private DashboardService dashboards;
    @Autowired private PrintJobService jobs;
    @Autowired private CustomerService customers;
    @Autowired private DeviceService deviceService;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private long deviceId;
    private Customer customer;

    @BeforeEach
    void setUp() {
        String name = "Dash-PC-" + UUID.randomUUID().toString().substring(0, 6);
        deviceService.register(name, null, "test");
        deviceId = deviceRepository.findByNameIgnoreCase(name).orElseThrow().getId();
        CustomerForm form = new CustomerForm();
        form.setName("Dashboard Customer " + UUID.randomUUID().toString().substring(0, 4));
        customer = customers.create(form, "WEB");
    }

    @Test
    void figuresCountOnlyCountedJobsForPrintedAndWaste() {
        LocalDate day = LocalDate.of(2026, 3, 10);
        long counted = job(day, "5598", 10);
        jobs.markRemovedFromFiery(counted);
        jobs.enterCounts(counted, counts(9, 1, "Paper jam"));
        long counted2 = job(day.plusDays(1), "5601", 20);
        jobs.markRemovedFromFiery(counted2);
        jobs.enterCounts(counted2, counts(20, 0, null));
        job(day.plusDays(1), "5598", 5);                    // sent, not counted yet

        DashboardData data = dashboards.load(DashboardFilter.of(day, day.plusDays(2), customer.getId(), null, null));

        assertThat(data.totals().jobsSent()).isEqualTo(3);
        assertThat(data.totals().sheetsSent()).isEqualTo(35);
        assertThat(data.totals().countedJobs()).isEqualTo(2);
        assertThat(data.totals().printed()).isEqualTo(29);
        assertThat(data.totals().waste()).isEqualTo(1);
        assertThat(data.totals().wastePercent()).isEqualTo(100.0 / 30);
        assertThat(data.days()).hasSize(3);
        assertThat(data.days().get(0).printed()).isEqualTo(9);
        assertThat(data.days().get(1).sheetsSent()).isEqualTo(25);
        assertThat(data.days().get(2).jobs()).isZero();
        assertThat(data.byPaper()).extracting(DashboardData.Group::key).containsExactly("5601", "5598");
        assertThat(data.wasteByReason()).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("Paper jam");
            assertThat(group.waste()).isEqualTo(1);
        });
        assertThat(data.worstWaste()).singleElement().satisfies(worst -> assertThat(worst.jobId()).isEqualTo(counted));

        DashboardData onePaper = dashboards.load(DashboardFilter.of(day, day.plusDays(2), customer.getId(), "5601", null));
        assertThat(onePaper.totals().printed()).isEqualTo(20);
    }

    @Test
    void filterDefaultsToLast30DaysAndCapsLongRanges() {
        DashboardFilter defaults = DashboardFilter.of(null, null, null, " ", null);
        assertThat(defaults.days()).isEqualTo(30);
        assertThat(defaults.to()).isEqualTo(LocalDate.now());
        assertThat(defaults.paperPcmid()).isNull();

        DashboardFilter reversed = DashboardFilter.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), null, null, null);
        assertThat(reversed.from()).isBefore(reversed.to());
        assertThat(DashboardFilter.of(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 1, 1), null, null, null).days())
                .isEqualTo(DashboardFilter.MAX_DAYS);
    }

    @Test
    void everyDashboardRendersForStaffAndCarriesItsCharts() throws Exception {
        long id = job(LocalDate.now(), "5598", 4);
        jobs.markRemovedFromFiery(id);
        jobs.enterCounts(id, counts(4, 1, "Misprint"));
        HubUser frontStaff = staff(Role.FRONT_STAFF);

        mvc.perform(get("/").with(user(frontStaff))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Printed sheets")))
                .andExpect(content().string(containsString("data-chart=")));
        mvc.perform(get("/dashboards/prints").with(user(frontStaff))).andExpect(status().isOk());
        mvc.perform(get("/dashboards/papers").with(user(frontStaff)).param("paper", "5598")).andExpect(status().isOk());
        mvc.perform(get("/dashboards/waste").with(user(frontStaff))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Misprint")));
        mvc.perform(get("/dashboards/customers").with(user(frontStaff))).andExpect(status().isOk());
        mvc.perform(get("/dashboards/customers").with(user(frontStaff)).param("customerId", String.valueOf(customer.getId())))
                .andExpect(status().isOk()).andExpect(content().string(containsString(customer.getName())));
        mvc.perform(get("/").with(user(frontStaff)).param("from", "2026-01-01").param("to", "2026-01-31")
                .param("deviceId", String.valueOf(deviceId))).andExpect(status().isOk());
    }

    @Test
    void customerRoleWithoutAccessIsKeptOut() throws Exception {
        HubUser customerUser = staff(Role.CUSTOMER);

        mvc.perform(get("/dashboards/waste").with(user(customerUser))).andExpect(status().isForbidden());
        mvc.perform(get("/").with(user(customerUser))).andExpect(status().isOk())
                .andExpect(content().string(containsString("no pages yet")));
    }

    // ------------------------------------------------------------------ helpers

    private long job(LocalDate day, String paperId, int sheets) {
        Instant sentAt = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant();
        JobReport report = new JobReport(UUID.randomUUID().toString(), "SENT", "2001", customer.getId(), SendMethod.API,
                "card.jpg", null, "card.jpg", paperId, "Paper " + paperId, false, null, null, sheets, false,
                null, "akhil", sentAt, null);
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
