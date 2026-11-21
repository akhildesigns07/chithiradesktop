package com.chithira.printhub.web;

import com.chithira.printhub.audit.AuditLog;
import com.chithira.printhub.audit.AuditLogRepository;
import com.chithira.printhub.auth.AppUser;
import com.chithira.printhub.auth.AppUserRepository;
import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.Role;
import com.chithira.printhub.devices.DeviceRepository;
import com.chithira.printhub.devices.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end checks of logins, roles, desktop API keys, customers and the audit trail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintHubIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AppUserRepository users;
    @Autowired private DeviceService deviceService;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void firstStartCreatesAdminWithTemporaryPassword() {
        AppUser admin = users.findByUsernameIgnoreCase("admin").orElseThrow();

        assertThat(admin.getRoles()).containsExactly(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
    }

    @Test
    void apiWithoutKeyIsRejected() throws Exception {
        mvc.perform(get("/api/v1/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ping").header("X-Api-Key", "chp_aaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void desktopCreatesCustomerAndTheAuditNamesDesktopAndOperator() throws Exception {
        String deviceName = "Front-PC-" + UUID.randomUUID().toString().substring(0, 6);
        String apiKey = deviceService.register(deviceName, "Counter", "test");
        String phone = "98" + (System.nanoTime() % 100_000_000L);

        mvc.perform(get("/api/v1/ping").header("X-Api-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device").value(deviceName));

        mvc.perform(post("/api/v1/customers").header("X-Api-Key", apiKey).header("X-Operator", "akhil")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anu Weddings\",\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item.code").value(org.hamcrest.Matchers.startsWith("C-")));

        // same phone again: the existing customer is returned with 409
        mvc.perform(post("/api/v1/customers").header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anu W\",\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.item.name").value("Anu Weddings"));

        List<AuditLog> created = auditRepository.findByAction("CUSTOMER_CREATED");
        assertThat(created).anySatisfy(row -> {
            assertThat(row.getActorType()).isEqualTo("DEVICE");
            assertThat(row.getActorName()).isEqualTo(deviceName + " (akhil)");
        });
    }

    @Test
    void invalidCustomerFromDesktopGetsFieldErrors() throws Exception {
        String apiKey = deviceService.register("Front-PC-" + UUID.randomUUID().toString().substring(0, 6), null, "test");

        mvc.perform(post("/api/v1/customers").header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void revokedKeyStopsWorking() throws Exception {
        String deviceName = "Old-PC-" + UUID.randomUUID().toString().substring(0, 6);
        String apiKey = deviceService.register(deviceName, null, "test");
        long deviceId = deviceRepository.findByNameIgnoreCase(deviceName).orElseThrow().getId();

        deviceService.revoke(deviceId);

        mvc.perform(get("/api/v1/ping").header("X-Api-Key", apiKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void printRoomCanViewCustomersButNotEdit() throws Exception {
        HubUser printRoom = staff(Role.PRINT_ROOM);

        mvc.perform(get("/customers").with(user(printRoom))).andExpect(status().isOk());
        mvc.perform(get("/customers/new").with(user(printRoom))).andExpect(status().isForbidden());
        mvc.perform(post("/customers/new").with(user(printRoom)).with(csrf()).param("name", "X"))
                .andExpect(status().isForbidden());
    }

    @Test
    void frontStaffCanCreateCustomersButNotOpenAdminPages() throws Exception {
        HubUser frontStaff = staff(Role.FRONT_STAFF);

        mvc.perform(get("/customers/new").with(user(frontStaff))).andExpect(status().isOk());
        mvc.perform(post("/customers/new").with(user(frontStaff)).with(csrf()).param("name", "Walk-in customer"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/users").with(user(frontStaff))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/devices").with(user(frontStaff))).andExpect(status().isForbidden());
    }

    @Test
    void adminChangesPageAccessAndItIsAudited() throws Exception {
        HubUser admin = staff(Role.ADMIN);
        HubUser printRoom = staff(Role.PRINT_ROOM);

        // only "view customers" for Front staff ticked: Print room loses customer view
        mvc.perform(post("/admin/access").with(user(admin)).with(csrf()).param("box", "V:FRONT_STAFF:CUSTOMERS"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/customers").with(user(printRoom))).andExpect(status().isForbidden());
        assertThat(auditRepository.findByAction("PAGE_ACCESS_CHANGED")).isNotEmpty();

        // restore the defaults for the other tests
        mvc.perform(post("/admin/access").with(user(admin)).with(csrf())
                        .param("box", "V:FRONT_STAFF:OVERALL_DASHBOARD", "V:PRINT_ROOM:OVERALL_DASHBOARD",
                                "E:FRONT_STAFF:CUSTOMERS", "V:PRINT_ROOM:CUSTOMERS",
                                "E:FRONT_STAFF:PRINT_JOBS", "E:PRINT_ROOM:PRINT_JOBS", "E:PRINT_ROOM:PRINT_ROOM",
                                "V:FRONT_STAFF:WASTE_DASHBOARD", "V:PRINT_ROOM:WASTE_DASHBOARD",
                                "V:FRONT_STAFF:PAPERS", "E:PRINT_ROOM:PAPERS",
                                "V:FRONT_STAFF:PRINT_DASHBOARD", "V:PRINT_ROOM:PRINT_DASHBOARD",
                                "V:FRONT_STAFF:PAPER_DASHBOARD", "V:PRINT_ROOM:PAPER_DASHBOARD",
                                "V:FRONT_STAFF:CUSTOMER_DASHBOARD", "V:PRINT_ROOM:CUSTOMER_DASHBOARD"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/customers").with(user(printRoom))).andExpect(status().isOk());
    }

    @Test
    void loginPageIsPublicAndOtherPagesNeedLogin() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/customers")).andExpect(status().is3xxRedirection());
    }

    /** A saved staff account with the given role, as the logged-in principal. */
    private HubUser staff(Role role) {
        AppUser account = new AppUser();
        account.setUsername(role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        account.setDisplayName("Test " + role.label());
        account.setRoles(EnumSet.of(role));
        account.setPasswordHash(passwordEncoder.encode("secret-password"));
        return new HubUser(users.save(account));
    }
}
