package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    Optional<User> findByVerificationToken(String verificationToken);
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    long countByCreatedAtGreaterThanEqual(Instant since);

    /** The platform admin's account search: by address or name, a null term matching everyone. */
    @Query("SELECT u FROM User u WHERE :search IS NULL "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))")
    Page<User> searchForOperator(@Param("search") String search, Pageable pageable);
}
