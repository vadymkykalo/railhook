package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.CapturedRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CapturedRequestRepository extends JpaRepository<CapturedRequest, UUID> {

    Page<CapturedRequest> findByTestEndpointIdOrderByReceivedAtDesc(UUID testEndpointId, Pageable pageable);

    List<CapturedRequest> findByTestEndpointIdOrderByReceivedAtDesc(UUID testEndpointId);

    @Modifying
    @Query("DELETE FROM CapturedRequest c WHERE c.testEndpointId = :testEndpointId")
    int deleteByTestEndpointId(@Param("testEndpointId") UUID testEndpointId);

    @Modifying
    @Query("DELETE FROM CapturedRequest c WHERE c.testEndpointId IN " +
           "(SELECT t.id FROM TestEndpoint t WHERE t.expiresAt < :now)")
    int deleteExpiredRequests(@Param("now") java.time.Instant now);

    long countByTestEndpointId(UUID testEndpointId);
}
