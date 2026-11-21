package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.fiery.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The desktop's copy of the hub's active customers, so the picker works instantly and while the hub is offline.
 * The first sync loads everything; later syncs ask only for changes since the last server time.
 * Saved in {@code <user home>/.chithira-fiery/customers.json}.
 */
public class CustomerDirectory {

    private final Path cacheFile;
    private final Map<Long, HubCustomer> customersById = new LinkedHashMap<>();
    private String lastServerTime;

    public CustomerDirectory(Path cacheFile) { this.cacheFile = cacheFile; }

    /** Loads the saved copy (if any). */
    public synchronized void loadCache() {
        if (!Files.exists(cacheFile)) return;
        try {
            if (JsonParser.parse(Files.readString(cacheFile, StandardCharsets.UTF_8)) instanceof Map<?, ?> saved) {
                customersById.clear();
                if (saved.get("items") instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> map) {
                            HubCustomer customer = HubCustomer.fromJson(map);
                            customersById.put(customer.id(), customer);
                        }
                    }
                }
                String serverTime = JsonParser.text(saved, "serverTime");
                lastServerTime = serverTime.isBlank() ? null : serverTime;
            }
        } catch (IOException | RuntimeException e) {
            customersById.clear();          // unreadable cache: the next sync loads everything again
            lastServerTime = null;
        }
    }

    /**
     * Brings the copy up to date.
     *
     * @return how many customers were added, changed or removed
     */
    public int sync(HubClient client) throws IOException, InterruptedException {
        String since;
        synchronized (this) { since = lastServerTime; }
        HubClient.CustomerPage page = client.customers(since);
        synchronized (this) {
            if (since == null) customersById.clear();
            for (HubCustomer customer : page.items()) {
                if (customer.active()) customersById.put(customer.id(), customer);
                else customersById.remove(customer.id());
            }
            if (!page.serverTime().isBlank()) lastServerTime = page.serverTime();
            saveCache();
            return page.items().size();
        }
    }

    /** Adds a customer just created from this desktop, without waiting for the next sync. */
    public synchronized void put(HubCustomer customer) {
        customersById.put(customer.id(), customer);
        saveCache();
    }

    /** Customers matching the search text (all words), sorted by name. */
    public synchronized List<HubCustomer> search(String text, int limit) {
        return customersById.values().stream()
                .filter(customer -> customer.matches(text))
                .sorted(Comparator.comparing(HubCustomer::name, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    public synchronized int size() { return customersById.size(); }

    private void saveCache() {
        List<Object> items = new ArrayList<>();
        customersById.values().forEach(customer -> items.add(customer.toJson()));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("serverTime", lastServerTime);
        json.put("items", items);
        try {
            Files.createDirectories(cacheFile.getParent());
            Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(temporary, JsonWriter.write(json), StandardCharsets.UTF_8);
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // the copy in memory still works; it is saved again on the next change
        }
    }
}
