package com.chithira.printhub.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Database access for job status history. */
public interface JobStatusHistoryRepository extends JpaRepository<JobStatusHistory, Long> {
    List<JobStatusHistory> findByJobIdOrderById(long jobId);
}
