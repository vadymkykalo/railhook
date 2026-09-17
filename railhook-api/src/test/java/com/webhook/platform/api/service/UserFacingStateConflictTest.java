package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Requests refused because of the state a resource is in answer 409 with their message. They used
 * to throw IllegalStateException, which the error handler now treats as a server fault: a 500 that
 * hides from the person what they need to do.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserFacingStateConflictTest {

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private EmailService emailService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private TransformationRepository transformationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private IncomingDestinationRepository incomingDestinationRepository;

    private MembershipService membershipService;
    private TransformationService transformationService;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);
        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService,
                new BCryptPasswordEncoder(4));
        transformationService = new TransformationService(
                transformationRepository, projectRepository, subscriptionRepository,
                incomingDestinationRepository, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reissuingAnInviteThatIsNoLongerPendingIsAConflict() {
        UUID userId = UUID.randomUUID();
        Membership active = Membership.builder()
                .userId(userId).organizationId(organizationId)
                .role(MembershipRole.DEVELOPER).status(MembershipStatus.ACTIVE).build();
        when(membershipRepository.findByUserIdAndOrganizationId(userId, organizationId))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> membershipService.reissueInvite(userId, MembershipRole.OWNER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no pending invite");
    }

    @Test
    void acceptingAnInviteAlreadyAcceptedIsAConflict() {
        UUID userId = UUID.randomUUID();
        String token = "invite-token";
        Membership accepted = Membership.builder()
                .userId(userId).organizationId(organizationId)
                .role(MembershipRole.DEVELOPER).status(MembershipStatus.ACTIVE).build();
        when(membershipRepository.findByInviteTokenHash(CryptoUtils.hashApiKey(token)))
                .thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> membershipService.acceptInvite(organizationId, token, userId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already accepted");
    }

    @Test
    void deletingATransformationStillInUseIsAConflict() {
        UUID projectId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(transformationRepository.findByIdAndProjectId(id, projectId))
                .thenReturn(Optional.of(Transformation.builder().id(id).projectId(projectId).build()));
        when(subscriptionRepository.countByTransformationId(id)).thenReturn(2L);

        assertThatThrownBy(() -> transformationService.delete(projectId, id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("referenced by 2 subscriptions");
    }
}
