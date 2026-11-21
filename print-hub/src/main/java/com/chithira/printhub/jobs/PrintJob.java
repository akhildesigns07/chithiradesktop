package com.chithira.printhub.jobs;

import com.chithira.printhub.customers.Customer;
import com.chithira.printhub.devices.Device;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One job a desktop sent (or tried to send) to the Fiery, with its counts once printed. */
@Entity
@Table(name = "print_job")
public class PrintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_job_uuid", nullable = false)
    private String clientJobUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id")
    private Device device;

    private String operator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "fiery_job_id")
    private String fieryJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "send_method", nullable = false)
    private SendMethod sendMethod;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "back_file_name")
    private String backFileName;

    @Column(name = "job_name")
    private String jobName;

    @Column(name = "paper_pcmid")
    private String paperPcmid;

    @Column(name = "paper_name")
    private String paperName;

    @Column(name = "file_width_mm")
    private Double fileWidthMm;

    @Column(name = "file_height_mm")
    private Double fileHeightMm;

    @Column(name = "file_size_source")
    private String fileSizeSource;

    private boolean imposed;

    @Column(name = "quantity_pieces")
    private Integer quantityPieces;

    @Column(name = "pieces_per_sheet")
    private Integer piecesPerSheet;

    @Column(name = "sheets_requested", nullable = false)
    private int sheetsRequested;

    private boolean duplex;

    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrintJobStatus status;

    @Column(name = "fiery_state_raw")
    private String fieryStateRaw;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "printed_at")
    private Instant printedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "printed_count")
    private Integer printedCount;

    @Column(name = "waste_count")
    private Integer wasteCount;

    @Column(name = "waste_reason")
    private String wasteReason;

    @Column(name = "counted_by")
    private String countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { receivedAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    /** Snapshot for the audit log. */
    public Map<String, Object> toAudit() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fieryJobId", fieryJobId);
        snapshot.put("customerId", customer == null ? null : customer.getId());
        snapshot.put("fileName", fileName);
        snapshot.put("backFileName", backFileName);
        snapshot.put("sendMethod", sendMethod);
        snapshot.put("paper", paperName);
        snapshot.put("fileSize", getFileSizeLabel());
        snapshot.put("imposed", imposed);
        snapshot.put("quantityPieces", quantityPieces);
        snapshot.put("sheetsRequested", sheetsRequested);
        snapshot.put("duplex", duplex);
        snapshot.put("status", status);
        snapshot.put("printedCount", printedCount);
        snapshot.put("wasteCount", wasteCount);
        snapshot.put("wasteReason", wasteReason);
        snapshot.put("errorMessage", errorMessage);
        return snapshot;
    }

    public Long getId() { return id; }
    public String getClientJobUuid() { return clientJobUuid; }
    void setClientJobUuid(String clientJobUuid) { this.clientJobUuid = clientJobUuid; }
    public Device getDevice() { return device; }
    void setDevice(Device device) { this.device = device; }
    public String getOperator() { return operator; }
    void setOperator(String operator) { this.operator = operator; }
    public Customer getCustomer() { return customer; }
    void setCustomer(Customer customer) { this.customer = customer; }
    public String getFieryJobId() { return fieryJobId; }
    void setFieryJobId(String fieryJobId) { this.fieryJobId = fieryJobId; }
    public SendMethod getSendMethod() { return sendMethod; }
    void setSendMethod(SendMethod sendMethod) { this.sendMethod = sendMethod; }
    public String getFileName() { return fileName; }
    void setFileName(String fileName) { this.fileName = fileName; }
    public String getBackFileName() { return backFileName; }
    void setBackFileName(String backFileName) { this.backFileName = backFileName; }
    public String getJobName() { return jobName; }
    void setJobName(String jobName) { this.jobName = jobName; }
    public String getPaperPcmid() { return paperPcmid; }
    void setPaperPcmid(String paperPcmid) { this.paperPcmid = paperPcmid; }
    public String getPaperName() { return paperName; }
    void setPaperName(String paperName) { this.paperName = paperName; }
    public Double getFileWidthMm() { return fileWidthMm; }
    public Double getFileHeightMm() { return fileHeightMm; }
    public String getFileSizeSource() { return fileSizeSource; }

    void setFileSize(Double widthMm, Double heightMm, String source) {
        this.fileWidthMm = widthMm;
        this.fileHeightMm = heightMm;
        this.fileSizeSource = source;
    }

    /** The original file's size, e.g. "90 × 50 mm (3.54 × 1.97 in)", or null when unknown. */
    public String getFileSizeLabel() {
        if (fileWidthMm == null || fileHeightMm == null) return null;
        return number(fileWidthMm, 1) + " × " + number(fileHeightMm, 1) + " mm ("
                + number(fileWidthMm / 25.4, 2) + " × " + number(fileHeightMm / 25.4, 2) + " in)";
    }

    private static String number(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        double rounded = Math.round(value * factor) / factor;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    public boolean isImposed() { return imposed; }
    void setImposed(boolean imposed) { this.imposed = imposed; }
    public Integer getQuantityPieces() { return quantityPieces; }
    void setQuantityPieces(Integer quantityPieces) { this.quantityPieces = quantityPieces; }
    public Integer getPiecesPerSheet() { return piecesPerSheet; }
    void setPiecesPerSheet(Integer piecesPerSheet) { this.piecesPerSheet = piecesPerSheet; }
    public int getSheetsRequested() { return sheetsRequested; }
    void setSheetsRequested(int sheetsRequested) { this.sheetsRequested = sheetsRequested; }
    public boolean isDuplex() { return duplex; }
    void setDuplex(boolean duplex) { this.duplex = duplex; }
    public String getInstructions() { return instructions; }
    void setInstructions(String instructions) { this.instructions = instructions; }
    public PrintJobStatus getStatus() { return status; }
    void setStatus(PrintJobStatus status) { this.status = status; }
    public String getFieryStateRaw() { return fieryStateRaw; }
    void setFieryStateRaw(String fieryStateRaw) { this.fieryStateRaw = fieryStateRaw; }
    void setPrintedAt(Instant printedAt) { this.printedAt = printedAt; }
    void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }

    /** Records the print room's counts. */
    void setCounts(int printedCount, int wasteCount, String wasteReason, String countedBy) {
        this.printedCount = printedCount;
        this.wasteCount = wasteCount;
        this.wasteReason = wasteReason;
        this.countedBy = countedBy;
        this.countedAt = Instant.now();
    }

    /** Sheets used in total: printed + waste (null until counted). */
    public Integer getTotalSheets() {
        return printedCount == null ? null : printedCount + (wasteCount == null ? 0 : wasteCount);
    }
    public String getErrorMessage() { return errorMessage; }
    void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getSentAt() { return sentAt; }
    void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getPrintedAt() { return printedAt; }
    public Instant getRemovedAt() { return removedAt; }
    public Integer getPrintedCount() { return printedCount; }
    public Integer getWasteCount() { return wasteCount; }
    public String getWasteReason() { return wasteReason; }
    public String getCountedBy() { return countedBy; }
    public Instant getCountedAt() { return countedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
