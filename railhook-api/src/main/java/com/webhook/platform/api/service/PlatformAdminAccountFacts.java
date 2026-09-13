package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserIdentityRepository;
import com.webhook.platform.api.domain.repository.UserSessionRepository;
import com.webhook.platform.api.dto.AdminUserResponse;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What the platform admin panel says about an account besides its own row: how it signs in, when
 * it was last active, and which organizations it belongs to.
 *
 * <p>One query per question for a whole page of accounts, never one per account — the lists these
 * feed are fifty rows long.
 */
@Component
@RequiredArgsConstructor
public class PlatformAdminAccountFacts {

    /** An account with a password hash can sign in with it; identity providers are listed by name. */
    public static final String PASSWORD = "PASSWORD";

    private final UserIdentityRepository userIdentityRepository;
    private final UserSessionRepository userSessionRepository;
    private final MembershipRepository membershipRepository;

    @SystemTenant("how an account signs in is account-level and belongs to no organization")
    public Map<UUID, List<String>> signInMethods(Collection<User> users) {
        Map<UUID, List<String>> methods = new HashMap<>();
        if (users.isEmpty()) {
            return methods;
        }
        for (User user : users) {
            List<String> own = new ArrayList<>();
            if (user.getPasswordHash() != null) {
                own.add(PASSWORD);
            }
            methods.put(user.getId(), own);
        }
        List<UUID> ids = users.stream().map(User::getId).toList();
        for (Object[] row : userIdentityRepository.findProvidersOfUsers(ids)) {
            String provider = ((String) row[1]).toUpperCase(Locale.ROOT);
            List<String> own = methods.computeIfAbsent((UUID) row[0], id -> new ArrayList<>());
            if (!own.contains(provider)) {
                own.add(provider);
            }
        }
        return methods;
    }

    @SystemTenant("sessions are account-level; user_sessions is deliberately not tenant-scoped")
    public Map<UUID, Instant> lastSeen(Collection<UUID> userIds) {
        Map<UUID, Instant> lastSeen = new HashMap<>();
        if (userIds.isEmpty()) {
            return lastSeen;
        }
        for (Object[] row : userSessionRepository.findLastSeenOfUsers(userIds)) {
            lastSeen.put((UUID) row[0], (Instant) row[1]);
        }
        return lastSeen;
    }

    @SystemTenant("an account's organizations are, by definition, more than one tenant")
    public Map<UUID, List<AdminUserResponse.OrganizationMembership>> organizations(Collection<UUID> userIds) {
        Map<UUID, List<AdminUserResponse.OrganizationMembership>> organizations = new HashMap<>();
        if (userIds.isEmpty()) {
            return organizations;
        }
        for (Object[] row : membershipRepository.findOrganizationsOfUsers(userIds)) {
            organizations.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
                    .add(AdminUserResponse.OrganizationMembership.builder()
                            .id((UUID) row[1])
                            .name((String) row[2])
                            .role((MembershipRole) row[3])
                            .build());
        }
        return organizations;
    }
}
