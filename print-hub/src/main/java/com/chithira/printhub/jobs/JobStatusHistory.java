package com.chithira.printhub.jobs;

import jakarta.persistence.*;

import java.time.Instant;

/** One status change of a print job, with who or what caused it. */
@Entity
@Table(name = "job_status_history")
public class JobStatusHistory {

    /** What changed the status. */
    public enum Source { DESKTOP, POLLER, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PrintJobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PrintJobStatus toStatus;

    @Column(nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String note;

    protected JobStatusHistory() {}

    public JobStatusHistory(long jobId, PrintJobStatus fromStatus, PrintJobStatus toStatus, Source source, String note) {
        this.jobId = jobId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.source = source;
        this.note = note;
        this.at = Instant.now();
    }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public PrintJobStatus getFromStatus() { return fromStatus; }
    public PrintJobStatus getToStatus() { return toStatus; }
    public Instant getAt() { return at; }
    public Source getSource() { return source; }
    public String getNote() { return note; }
}
