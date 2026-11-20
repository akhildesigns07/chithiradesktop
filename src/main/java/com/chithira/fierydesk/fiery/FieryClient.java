package com.chithira.fierydesk.fiery;

import com.chithira.fierydesk.core.AppConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Fiery API v5 client using only the JDK HTTP client.
 *
 * Login is sent exactly like the working Postman request: username/password
 * headers plus a JSON body with apikey, username and password.
 * The session cookie from /login is kept and sent on every call; on 401 the
 * client logs in again and retries once.
 */
public final class FieryClient {

    public record UploadResult(String jobId, String rawResponse) {}

    private final AppConfig.FieryApi api;
    private final HttpClient http;

    private volatile boolean loggedIn;
    private volatile String cookieHeader;

    /** Settings from the external fiery-hotfolder.properties file. */
    public FieryClient(AppConfig.FieryApi api) {
        this.api = api;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(api.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1);
        if (api.trustSelfSigned()) b.sslContext(trustAllContext());
        this.http = b.build();
    }

    /** Convenience: default API paths (/live/api/v5/...) and timeouts. */
    public FieryClient(String baseUrl, String apiKey, String username, String password, boolean trustSelfSigned) {
        this(new AppConfig.FieryApi(baseUrl, "/live/api/v5", "/login", "/logout", "/jobs", "/papercatalog",
                apiKey, username, password, trustSelfSigned,
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(300)));
    }

    // ------------------------------------------------------------- session

    public synchronized void login() throws IOException, InterruptedException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("apikey", api.apiKey());
        body.put("username", api.username());
        body.put("password", api.password());

        HttpRequest req = HttpRequest.newBuilder(URI.create(api.url(api.loginPath())))
                .timeout(api.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("username", api.username())
                .header("password", api.password())
                .POST(HttpRequest.BodyPublishers.ofString(Json.object(body)))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            loggedIn = false;
            throw new FieryException("Fiery login failed", res.statusCode(), res.body());
        }
        List<String> setCookies = res.headers().allValues("set-cookie");
        cookieHeader = setCookies.stream()
                .map(c -> c.split(";", 2)[0].trim())
                .filter(c -> !c.isEmpty())
                .collect(Collectors.joining("; "));
        if (cookieHeader.isEmpty()) cookieHeader = null;
        loggedIn = true;
    }

    public synchronized void logout() {
        if (!loggedIn) return;
        try {
            http.send(withCookie(HttpRequest.newBuilder(URI.create(api.url(api.logoutPath())))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody())).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // best effort
        } finally {
            loggedIn = false;
            cookieHeader = null;
        }
    }

    // ---------------------------------------------------------------- jobs

    /** Uploads a print-ready file (PDF / PostScript / TIFF) and returns the Fiery job id. */
    public UploadResult uploadJob(byte[] content, String filename, String contentType, JobOptions o)
            throws IOException, InterruptedException {

        Multipart mp = new Multipart()
                .file("file", filename, contentType, content)
                .text("queue", o.queue())
                .text("preset", o.preset())
                .text("pcmid", o.pcmid());
        if (!o.attributes().isEmpty()) mp.text("attributes", Json.object(o.attributes()));
        byte[] body = mp.build();
        String type = mp.contentType();

        HttpResponse<String> res = sendWithSession(() -> HttpRequest.newBuilder(URI.create(api.url(api.jobsPath())))
                .timeout(api.uploadTimeout())
                .header("Content-Type", type)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));

        if (res.statusCode() / 100 != 2) {
            throw new FieryException("Fiery rejected the job", res.statusCode(), res.body());
        }
        return new UploadResult(Json.firstId(res.body()), res.body());
    }

    /** GET /papercatalog: raw JSON with all Paper Catalog entries. */
    public String getPaperCatalogJson() throws IOException, InterruptedException {
        HttpResponse<String> res = sendWithSession(() -> HttpRequest.newBuilder(URI.create(api.url(api.paperCatalogPath())))
                .timeout(api.requestTimeout())
                .header("Accept", "application/json")
                .GET());
        if (res.statusCode() / 100 != 2) throw new FieryException("Could not read the paper catalog", res.statusCode(), res.body());
        return res.body();
    }

    /** GET /jobs/{id}: raw JSON, handy for discovering attribute keys. */
    public String getJob(String jobId) throws IOException, InterruptedException {
        HttpResponse<String> res = sendWithSession(() -> HttpRequest.newBuilder(URI.create(api.url(api.jobsPath()) + "/" + jobId))
                .timeout(api.requestTimeout())
                .header("Accept", "application/json")
                .GET());
        if (res.statusCode() / 100 != 2) throw new FieryException("Could not read job " + jobId, res.statusCode(), res.body());
        return res.body();
    }

    // -------------------------------------------------------------- helpers

    private HttpResponse<String> sendWithSession(Supplier<HttpRequest.Builder> request)
            throws IOException, InterruptedException {
        if (!loggedIn) login();
        HttpResponse<String> res = http.send(withCookie(request.get()).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) {          // session expired
            login();
            res = http.send(withCookie(request.get()).build(), HttpResponse.BodyHandlers.ofString());
        }
        return res;
    }

    private HttpRequest.Builder withCookie(HttpRequest.Builder b) {
        String c = cookieHeader;
        return c == null ? b : b.header("Cookie", c);
    }

    private static SSLContext trustAllContext() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create TLS context", e);
        }
    }
}
