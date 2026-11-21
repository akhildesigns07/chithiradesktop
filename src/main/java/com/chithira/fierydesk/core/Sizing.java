package com.chithira.fierydesk.core;

import com.chithira.fierydesk.document.FilePageSize;
import com.chithira.fierydesk.document.PageSpec;
import com.chithira.fierydesk.fiery.Paper;

import java.nio.file.Path;

/** How an image is placed on the chosen paper when it is converted to PDF. */
public enum Sizing {
    FIT("Fit to paper (whole image)"),
    FILL("Fill paper (crop edges)"),
    /** Page size = the file's own size (image at its stored DPI, else 300); the Fiery is told that size. */
    ACTUAL("Actual size – page size = file size");

    private final String label;
    Sizing(String label) { this.label = label; }

    public PageSpec pageSpec(Paper paper) {
        if (this == ACTUAL || paper == null || !paper.hasSize()) {
            return new PageSpec(0, 0, PageSpec.Fit.FIT, false);   // page = image size
        }
        return new PageSpec(paper.widthInch(), paper.heightInch(),
                this == FILL ? PageSpec.Fit.FILL : PageSpec.Fit.FIT, true);
    }

    /** Like {@link #pageSpec(Paper)}, but "actual size" uses the DPI stored in this image file. */
    public PageSpec pageSpec(Paper paper, Path file) {
        return this == ACTUAL ? PageSpec.actualSize(FilePageSize.imageDpi(file)) : pageSpec(paper);
    }

    /** For driver printing: a paper size in inches (null = unknown, use the image size). */
    public PageSpec pageSpec(double[] inches) {
        if (this == ACTUAL || inches == null) return new PageSpec(0, 0, PageSpec.Fit.FIT, false);
        return new PageSpec(inches[0], inches[1], this == FILL ? PageSpec.Fit.FILL : PageSpec.Fit.FIT, true);
    }

    @Override
    public String toString() { return label; }
}
