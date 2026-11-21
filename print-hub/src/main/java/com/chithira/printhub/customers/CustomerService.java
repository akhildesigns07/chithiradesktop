package com.chithira.printhub.customers;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.NotFoundException;
import com.chithira.printhub.common.events.CustomerCreated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Customer rules: one active customer per phone number, shop code C-0001 made from the id,
 * and an audit row plus a {@link CustomerCreated} event for every change.
 */
@Service
public class CustomerService {

    private final CustomerRepository repo;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CustomerService(CustomerRepository repo, AuditService audit, ApplicationEventPublisher events) {
        this.repo = repo;
        this.audit = audit;
        this.events = events;
    }

    public Customer get(long id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("Customer")); }

    public Page<Customer> search(String query, boolean includeInactive, int page, int size) {
        String searchTerm = query == null ? "" : query.trim().toLowerCase();
        return repo.search(searchTerm, includeInactive, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 5000)));
    }

    public List<Customer> changedSince(Instant since) { return repo.findByUpdatedAtAfterOrderByUpdatedAt(since); }

    public long activeCount() { return repo.countByActiveTrue(); }

    /**
     * @param source WEB or DESKTOP
     * @throws DuplicateCustomerException if an active customer already has this phone number
     */
    @Transactional
    public Customer create(CustomerForm form, String source) {
        String phone = form.cleanPhone();
        if (phone != null) repo.findFirstByPhoneAndActiveTrue(phone).ifPresent(customer -> { throw new DuplicateCustomerException(customer); });
        Customer customer = new Customer();
        customer.apply(form);
        customer.setSource(source);
        customer.setCreatedBy(audit.current().name());
        repo.saveAndFlush(customer);
        customer.setCode(String.format("C-%04d", customer.getId()));
        audit.record("CUSTOMER_CREATED", "CUSTOMER", customer.getId(), "Customer " + customer.getCode() + " " + customer.getName()
                + " created (" + source.toLowerCase() + ")", null, customer.toAudit());
        events.publishEvent(new CustomerCreated(customer.getId(), customer.getName(), source));
        return customer;
    }

    @Transactional
    public Customer update(long id, CustomerForm form) {
        Customer customer = get(id);
        String phone = form.cleanPhone();
        if (phone != null) {
            repo.findFirstByPhoneAndActiveTrue(phone)
                    .filter(otherCustomer -> !Objects.equals(otherCustomer.getId(), id))
                    .ifPresent(otherCustomer -> { throw new DuplicateCustomerException(otherCustomer); });
        }
        var before = customer.toAudit();
        customer.apply(form);
        audit.record("CUSTOMER_UPDATED", "CUSTOMER", id, "Customer " + customer.getCode() + " updated", before, customer.toAudit());
        return customer;
    }

    @Transactional
    public void setActive(long id, boolean active) {
        Customer customer = get(id);
        if (customer.isActive() == active) return;
        var before = customer.toAudit();
        customer.setActive(active);
        audit.record(active ? "CUSTOMER_REACTIVATED" : "CUSTOMER_DEACTIVATED", "CUSTOMER", id,
                "Customer " + customer.getCode() + (active ? " reactivated" : " deactivated"), before, customer.toAudit());
    }
}
