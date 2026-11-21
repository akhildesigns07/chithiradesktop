package com.chithira.printhub;

import com.chithira.printhub.common.PrintHubProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chithira Print Hub: the shop's web application. Desktops (Fiery hot folder) push every
 * job here with an API key; staff log in to manage customers, enter printed / waste counts
 * and see dashboards. Every change is written to the audit log.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PrintHubProperties.class)
public class PrintHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrintHubApplication.class, args);
    }
}
