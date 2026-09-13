package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.SignInHandoff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SignInHandoffRepository extends JpaRepository<SignInHandoff, String> {

    /**
     * Claims the code, compare-and-set: only the one caller that flips {@code consumed_at} from
     * null gets a session. A replay, or a second tab racing the first, updates nothing.
     */
    @Modifying(clearAutomatically = true)
    @Query("update SignInHandoff h set h.consumedAt = :now "
            + "where h.codeHash = :codeHash and h.consumedAt is null and h.expiresAt > :now")
    int consume(@Param("codeHash") String codeHash, @Param("now") Instant now);

    @Modifying
    @Query("delete from SignInHandoff h where h.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
