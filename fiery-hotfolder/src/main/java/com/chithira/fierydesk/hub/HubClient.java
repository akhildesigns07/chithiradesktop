package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.AppConfig.HubSettings;
import com.chithira.fierydesk.fiery.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Print Hub API ({@code /api/v1}) with this desktop's API key.
 * Every request also sends the Windows user (X-Operator) so the hub's audit log shows who was at the PC.
 */
public class HubClient {

    /** A page of customers and the server time to pass as {@code updatedSince} next time. */
    public record CustomerPage(List<HubCustomer> items, String serverTime) {}

    /** Result of a quick add: the new customer, or the existing one with the same phone number. */
    public record CreatedCustomer(HubCustomer customer, boolean alreadyExisted, String message) {}

    public static final String APP_VERSION = "fiery-hotfolder/1.0.0";

    private final HubSettings settings;
    private final String operator;
    private final HttpClient http;

    public HubClient(HubSettings settings) {
        this.settings = settings;
        this.operator = System.getProperty("user.name", "");
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    /** Checks the address and key; returns the name the hub knows this desktop by. */
    public String ping() throws IOException, InterruptedException {
        Map<?, ?> body = object(send(request("/api/v1/ping").GET()));
        return JsonParser.text(body, "device");
    }

    /**
     * All active customers ({@code updatedSince} null), or only those changed since that server time
     * (including deactivated ones, so the cache can drop them).
     */
    public CustomerPage customers(String updatedSince) throws IOException, InterruptedException {
        String path = "/api/v1/customers" + (updatedSince == null ? ""
                : "?updatedSince=" + URLEncoder.encode(updatedSince, StandardCharsets.UTF_8));
        Map<?, ?> body = object(send(request(path).GET()));
        List<HubCustomer> items = new ArrayList<>();
        if (body.get("items") instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) items.add(HubCustomer.fromJson(map));
        }
        return new CustomerPage(items, JsonParser.text(body, "serverTime"));
    }

    /** Quick add from the desktop. A customer with the same phone is returned instead of a duplicate. */
    public CreatedCustomer createCustomer(String name, String phone, String email, String company)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();     // allows null for fields left empty
        fields.put("name", name);
        fields.put("phone", phone);
        fields.put("email", email);
        fields.put("company", company);
        String json = JsonWriter.write(fields);
        HttpResponse<String> response = http.send(request("/api/v1/customers")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409 || response.statusCode() == 201 || response.statusCode() == 200) {
            Map<?, ?> body = object(response.body());
            HubCustomer customer = HubCustomer.fromJson((Map<?, ?>) body.get("item"));
            return new CreatedCustomer(customer, response.statusCode() == 409, JsonParser.text(body, "error"));
        }
        throw failure(response);
    }

    /** Reports a job (JSON made by {@link JobReport#toJson()}); a repeat of the same job is accepted as a no-op. */
    public void reportJob(String json) throws IOException, InterruptedException {
        send(request("/api/v1/jobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    // ------------------------------------------------------------------ plumbing

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(settings.url(path)))
                .timeout(settings.timeout())
                .header("X-Api-Key", settings.apiKey())
                .header("X-Operator", operator)
                .header("X-App-Version", APP_VERSION)
                .header("Accept", "application/json");
    }

    private String send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new HubException(0, "Print Hub not reachable at " + settings.baseUrl() + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", null);
        }
        if (response.statusCode() / 100 != 2) throw failure(response);
        return response.body();
    }

    private static HubException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = "";
        try {
            if (JsonParser.parse(response.body()) instanceof Map<?, ?> map) detail = JsonParser.text(map, "error");
        } catch (RuntimeException notJson) {
            // body is not JSON (e.g. a proxy error page)
        }
        String message = switch (status) {
            case 401 -> "Print Hub rejected the API key (wrong or revoked). Check hub.api-key.";
            case 403 -> "This API key may not do that.";
            default -> "Print Hub answered " + status + (detail.isBlank() ? "" : ": " + detail);
        };
        return new HubException(status, message, response.body());
    }

    private static Map<?, ?> object(String json) throws HubException {
        try {
            if (JsonParser.parse(json) instanceof Map<?, ?> map) return map;
        } catch (RuntimeException e) {
            // fall through
        }
        throw new HubException(200, "Print Hub sent an unexpected answer", json);
    }
}
