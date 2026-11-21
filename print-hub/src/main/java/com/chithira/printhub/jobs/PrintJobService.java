package com.chithira.printhub.jobs;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.NotFoundException;
import com.chithira.printhub.common.events.JobSent;
import com.chithira.printhub.customers.Customer;
import com.chithira.printhub.customers.CustomerService;
import com.chithira.printhub.devices.Device;
import com.chithira.printhub.devices.DeviceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Print job rules. Phase 2: saving what the desktops report. Every new job gets a status-history
 * row, an audit row and a {@link JobSent} event.
 */
@Service
public class PrintJobService {

    /** A saved job, and whether it was already known (a retry from the desktop outbox). */
    public record Recorded(PrintJob job, boolean duplicate) {}

    private final PrintJobRepository jobs;
    private final JobStatusHistoryRepository history;
    private final CustomerService customers;
    private final DeviceService devices;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public PrintJobService(PrintJobRepository jobs, JobStatusHistoryRepository history, CustomerService customers,
                           DeviceService devices, AuditService audit, ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.history = history;
        this.customers = customers;
        this.devices = devices;
        this.audit = audit;
        this.events = events;
    }

    public PrintJob get(long id) { return jobs.findById(id).orElseThrow(() -> new NotFoundException("Print job")); }

    /**
     * Saves a job reported by a desktop. Safe to call again with the same {@code clientJobUuid}:
     * the job saved the first time is returned unchanged.
     */
    @Transactional
    public Recorded recordFromDesktop(long deviceId, JobReport report) {
        Optional<PrintJob> existing = jobs.findByDeviceIdAndClientJobUuid(deviceId, report.clientJobUuid());
        if (existing.isPresent()) return new Recorded(existing.get(), true);

        if (!report.failed() && report.sendMethod().hasFieryJobId() && isBlank(report.fieryJobId())) {
            throw new BusinessException("fieryJobId is required for a job sent with the Fiery API");
        }
        Device device = devices.get(deviceId);
        Customer customer = report.customerId() == null ? null : findCustomer(report.customerId());

        PrintJob job = new PrintJob();
        job.setClientJobUuid(report.clientJobUuid().toLowerCase());
        job.setDevice(device);
        job.setOperator(trim(report.operator()));
        job.setCustomer(customer);
        job.setFieryJobId(trim(report.fieryJobId()));
        job.setSendMethod(report.sendMethod());
        job.setFileName(report.fileName().trim());
        job.setBackFileName(trim(report.backFileName()));
        job.setJobName(trim(report.jobName()));
        job.setPaperPcmid(trim(report.paperId()));
        job.setPaperName(trim(report.paperName()));
        job.setImposed(report.imposed());
        job.setQuantityPieces(report.quantity());
        job.setPiecesPerSheet(report.piecesPerSheet());
        job.setSheetsRequested(report.sheets());
        job.setDuplex(report.duplex());
        job.setInstructions(trim(report.instructions()));
        job.setSentAt(report.sentAt());
        job.setErrorMessage(trim(report.error()));
        job.setStatus(initialStatus(report));
        jobs.save(job);

        history.save(new JobStatusHistory(job.getId(), null, job.getStatus(), JobStatusHistory.Source.DESKTOP,
                report.failed() ? "Rejected: " + report.error() : "Reported by " + device.getName()));
        String who = customer == null ? "no customer" : customer.getCode() + " " + customer.getName();
        audit.record(report.failed() ? "JOB_FAILED" : "JOB_SENT", "PRINT_JOB", job.getId(),
                job.getFileName() + " (" + who + ")" + (job.getFieryJobId() == null ? "" : ", Fiery job " + job.getFieryJobId()),
                null, job.toAudit());
        events.publishEvent(new JobSent(job.getId(), customer == null ? null : customer.getId(), job.getStatus().name()));
        return new Recorded(job, false);
    }

    /**
     * Where a new job starts: FAILED if rejected; AWAITING_COUNTS for driver jobs (nothing to follow
     * on the Fiery); otherwise SENT until the poller sees it in the Hold queue.
     */
    static PrintJobStatus initialStatus(JobReport report) {
        if (report.failed()) return PrintJobStatus.FAILED;
        if (!report.sendMethod().hasFieryJobId()) return PrintJobStatus.AWAITING_COUNTS;
        return PrintJobStatus.SENT;
    }

    private Customer findCustomer(long customerId) {
        try {
            return customers.get(customerId);
        } catch (NotFoundException e) {
            throw new BusinessException("Unknown customerId " + customerId);
        }
    }

    private static boolean isBlank(String text) { return text == null || text.isBlank(); }

    private static String trim(String text) { return isBlank(text) ? null : text.trim(); }
}
