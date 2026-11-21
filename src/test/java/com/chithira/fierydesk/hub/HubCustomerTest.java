package com.chithira.fierydesk.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Search in the customer picker. */
class HubCustomerTest {

    private final HubCustomer customer = new HubCustomer(42, "C-0042", "Anu Weddings", "9847012345", null,
            "Anu Events", true, "2026-11-21T10:00:00Z");

    @Test
    void matchesEveryWordAcrossFields() {
        assertTrue(customer.matches(""));
        assertTrue(customer.matches("anu"));
        assertTrue(customer.matches("wed events"));
        assertTrue(customer.matches("c-0042"));
        assertTrue(customer.matches("7012345"));
        assertFalse(customer.matches("anu bakery"));
    }

    @Test
    void labelShowsCodeNameCompanyAndPhone() {
        assertTrue(customer.toString().startsWith("C-0042 · Anu Weddings (Anu Events) · 9847012345"));
    }
}
