package com.chithira.printhub.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * {@code printhub.print-room.*}
 *
 * @param countsOverdueHours jobs waiting longer than this for counts are flagged
 * @param wasteReasons       choices offered when waste is entered
 */
@ConfigurationProperties("printhub.print-room")
public record PrintRoomProperties(@DefaultValue("12") int countsOverdueHours,
                                  @DefaultValue({"Misprint", "Colour mismatch", "Paper jam", "Other"}) List<String> wasteReasons) {}
