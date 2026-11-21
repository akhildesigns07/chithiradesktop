package com.chithira.printhub.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Database access for the audit log (read and insert only).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    List<AuditLog> findTop10ByOrderByIdDesc();

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByIdDesc(String entityType, String entityId, Pageable p);

    List<AuditLog> findByAction(String action);
}
