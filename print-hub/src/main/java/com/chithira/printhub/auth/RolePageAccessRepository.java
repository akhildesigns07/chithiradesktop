package com.chithira.printhub.auth;

import com.chithira.printhub.common.PageKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Database access for the page-access matrix.
 */
public interface RolePageAccessRepository extends JpaRepository<RolePageAccess, Long> {
    Optional<RolePageAccess> findByRoleAndPageKey(Role role, PageKey pageKey);
}
