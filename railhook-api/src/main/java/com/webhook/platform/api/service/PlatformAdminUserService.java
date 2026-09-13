package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AdminUserResponse;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every account on this deployment, searchable, for the platform admin. */
@Service
@RequiredArgsConstructor
public class PlatformAdminUserService {

    private final UserRepository userRepository;
    private final PlatformAdminAccountFacts accountFacts;

    @SystemTenant("the platform admin's account list spans every organization and belongs to none")
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(String search, Pageable pageable) {
        String normalized = (search == null || search.isBlank()) ? null : search.trim();
        Page<User> page = userRepository.searchForOperator(normalized, pageable);

        List<UUID> ids = page.getContent().stream().map(User::getId).toList();
        Map<UUID, List<String>> methods = accountFacts.signInMethods(page.getContent());
        Map<UUID, Instant> lastSeen = accountFacts.lastSeen(ids);
        Map<UUID, List<AdminUserResponse.OrganizationMembership>> organizations = accountFacts.organizations(ids);

        return page.map(user -> AdminUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                .status(user.getStatus())
                .signInMethods(methods.getOrDefault(user.getId(), List.of()))
                .organizations(organizations.getOrDefault(user.getId(), List.of()))
                .createdAt(user.getCreatedAt())
                .lastSeenAt(lastSeen.get(user.getId()))
                .build());
    }
}
