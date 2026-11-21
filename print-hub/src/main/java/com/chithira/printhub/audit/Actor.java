package com.chithira.printhub.audit;

/** Who did something: a logged-in user, a desktop (API key) or the system itself. */
public record Actor(String type, Long id, String name) {
    public static final Actor SYSTEM = new Actor("SYSTEM", null, "system");

    public static Actor user(Long id, String username) { return new Actor("USER", id, username); }

    public static Actor device(long id, String name) { return new Actor("DEVICE", id, name); }
}
