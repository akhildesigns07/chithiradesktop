package com.chithira.mockfiery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mock's own web page (like the Hold queue in Command WorkStation) and its small
 * JSON API: job list, job details, page previews, downloads and test switches.
 */
final class WebPage implements HttpHandler {

    private final MockState s;

    WebPage(MockState s) { this.s = s; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                try (InputStream in = WebPage.class.getResourceAsStream("/mockfiery/dashboard.html")) {
                    FieryApi.send(ex, 200, in.readAllBytes(), "text/html; charset=utf-8");
                }
                return;
            }
            if (path.equals("/mock/api/state")) { FieryApi.send(ex, 200, json(state()), "application/json"); return; }
            if (path.equals("/mock/api/switch") && method.equals("POST")) { toggle(ex); return; }
            if (path.equals("/mock/api/clear") && method.equals("POST")) {
                s.jobs.clear();
                s.log("POST", path, 200, "Hold queue cleared");
                FieryApi.send(ex, 200, json(state()), "application/json");
                return;
            }
            if (path.startsWith("/mock/jobs/")) { job(ex, path.substring("/mock/jobs/".length())); return; }
            FieryApi.send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8), "text/plain");
        } catch (Exception e) {
            FieryApi.send(ex, 500, ("Mock error: " + e).getBytes(StandardCharsets.UTF_8), "text/plain");
        }
    }

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobs", s.jobs.all().stream().map(j -> (Object) j.toMap()).toList());
        m.put("activity", s.activity());
        Map<String, Object> sw = new LinkedHashMap<>();
        sw.put("offline", s.offline);
        sw.put("slowSeconds", s.slowSeconds);
        sw.put("rejectNext", s.rejectNext);
        sw.put("error500Next", s.error500Next);
        m.put("switches", sw);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("apiUrl", (s.cfg.https ? "https" : "http") + "://localhost:" + s.cfg.httpsPort + FieryApi.BASE);
        info.put("rawPort", s.cfg.rawPort);
        info.put("apiKey", s.cfg.apiKey);
        info.put("username", s.cfg.username);
        info.put("sessions", s.sessionCount());
        info.put("papers", s.papers().size());
        info.put("dataFolder", s.cfg.dataDir.toString());
        info.put("configFile", s.cfg.file.toString());
        m.put("info", info);
        return m;
    }

    private void toggle(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String name = q == null ? "" : q.replaceFirst("^name=", "");
        switch (name) {
            case "offline" -> s.offline = !s.offline;
            case "slow" -> s.slowSeconds = s.slowSeconds > 0 ? 0 : 20;
            case "rejectNext" -> s.rejectNext = !s.rejectNext;
            case "error500Next" -> s.error500Next = !s.error500Next;
            case "expireSessions" -> s.expireAllSessions();
            default -> { FieryApi.send(ex, 400, "Unknown switch".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
        }
        s.log("POST", "/mock/api/switch", 200, "test switch: " + name);
        FieryApi.send(ex, 200, json(state()), "application/json");
    }

    /** /mock/jobs/{id}/page/{n}.png  or  /mock/jobs/{id}/file */
    private void job(HttpExchange ex, String rest) throws IOException {
        String[] parts = rest.split("/");
        JobStore.Job j = s.jobs.get(parts[0]);
        if (j == null) { FieryApi.send(ex, 404, "No such job".getBytes(StandardCharsets.UTF_8), "text/plain"); return; }
        if (parts.length >= 2 && parts[1].equals("file")) {
            byte[] data = Files.readAllBytes(s.jobs.fileOf(j));
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(j.id + "_" + j.title + extension(j), StandardCharsets.UTF_8).replace("+", "%20"));
            FieryApi.send(ex, 200, data, "application/octet-stream");
            return;
        }
        if (parts.length >= 3 && parts[1].equals("page")) {
            int n = Integer.parseInt(parts[2].replace(".png", ""));
            byte[] png = s.previews.page(j, s.jobs.fileOf(j), n);
            if (png == null) {
                FieryApi.send(ex, 404, "No preview (PostScript previews need Ghostscript installed)".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            FieryApi.send(ex, 200, png, "image/png");
            return;
        }
        FieryApi.send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static String extension(JobStore.Job j) {
        String actual = switch (j.format) { case "PDF" -> ".pdf"; case "TIFF" -> ".tif"; default -> ".ps"; };
        return j.title.toLowerCase().endsWith(actual) ? "" : actual;
    }

    private static byte[] json(Object o) { return Json.write(o).getBytes(StandardCharsets.UTF_8); }
}
