package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    /** Addresses are stored normalized, so pass {@code EmailAddresses.normalize(address)}. */
    Optional<User> findByEmail(String email);
    /** Addresses are stored normalized, so pass {@code EmailAddresses.normalize(address)}. */
    boolean existsByEmail(String email);
    Optional<User> findByVerificationToken(String verificationToken);
    Optional<User> findByPasswordResetToken(String passwordResetToken);

    long countByCreatedAtGreaterThanEqual(Instant since);

    long countByCreatedAtGreaterThanEqualAndEmailVerifiedTrue(Instant since);

    /** {@code [day, count]} of accounts created since then, one row per calendar day that has any. */
    @Query("SELECT CAST(u.createdAt AS LocalDate), COUNT(u) FROM User u WHERE u.createdAt >= :since "
            + "GROUP BY CAST(u.createdAt AS LocalDate)")
    List<Object[]> countPerDaySince(@Param("since") Instant since);

    /** The platform admin's account search: by address or name, a null term matching everyone. */
    @Query("SELECT u FROM User u WHERE :search IS NULL "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) "
            + "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))")
    Page<User> searchForOperator(@Param("search") String search, Pageable pageable);
}
