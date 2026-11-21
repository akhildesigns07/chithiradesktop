package com.chithira.printhub.devices;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Database access for registered desktops.
 */
public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByKeyPrefix(String prefix);

    Optional<Device> findByNameIgnoreCase(String name);

    List<Device> findAllByOrderByName();

    long countByStatus(DeviceStatus status);
}
