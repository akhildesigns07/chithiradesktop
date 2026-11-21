package com.chithira.printhub.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Database access for user accounts.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findAllByOrderByUsername();

    @Query("select count(u) from AppUser u join u.roles r where r = :role and u.active = true")
    long countActiveUsersWithRole(@Param("role") Role role);

    /** Active users holding the Admin role (there must always be at least one). */
    default long countActiveAdmins() { return countActiveUsersWithRole(Role.ADMIN); }
}
