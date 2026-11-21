package com.chithira.printhub.jobs;

import com.chithira.printhub.customers.Customer;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Filters of the job list; every filter is optional.
 *
 * @param text matches file name, job name, Fiery job id, customer name or code
 */
public record JobSearch(String text, PrintJobStatus status, Long customerId, Instant from, Instant to) {

    Specification<PrintJob> toSpecification() {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (customerId != null) predicates.add(builder.equal(root.get("customer").get("id"), customerId));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("sentAt"), from));
            if (to != null) predicates.add(builder.lessThan(root.get("sentAt"), to));
            if (text != null && !text.isBlank()) {
                String like = "%" + text.trim().toLowerCase() + "%";
                Join<PrintJob, Customer> customer = root.join("customer", JoinType.LEFT);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("fileName")), like),
                        builder.like(builder.lower(builder.coalesce(root.get("jobName"), "")), like),
                        builder.like(builder.lower(builder.coalesce(root.get("fieryJobId"), "")), like),
                        builder.like(builder.lower(builder.coalesce(customer.get("name"), "")), like),
                        builder.like(builder.lower(builder.coalesce(customer.get("code"), "")), like)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
