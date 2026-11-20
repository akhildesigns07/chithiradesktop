package com.chithira.mockfiery;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Accepts raw print jobs on port 9100, like a printer's "Standard TCP/IP port (RAW)".
 * Point a Windows printer that uses a PostScript driver at localhost:9100 and every
 * Ctrl+P / driver job lands in the mock's Hold queue.
 */
final class RawPrintServer implements Runnable {

    private final MockState s;
    private final ServerSocket server;

    RawPrintServer(MockState s) throws IOException {
        this.s = s;
        this.server = new ServerSocket(s.cfg.rawPort);
    }

    void start() {
        Thread t = new Thread(this, "raw-9100");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> receive(socket), "raw-job");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!server.isClosed()) s.log("RAW", ":" + s.cfg.rawPort, 500, e.getMessage());
            }
        }
    }

    private void receive(Socket socket) {
        try (socket; InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(60_000);
            byte[] data;
            try {
                data = in.readAllBytes();
            } catch (SocketTimeoutException e) {
                s.log("RAW", ":" + s.cfg.rawPort, 408, "no data for 60 s");
                return;
            }
            if (data.length == 0) return; // printer status probe
            if (s.offline) { s.log("RAW", ":" + s.cfg.rawPort, 503, "offline switch – job dropped"); return; }
            String format = JobStore.detectFormat(data);
            if (format == null) {
                s.log("RAW", ":" + s.cfg.rawPort, 400, "not PostScript/PDF (" + data.length + " bytes) – use a PostScript driver");
                return;
            }
            JobStore.Job j = s.jobs.add("Raw print job", "Port " + s.cfg.rawPort + " (driver)", "hold", format, data, "", null);
            s.log("RAW", ":" + s.cfg.rawPort, 200, "job " + j.id + ": " + j.title + " (" + j.format + ", " + j.pages + " page(s)"
                    + (j.duplex ? ", duplex" : "") + ")");
        } catch (IOException e) {
            s.log("RAW", ":" + s.cfg.rawPort, 500, e.getMessage());
        }
    }
}
