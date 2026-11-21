package com.chithira.printhub.jobs;

import com.chithira.printhub.devices.DevicePrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Desktop API: {@code POST /api/v1/jobs} after every send (accepted or rejected by the Fiery).
 * Returns 201 for a new job, or 200 with {@code "duplicate": true} when the outbox retried a job already saved.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class PrintJobApiController {

    private final PrintJobService jobs;

    public PrintJobApiController(PrintJobService jobs) { this.jobs = jobs; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> report(@AuthenticationPrincipal DevicePrincipal device,
                                                      @Valid @RequestBody JobReport report) {
        PrintJobService.Recorded recorded = jobs.recordFromDesktop(device.id(), report);
        Map<String, Object> body = Map.of(
                "item", Map.of("id", recorded.job().getId(), "status", recorded.job().getStatus().name()),
                "duplicate", recorded.duplicate());
        return ResponseEntity.status(recorded.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
