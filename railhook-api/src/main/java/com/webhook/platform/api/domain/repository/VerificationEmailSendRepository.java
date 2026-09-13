package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.VerificationEmailSend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface VerificationEmailSendRepository extends JpaRepository<VerificationEmailSend, UUID> {

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    @Modifying
    @Query("delete from VerificationEmailSend s where s.createdAt < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from VerificationEmailSend s where s.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
