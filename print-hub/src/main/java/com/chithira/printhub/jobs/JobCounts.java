package com.chithira.printhub.jobs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the print room enters for a job: sheets that came out good, and sheets wasted (with a reason).
 */
public class JobCounts {

    @NotNull(message = "Enter the total printed")
    @Min(value = 0, message = "Can't be negative")
    @Max(value = 1_000_000, message = "Too large")
    private Integer printed;

    @NotNull(message = "Enter the waste (0 if none)")
    @Min(value = 0, message = "Can't be negative")
    @Max(value = 1_000_000, message = "Too large")
    private Integer waste = 0;

    @Size(max = 200)
    private String wasteReason;

    public static JobCounts of(PrintJob job) {
        JobCounts counts = new JobCounts();
        counts.printed = job.getPrintedCount();
        counts.waste = job.getWasteCount() == null ? 0 : job.getWasteCount();
        counts.wasteReason = job.getWasteReason();
        return counts;
    }

    public Integer getPrinted() { return printed; }
    public void setPrinted(Integer printed) { this.printed = printed; }
    public Integer getWaste() { return waste; }
    public void setWaste(Integer waste) { this.waste = waste; }
    public String getWasteReason() { return wasteReason; }
    public void setWasteReason(String wasteReason) { this.wasteReason = wasteReason; }
}
