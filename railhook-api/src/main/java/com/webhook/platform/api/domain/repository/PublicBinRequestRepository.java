package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.PublicBinRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PublicBinRequestRepository extends JpaRepository<PublicBinRequest, Long> {

    List<PublicBinRequest> findByBinIdOrderByIdDesc(UUID binId, Pageable pageable);

    /** Deletes everything in the bin older than its {@code keep} newest requests. */
    @Modifying
    @Query(value = "DELETE FROM public_bin_requests WHERE bin_id = :binId AND id <= ("
            + "SELECT id FROM public_bin_requests WHERE bin_id = :binId ORDER BY id DESC OFFSET :keep LIMIT 1)",
            nativeQuery = true)
    int trimToNewest(@Param("binId") UUID binId, @Param("keep") int keep);
}
