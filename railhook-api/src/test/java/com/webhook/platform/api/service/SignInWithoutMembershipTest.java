package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.LoginRequest;
import com.webhook.platform.api.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An account whose last organization is gone — removed from the only one it was invited to, or
 * that organization deleted — still has to be able to sign in.
 *
 * <p>Sign-in answered 404 "No organization membership found", for a password and for Google alike,
 * and nothing ever gave the account an organization again. Registering again was refused because
 * the address was taken, and erasing the account needs a session. The person was locked out for
 * good. They now get an organization of their own, as a new account does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignInWithoutMembershipTest {

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private PlanRepository planRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private UserSessionService userSessionService;
    @Mock private EmailService emailService;

    private static final String PASSWORD = "the-right-password";
    private static final SessionOrigin ORIGIN =
            SessionOrigin.of(SessionClient.WEB, "Mozilla/5.0", "198.51.100.4");

    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        authService = new AuthService(userRepository, organizationRepository, membershipRepository,
                planRepository, jwtUtil, encoder, tokenBlacklistService, userSessionService,
                new AccountLockoutService(userRepository, true, 3, 60, 900, 60), emailService,
                mock(VerificationMailBudget.class), true);

        user = User.builder()
                .id(UUID.randomUUID())
                .email("bob@example.com")
                .fullName("Bob")
                .passwordHash(encoder.encode(PASSWORD))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        // What the repository holds: nothing, until the sign-in saves a membership.
        List<Membership> stored = new ArrayList<>();
        when(membershipRepository.findByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenAnswer(inv -> List.copyOf(stored));
        when(planRepository.findByName("free")).thenReturn(Optional.of(Plan.builder().name("free").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> {
            Organization org = inv.getArgument(0);
            org.setId(UUID.randomUUID());
            return org;
        });
        when(membershipRepository.save(any(Membership.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh");
        when(jwtUtil.getJtiFromToken("refresh")).thenReturn(UUID.randomUUID().toString());
        when(jwtUtil.getExpirationFromToken("refresh"))
                .thenReturn(new Date(System.currentTimeMillis() + 86_400_000L));
    }

    @Test
    @DisplayName("a password sign-in with no organization left gets one of its own")
    void passwordSignIn() {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword(PASSWORD);

        authService.login(request, ORIGIN);

        assertOwnsANewOrganization();
    }

    @Test
    @DisplayName("so does a sign-in through Google")
    void externalSignIn() {
        authService.issueSessionFor(user, ORIGIN);

        assertOwnsANewOrganization();
    }

    private void assertOwnsANewOrganization() {
        ArgumentCaptor<Membership> membership = ArgumentCaptor.forClass(Membership.class);
        verify(membershipRepository).save(membership.capture());
        assertThat(membership.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(membership.getValue().getRole()).isEqualTo(MembershipRole.OWNER);
        ArgumentCaptor<Organization> org = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(org.capture());
        assertThat(org.getValue().getName()).isNotBlank();
    }
}
