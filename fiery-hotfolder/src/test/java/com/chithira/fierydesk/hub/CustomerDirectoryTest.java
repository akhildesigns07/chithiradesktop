package com.chithira.fierydesk.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The desktop's customer cache: full load first, then only changes; survives a restart. */
class CustomerDirectoryTest {

    @TempDir
    Path directory;

    @Test
    void firstSyncLoadsAllThenOnlyChangesAreRequested() throws Exception {
        FakeHubClient hub = new FakeHubClient();
        hub.customerPages.add(new HubClient.CustomerPage(List.of(customer(1, "Anu Weddings", true),
                customer(2, "Bindu Studio", true)), "2026-11-21T10:00:00Z"));
        hub.customerPages.add(new HubClient.CustomerPage(List.of(customer(2, "Bindu Studio", false),
                customer(3, "Cafe Coffee", true)), "2026-11-21T10:01:00Z"));
        CustomerDirectory customers = new CustomerDirectory(directory.resolve("customers.json"));

        customers.sync(hub);
        customers.sync(hub);

        assertEquals(List.of("null", "2026-11-21T10:00:00Z"), hub.customerRequests);
        assertEquals(List.of("Anu Weddings", "Cafe Coffee"),
                customers.search("", 10).stream().map(HubCustomer::name).toList());
    }

    @Test
    void savedCopyIsUsedAfterRestart() throws Exception {
        FakeHubClient hub = new FakeHubClient();
        hub.customerPages.add(new HubClient.CustomerPage(List.of(customer(7, "Divya Prints", true)), "2026-11-21T10:00:00Z"));
        Path cache = directory.resolve("customers.json");
        new CustomerDirectory(cache).sync(hub);

        CustomerDirectory afterRestart = new CustomerDirectory(cache);
        afterRestart.loadCache();

        assertEquals(1, afterRestart.size());
        assertEquals("C-0007", afterRestart.search("divya", 5).get(0).code());
    }

    private static HubCustomer customer(long id, String name, boolean active) {
        return new HubCustomer(id, String.format("C-%04d", id), name, null, null, null, active, "2026-11-21T10:00:00Z");
    }
}
