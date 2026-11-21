package com.chithira.printhub.devices;

/** A desktop authenticated by its API key. */
public record DevicePrincipal(long id, String name) {
    @Override public String toString() { return name; }
}
