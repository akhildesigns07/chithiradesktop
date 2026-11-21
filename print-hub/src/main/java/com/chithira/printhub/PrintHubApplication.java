package com.chithira.printhub;

import com.chithira.printhub.common.PrintHubProperties;
import com.chithira.printhub.fiery.FieryProperties;
import com.chithira.printhub.jobs.PrintRoomProperties;
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
@EnableConfigurationProperties({PrintHubProperties.class, FieryProperties.class, PrintRoomProperties.class})
public class PrintHubApplication {
    public static void main(String[] args) {
        // The Fiery's self-signed certificate is usually issued to its host name, not the IP address the
        // hub uses. Only the Fiery client uses java.net.http in the hub (same setting as the desktop app).
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        SpringApplication.run(PrintHubApplication.class, args);
    }
}
