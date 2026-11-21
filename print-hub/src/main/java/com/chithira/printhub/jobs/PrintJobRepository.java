package com.chithira.printhub.jobs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Database access for print jobs. Methods used by pages load the customer and desktop with the job
 * ({@link EntityGraph}), because pages are rendered after the transaction has ended.
 */
public interface PrintJobRepository extends JpaRepository<PrintJob, Long>, JpaSpecificationExecutor<PrintJob> {

    /** The job a desktop already reported under this id (outbox retries send it again). */
    Optional<PrintJob> findByDeviceIdAndClientJobUuid(long deviceId, String clientJobUuid);

    @EntityGraph(attributePaths = {"customer", "device"})
    Optional<PrintJob> findWithDetailsById(long id);

    @Override
    @EntityGraph(attributePaths = {"customer", "device"})
    Page<PrintJob> findAll(Specification<PrintJob> spec, Pageable pageable);

    /** Jobs the poller follows: still on the Fiery and sent with a Fiery job id. */
    List<PrintJob> findByStatusInAndFieryJobIdIsNotNullOrderById(Collection<PrintJobStatus> statuses);

    @EntityGraph(attributePaths = {"customer", "device"})
    List<PrintJob> findByStatusInOrderBySentAt(Collection<PrintJobStatus> statuses);

    @EntityGraph(attributePaths = {"customer", "device"})
    List<PrintJob> findByStatusAndCountedAtAfterOrderByCountedAtDesc(PrintJobStatus status, Instant since);

    @EntityGraph(attributePaths = {"customer", "device"})
    List<PrintJob> findTop20ByCustomerIdOrderBySentAtDesc(long customerId);

    @Query("""
            select count(j), coalesce(sum(j.printedCount), 0), coalesce(sum(j.wasteCount), 0)
            from PrintJob j where j.customer.id = :customerId and j.status = :status""")
    List<Object[]> totalsForCustomer(@Param("customerId") long customerId, @Param("status") PrintJobStatus status);

    @Query("""
            select j.paperPcmid, count(j), coalesce(sum(j.printedCount), 0), coalesce(sum(j.wasteCount), 0)
            from PrintJob j where j.status = :status and j.paperPcmid is not null
            group by j.paperPcmid""")
    List<Object[]> totalsPerPaper(@Param("status") PrintJobStatus status);

    /** [jobs, printed, waste] for one customer's counted jobs. */
    default Object[] countedTotalsForCustomer(long customerId) {
        List<Object[]> rows = totalsForCustomer(customerId, PrintJobStatus.COUNTED);
        return rows.isEmpty() ? new Object[]{0L, 0L, 0L} : rows.get(0);
    }

    /** [paper pcmid, jobs, printed, waste] per paper, counted jobs only. */
    default List<Object[]> countedTotalsPerPaper() { return totalsPerPaper(PrintJobStatus.COUNTED); }

    long countByCustomerId(long customerId);

    long countByStatusIn(Collection<PrintJobStatus> statuses);

    /** Newest job id (0 when there are none), so open pages can notice new jobs. */
    @Query("select coalesce(max(j.id), 0) from PrintJob j")
    long latestId();
}
