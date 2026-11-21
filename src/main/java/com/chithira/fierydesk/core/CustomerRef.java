package com.chithira.fierydesk.core;

/**
 * The Print Hub customer a job is for, as chosen in the "Files waiting" window.
 *
 * @param id   customer id in the hub
 * @param code shop reference, e.g. C-0042
 */
public record CustomerRef(long id, String code, String name) {
    @Override public String toString() { return code + " " + name; }
}
