package com.chithira.mockfiery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Fiery API v5 as the hot folder app uses it:
 *   POST /live/api/v5/login, POST /logout, GET /papercatalog,
 *   POST /jobs (multipart upload), GET /jobs, GET /jobs/{id}
 */
final class FieryApi implements HttpHandler {

    static final String BASE = "/live/api/v5";
    private static final String COOKIE = "fierysession";

    private final MockState s;

    FieryApi(MockState s) { this.s = s; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String sub = path.substring(BASE.length());
        try {
            if (s.slowSeconds > 0) Thread.sleep(s.slowSeconds * 1000L);
            if (s.offline) { reply(ex, 503, error("Fiery is offline (test switch)"), method, path, "offline switch"); return; }
            if (s.error500Next) {
                s.error500Next = false;
                reply(ex, 500, error("Internal server error (test switch)"), method, path, "error-500 switch");
                return;
            }

            if (sub.equals("/login") && method.equals("POST")) { login(ex, method, path); return; }
            if (sub.equals("/logout") && method.equals("POST")) {
                s.endSession(sessionOf(ex));
                reply(ex, 200, data("logout", Map.of("authenticated", false)), method, path, "");
                return;
            }

            if (!s.validSession(sessionOf(ex))) {
                reply(ex, 401, error("Not logged in or session expired"), method, path, "no valid session cookie");
                return;
            }

            if (sub.equals("/papercatalog") && method.equals("GET")) {
                byte[] body = s.paperCatalogJson().getBytes(StandardCharsets.UTF_8);
                send(ex, 200, body, "application/json");
                s.log(method, path, 200, s.papers().size() + " papers");
                return;
            }
            if (sub.equals("/jobs") && method.equals("POST")) { upload(ex, method, path); return; }
            if (sub.equals("/jobs") && method.equals("GET")) {
                List<Object> items = s.jobs.all().stream().map(j -> (Object) jobItem(j)).toList();
                reply(ex, 200, data("jobs", Map.of("items", items)), method, path, items.size() + " jobs");
                return;
            }
            if (sub.startsWith("/jobs/") && method.equals("GET")) {
                JobStore.Job j = s.jobs.get(sub.substring("/jobs/".length()));
                if (j == null) { reply(ex, 404, error("No such job"), method, path, ""); return; }
                reply(ex, 200, data("job", Map.of("item", jobItem(j))), method, path, j.title);
                return;
            }
            reply(ex, 404, error("Unknown API call " + method + " " + path), method, path, "not supported by the mock");
        } catch (Exception e) {
            reply(ex, 500, error("Mock error: " + e.getMessage()), method, path, e.toString());
        }
    }

    // ------------------------------------------------------------------ login

