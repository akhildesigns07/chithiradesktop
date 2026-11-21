package com.chithira.printhub.fiery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fiery API calls the hub needs: login (session cookie), {@code GET /jobs/{id}} and {@code GET /papercatalog}.
 * A 401 means the session expired: the client logs in again once and repeats the call.
 */
@Component
public class FieryApiClient {

    /** Answer of {@code GET /jobs/{id}}: the job JSON, or {@code found=false} when the Fiery has no such job (404). */
    public record JobLookup(boolean found, JsonNode body) {}

    private final FieryProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private volatile String sessionCookie;

    public FieryApiClient(FieryProperties properties) {
        this.properties = properties;
        this.http = createHttpClient(properties);
    }

    public JobLookup getJob(String fieryJobId) throws IOException, InterruptedException {
        HttpResponse<String> response = sendWithSession("/jobs/" + URLEncoder.encode(fieryJobId, StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return new JobLookup(false, null);
        requireSuccess(response, "Could not read Fiery job " + fieryJobId);
        return new JobLookup(true, json.readTree(response.body()));
    }

    public JsonNode getPaperCatalog() throws IOException, InterruptedException {
        HttpResponse<String> response = sendWithSession("/papercatalog");
        requireSuccess(response, "Could not read the paper catalog");
        return json.readTree(response.body());
    }

    // ------------------------------------------------------------------ session

    private synchronized void login() throws IOException, InterruptedException {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("apikey", properties.getApiKey());
        credentials.put("username", properties.getUsername());
        credentials.put("password", properties.getPassword());
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url("/login")))
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(credentials)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            sessionCookie = null;
            throw new FieryCallException("Fiery login failed (" + response.statusCode() + ")", response.statusCode());
        }
        List<String> cookies = response.headers().allValues("set-cookie");
        String cookie = cookies.stream().map(c -> c.split(";", 2)[0].trim()).filter(c -> !c.isEmpty())
                .collect(Collectors.joining("; "));
        sessionCookie = cookie.isEmpty() ? "" : cookie;
    }

    private HttpResponse<String> sendWithSession(String path) throws IOException, InterruptedException {
        if (sessionCookie == null) login();
        HttpResponse<String> response = http.send(get(path), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {          // session expired
            login();
            response = http.send(get(path), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private HttpRequest get(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.url(path)))
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .GET();
        String cookie = sessionCookie;
        if (cookie != null && !cookie.isEmpty()) builder.header("Cookie", cookie);
        return builder.build();
    }

    private static void requireSuccess(HttpResponse<String> response, String what) throws FieryCallException {
        if (response.statusCode() / 100 != 2) throw new FieryCallException(what + " (" + response.statusCode() + ")", response.statusCode());
    }

    private static HttpClient createHttpClient(FieryProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        if (properties.isTrustSelfSignedCertificate()) builder.sslContext(trustAllContext());
        return builder.build();
    }

    /** Accepts the Fiery's self-signed certificate (shop network only; see trust-self-signed-certificate). */
    private static SSLContext trustAllContext() {
        try {
            TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot create SSL context", e);
        }
    }

    /** A Fiery call failed with an HTTP status. */
    public static class FieryCallException extends IOException {
        private final int status;

        public FieryCallException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }
}
