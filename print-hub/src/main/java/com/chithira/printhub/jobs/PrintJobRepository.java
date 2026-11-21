package com.chithira.printhub.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Database access for print jobs. */
public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    /** The job a desktop already reported under this id (outbox retries send it again). */
    Optional<PrintJob> findByDeviceIdAndClientJobUuid(long deviceId, String clientJobUuid);
}