    private void login(HttpExchange ex, String method, String path) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> in;
        try {
            in = Json.object(body);
        } catch (Exception e) {
            reply(ex, 400, error("Login body must be JSON: {\"apikey\":..,\"username\":..,\"password\":..}"), method, path, "invalid JSON");
            return;
        }
        String key = str(in.get("apikey")), user = str(in.get("username")), pass = str(in.get("password"));
        if ("guest".equalsIgnoreCase(user)) { reply(ex, 401, error("Guest cannot use the API"), method, path, "guest"); return; }
        if (!s.cfg.apiKey.equals(key)) { reply(ex, 401, error("Invalid API key"), method, path, "wrong api key"); return; }
        if (!s.cfg.username.equals(user) || !s.cfg.password.equals(pass)) {
            reply(ex, 401, error("Invalid user name or password"), method, path, "wrong user/password");
            return;
        }
        String session = s.newSession();
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session + "; Path=/; HttpOnly; Secure");
        reply(ex, 200, data("login", Map.of("authenticated", true)), method, path, "user " + user);
    }

    // ------------------------------------------------------------------ upload

    private void upload(HttpExchange ex, String method, String path) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        String boundary = Multipart.boundary(type);
        if (boundary == null) {
            reply(ex, 400, error("Expected multipart/form-data with a boundary"), method, path, "Content-Type: " + type);
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        List<Multipart.Part> parts = Multipart.parse(body, boundary);

        Multipart.Part file = null;
        String queue = "hold", preset = null, pcmid = null, attributesText = null;
        for (Multipart.Part p : parts) {
            if (p.name() == null) continue;
            switch (p.name()) {
                case "file" -> file = p;
                case "queue" -> queue = p.text().trim();
                case "preset" -> preset = p.text().trim();
                case "pcmid" -> pcmid = p.text().trim();
                case "attributes" -> attributesText = p.text();
                default -> { /* ignored, like unknown fields on a real server */ }
            }
        }
        if (file == null || file.filename() == null) { reply(ex, 400, error("Missing 'file' part"), method, path, "no file"); return; }
        if (s.rejectNext) {
            s.rejectNext = false;
            reply(ex, 400, error("Job rejected (test switch)"), method, path, "reject switch: " + file.filename());
            return;
        }
        String format = JobStore.detectFormat(file.data());
        if (format == null) {
            reply(ex, 400, error("unsupported PDL: the Fiery can only process PDF, PostScript and TIFF"), method, path,
                    file.filename() + " is not PDF/PS/TIFF");
            return;
        }
        if (!queue.equalsIgnoreCase("hold") && !queue.equalsIgnoreCase("print")) {
            reply(ex, 400, error("Unknown queue '" + queue + "' (use hold or print)"), method, path, "bad queue");
            return;
        }
        Map<String, String> papers = s.papers();
        if (pcmid != null && !pcmid.isEmpty() && !papers.containsKey(pcmid)) {
            reply(ex, 400, error("pcmid " + pcmid + " is not in the paper catalog"), method, path, "unknown pcmid");
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (attributesText != null && !attributesText.isBlank()) {
            try {
                attributes = Json.object(attributesText);
            } catch (Exception e) {
                reply(ex, 400, error("attributes is not valid JSON: " + e.getMessage()), method, path, "bad attributes");
                return;
            }
        }

        JobStore.Job j = s.jobs.add(file.filename(), "API", queue.toLowerCase(), format, file.data(), file.contentType(), attributes);
        if (pcmid != null && !pcmid.isEmpty()) { j.paperId = pcmid; j.paperName = papers.get(pcmid); }
        if (preset != null && !preset.isEmpty()) j.notes.add("Preset " + preset);
        Object copies = attributes.get("num copies");
        if (copies != null) j.copies = copies.toString();
        Object duplex = attributes.get(s.cfg.duplexAttribute);
        if (duplex != null && !duplex.toString().equalsIgnoreCase("false") && !duplex.toString().equalsIgnoreCase("simplex")) j.duplex = true;
        s.jobs.save(j);

        reply(ex, 200, data("job", Map.of("item", Map.of("id", j.id, "title", j.title, "state", j.state.toLowerCase()))),
                method, path, "job " + j.id + ": " + j.title + " (" + j.format + ", " + j.pages + " page(s), "
                        + (j.paperName.isEmpty() ? "no paper" : j.paperName) + ", copies " + j.copies + (j.duplex ? ", duplex" : "") + ")");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> jobItem(JobStore.Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id);
        m.put("title", j.title);
        m.put("state", j.state.toLowerCase());
        m.put("queue", j.queue);
        m.put("pages", String.valueOf(j.pages));
        m.put("num copies", j.copies);
        m.put("pcmid", j.paperId);
        m.put("media", j.paperName);
        m.put("duplex", j.duplex ? "true" : "false");
        m.put("date", j.received);
        m.putAll(j.attributes);
        return m;
    }

    private static Map<String, Object> data(String kind, Map<String, Object> content) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", kind);
        d.putAll(content);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", Instant.now().toString());
        m.put("data", d);
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("message", message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", Instant.now().toString());
        m.put("error", e);
        return m;
    }

    private void reply(HttpExchange ex, int status, Map<String, Object> body, String method, String path, String note) throws IOException {
        send(ex, status, Json.write(body).getBytes(StandardCharsets.UTF_8), "application/json");
        s.log(method, path, status, note);
    }

    static void send(HttpExchange ex, int status, byte[] body, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    private static String sessionOf(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String c : header.split(";")) {
                String t = c.trim();
                if (t.startsWith(COOKIE + "=")) return t.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
