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

    // Keeps at most `keep` newest requests whose bodies fit in budgetBytes; the newest always stays.
    @Modifying
    @Query(value = "DELETE FROM public_bin_requests WHERE id IN ("
            + "SELECT id FROM (SELECT id, "
            + "ROW_NUMBER() OVER (ORDER BY id DESC) AS position, "
            + "SUM(COALESCE(octet_length(body), 0)) OVER (ORDER BY id DESC) AS running_bytes "
            + "FROM public_bin_requests WHERE bin_id = :binId) newest_first "
            + "WHERE position > 1 AND (position > :keep OR running_bytes > :budgetBytes))",
            nativeQuery = true)
    int trimToNewest(@Param("binId") UUID binId, @Param("keep") int keep, @Param("budgetBytes") long budgetBytes);
}
