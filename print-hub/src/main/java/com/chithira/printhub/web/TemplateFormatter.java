package com.chithira.printhub.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Template helpers: {@code ${@fmt.dt(x)}}, {@code ${@fmt.ago(x)}}. */
@Component("fmt")
public class TemplateFormatter {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault());

    public String dt(Instant instant) { return instant == null ? "–" : DATE_TIME.format(instant); }

    public String ago(Instant instant) {
        if (instant == null) return "never";
        long seconds = Duration.between(instant, Instant.now()).toSeconds();
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + " min ago";
        if (seconds < 86400) return (seconds / 3600) + " h ago";
        return (seconds / 86400) + " days ago";
    }
}
