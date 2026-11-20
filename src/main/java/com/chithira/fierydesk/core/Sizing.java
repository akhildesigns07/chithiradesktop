package com.chithira.fierydesk.core;

import com.chithira.fierydesk.document.PageSpec;
import com.chithira.fierydesk.fiery.Paper;

/** How an image is placed on the chosen paper when it is converted to PDF. */
public enum Sizing {
    FIT("Fit to paper (whole image)"),
    FILL("Fill paper (crop edges)"),
    ACTUAL("Actual size @300 DPI");

    private final String label;
    Sizing(String label) { this.label = label; }

    public PageSpec pageSpec(Paper paper) {
        if (this == ACTUAL || paper == null || !paper.hasSize()) {
            return new PageSpec(0, 0, PageSpec.Fit.FIT, false);   // page = image size
        }
        return new PageSpec(paper.widthInch(), paper.heightInch(),
                this == FILL ? PageSpec.Fit.FILL : PageSpec.Fit.FIT, true);
    }

    /** For driver printing: a paper size in inches (null = unknown, use the image size). */
    public PageSpec pageSpec(double[] inches) {
        if (this == ACTUAL || inches == null) return new PageSpec(0, 0, PageSpec.Fit.FIT, false);
        return new PageSpec(inches[0], inches[1], this == FILL ? PageSpec.Fit.FILL : PageSpec.Fit.FIT, true);
    }

    @Override
    public String toString() { return label; }
}
