package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.AppConfig;
import com.chithira.fierydesk.core.AppConfig.HubSettings;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Everything the windows need from the Print Hub: the customer list, quick add, job reports and a status line.
 * A background thread syncs customers and delivers waiting job reports every minute (and right after each job).
 * Nothing here ever blocks or fails a print: reports wait in the {@link Outbox} until the hub is back.
 */
public class HubConnection {

    /** Connection state shown in the main window. */
    public enum State { OFF, CONNECTING, CONNECTED, OFFLINE, KEY_REJECTED }

    /** State plus a short text for staff. */
    public record Status(State state, String text) {}

    private static final long SYNC_INTERVAL_SECONDS = 60;

    private final CustomerDirectory directory = new CustomerDirectory(AppConfig.DIR.resolve("customers.json"));
    private final Outbox outbox = new Outbox(AppConfig.DIR.resolve("outbox"));
    private final List<Consumer<Status>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> customerListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "print-hub");
        thread.setDaemon(true);
        return thread;
    });

    private volatile HubSettings settings;
    private volatile HubClient client;
    private volatile Status status = new Status(State.OFF, "Print Hub: off");

    public HubConnection(HubSettings settings) {
        applySettings(settings);
    }

    /** Loads the saved customer list and starts the minute-by-minute sync. */
    public void start() {
        directory.loadCache();
        fireCustomersChanged();
        worker.scheduleWithFixedDelay(this::syncNow, 0, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** After "Reload config": new address or key take effect immediately. */
    public void reconfigure(HubSettings newSettings) {
        applySettings(newSettings);
        worker.execute(this::syncNow);
    }

    public boolean isEnabled() { return settings.enabled(); }

    /** True when staff must pick a customer before sending. */
    public boolean isCustomerRequired() { return settings.isConfigured() && settings.requireCustomer(); }

    public Status status() { return status; }

    public CustomerDirectory customers() { return directory; }

    public int waitingReports() { return outbox.waiting(); }

    /** Queues a job report and tries to deliver it straight away (in the background). */
    public void report(JobReport report) {
        if (!settings.isConfigured()) return;
        try {
            outbox.add(report);
        } catch (IOException e) {
            updateStatus(new Status(State.OFFLINE, "Print Hub: could not save job report – " + e.getMessage()));
            return;
        }
        worker.execute(this::deliverReports);
    }

    /**
     * Quick add (call from a background thread). Returns the existing customer when the phone number is
     * already used.
     */
    public HubClient.CreatedCustomer createCustomer(String name, String phone, String email, String company)
            throws IOException, InterruptedException {
        HubClient current = requireClient();
        HubClient.CreatedCustomer created = current.createCustomer(name, phone, email, company);
        directory.put(created.customer());
        fireCustomersChanged();
        return created;
    }

    /** "Test hub connection" (call from a background thread): the name the hub knows this desktop by. */
    public String test() throws IOException, InterruptedException {
        String deviceName = requireClient().ping();
        syncNow();
        return deviceName;
    }

    public void addStatusListener(Consumer<Status> listener) { statusListeners.add(listener); }

    public void addCustomerListener(Runnable listener) { customerListeners.add(listener); }

    public void stop() { worker.shutdownNow(); }

    // ------------------------------------------------------------------ background work

    private void syncNow() {
        HubClient current = client;
        if (current == null) return;
        try {
            int changed = directory.sync(current);
            if (changed > 0) fireCustomersChanged();
            deliverReports();
        } catch (HubException e) {
            updateStatus(e.isKeyProblem() ? new Status(State.KEY_REJECTED, "Print Hub: " + e.getMessage())
                    : new Status(State.OFFLINE, offlineText(e)));
        } catch (IOException e) {
            updateStatus(new Status(State.OFFLINE, offlineText(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            updateStatus(new Status(State.OFFLINE, "Print Hub: " + e));
        }
    }

    private void deliverReports() {
        HubClient current = client;
        if (current == null) return;
        try {
            Outbox.FlushResult result = outbox.deliver(current);
            if (result.stoppedBy() != null) {
                HubException problem = result.stoppedBy();
                updateStatus(problem.isKeyProblem() ? new Status(State.KEY_REJECTED, "Print Hub: " + problem.getMessage())
                        : new Status(State.OFFLINE, offlineText(problem)));
                return;
            }
            String rejected = result.rejected() == 0 ? ""
                    : " · ⚠ " + result.rejected() + " job report(s) refused – see " + AppConfig.DIR.resolve("outbox/rejected");
            updateStatus(new Status(State.CONNECTED, "Print Hub: connected · " + directory.size() + " customers"
                    + (result.waiting() == 0 ? "" : " · " + result.waiting() + " job report(s) waiting") + rejected));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String offlineText(IOException e) {
        int waiting = outbox.waiting();
        return "Print Hub: offline – " + e.getMessage()
                + (waiting == 0 ? "" : " · " + waiting + " job report(s) waiting, sent automatically when it is back");
    }

    private void applySettings(HubSettings newSettings) {
        this.settings = newSettings;
        this.client = newSettings.isConfigured() ? new HubClient(newSettings) : null;
        if (!newSettings.enabled()) updateStatus(new Status(State.OFF, "Print Hub: off (hub.enabled=false)"));
        else if (client == null) updateStatus(new Status(State.OFFLINE, "Print Hub: hub.base-url or hub.api-key missing"));
        else updateStatus(new Status(State.CONNECTING, "Print Hub: connecting to " + newSettings.baseUrl() + " …"));
    }

    private HubClient requireClient() throws HubException {
        HubClient current = client;
        if (current == null) throw new HubException(0, "Print Hub is not set up (hub.enabled, hub.base-url, hub.api-key)", null);
        return current;
    }

    private void updateStatus(Status newStatus) {
        status = newStatus;
        statusListeners.forEach(listener -> listener.accept(newStatus));
    }

    private void fireCustomersChanged() { customerListeners.forEach(Runnable::run); }
}
