package com.chithira.printhub.papers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Database access for Paper Catalog entries. */
public interface PaperRepository extends JpaRepository<Paper, Long> {
    Optional<Paper> findByFieryPcmid(String fieryPcmid);

    List<Paper> findAllByOrderByActiveDescNameAsc();
}
