package com.chithira.printhub.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code printhub.*} settings from application.yml. */
@ConfigurationProperties("printhub")
public record PrintHubProperties(@DefaultValue("Chithira Printers") String shopName,
                                 @DefaultValue Features features) {

    /** Optional modules; a page whose feature is off is hidden everywhere. */
    public record Features(@DefaultValue("false") boolean customerPortal,
                           @DefaultValue("false") boolean reports,
                           @DefaultValue("false") boolean billing,
                           @DefaultValue("false") boolean paymentReminders,
                           @DefaultValue("false") boolean inventory) {

        public boolean isOn(Feature f) {
            return switch (f) {
                case CUSTOMER_PORTAL -> customerPortal;
                case REPORTS -> reports;
                case BILLING -> billing;
                case PAYMENT_REMINDERS -> paymentReminders;
                case INVENTORY -> inventory;
            };
        }
    }

    public enum Feature { CUSTOMER_PORTAL, REPORTS, BILLING, PAYMENT_REMINDERS, INVENTORY }
}
