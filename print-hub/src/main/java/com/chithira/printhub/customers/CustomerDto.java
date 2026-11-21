package com.chithira.printhub.customers;

import java.time.Instant;

/** Customer as the desktop API returns it. */
public record CustomerDto(long id, String code, String name, String phone, String email, String company,
                          boolean active, Instant updatedAt) {
    static CustomerDto of(Customer customer) {
        return new CustomerDto(customer.getId(), customer.getCode(), customer.getName(), customer.getPhone(), customer.getEmail(), customer.getCompany(),
                customer.isActive(), customer.getUpdatedAt());
    }
}
