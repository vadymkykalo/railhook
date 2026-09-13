package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    @Modifying
    @Query("delete from UserIdentity i where i.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
