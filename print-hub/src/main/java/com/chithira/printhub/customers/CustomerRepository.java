package com.chithira.printhub.customers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Database access for customers: search for the list page and the desktop cache, and change tracking for desktop sync.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /** Name, phone, code, company or email contains {@code q} ("" = all). */
    @Query("""
            select c from Customer c
            where (:includeInactive = true or c.active = true)
              and (lower(c.name) like concat('%', :q, '%')
                   or lower(coalesce(c.phone, '')) like concat('%', :q, '%')
                   or lower(coalesce(c.code, '')) like concat('%', :q, '%')
                   or lower(coalesce(c.company, '')) like concat('%', :q, '%')
                   or lower(coalesce(c.email, '')) like concat('%', :q, '%'))
            order by c.name""")
    Page<Customer> search(@Param("q") String q, @Param("includeInactive") boolean includeInactive, Pageable page);

    /** Changes since the desktop last synced, including deactivations. */
    List<Customer> findByUpdatedAtAfterOrderByUpdatedAt(Instant since);

    Optional<Customer> findFirstByPhoneAndActiveTrue(String phone);

    long countByActiveTrue();
}
