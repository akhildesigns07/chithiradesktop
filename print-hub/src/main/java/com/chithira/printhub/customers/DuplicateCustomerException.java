package com.chithira.printhub.customers;

import com.chithira.printhub.common.BusinessException;

/** An active customer with the same phone number already exists. */
public class DuplicateCustomerException extends BusinessException {
    private final Customer existing;

    public DuplicateCustomerException(Customer existing) {
        super("Customer " + existing.getCode() + " " + existing.getName() + " already has this phone number");
        this.existing = existing;
    }

    public Customer existing() { return existing; }
}
