package com.chithira.printhub.common.events;

/**
 * Published after a customer is saved. Future modules (billing, portal, reminders) listen
 * with {@code @EventListener} instead of being called by the customers module.
 */
public record CustomerCreated(long customerId, String name, String source) {}
