package com.chithira.printhub.dashboard;

import com.chithira.printhub.jobs.PrintJobStatus;
import com.chithira.printhub.jobs.SendMethod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Reads the jobs of a dashboard's range in one query (customer and desktop joined, no lazy loading). */
@Repository
class DashboardRepository {

    private final EntityManager entityManager;

    DashboardRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    List<JobFact> jobFacts(DashboardFilter filter) {
        StringBuilder jpql = new StringBuilder("""
                select j.id, j.sentAt, j.status, j.sendMethod, j.sheetsRequested, j.printedCount, j.wasteCount, j.wasteReason,
                       c.id, c.code, c.name, j.paperPcmid, j.paperName, d.name, j.operator, j.fileName
                from PrintJob j join j.device d left join j.customer c
                where j.sentAt >= :from and j.sentAt < :to""");
        if (filter.customerId() != null) jpql.append(" and c.id = :customerId");
        if (filter.paperPcmid() != null) jpql.append(" and j.paperPcmid = :paperPcmid");
        if (filter.deviceId() != null) jpql.append(" and d.id = :deviceId");
        jpql.append(" order by j.sentAt");

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive());
        if (filter.customerId() != null) query.setParameter("customerId", filter.customerId());
        if (filter.paperPcmid() != null) query.setParameter("paperPcmid", filter.paperPcmid());
        if (filter.deviceId() != null) query.setParameter("deviceId", filter.deviceId());

        return query.getResultList().stream().map(row -> new JobFact(
                ((Number) row[0]).longValue(), (Instant) row[1], (PrintJobStatus) row[2], (SendMethod) row[3],
                ((Number) row[4]).intValue(), (Integer) row[5], (Integer) row[6], (String) row[7],
                row[8] == null ? null : ((Number) row[8]).longValue(), (String) row[9], (String) row[10],
                (String) row[11], (String) row[12], (String) row[13], (String) row[14], (String) row[15])).toList();
    }

    /** Jobs in these statuses right now, whatever the date range (workload tiles). */
    long countByStatus(List<PrintJobStatus> statuses) {
        return entityManager.createQuery("select count(j) from PrintJob j where j.status in :statuses", Long.class)
                .setParameter("statuses", statuses).getSingleResult();
    }
}
