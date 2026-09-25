package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.SessionClient;
import com.webhook.platform.api.domain.enums.UserStatus;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// EmailService is a mock: the decision to send is asserted, and the timestamps that make it once.
@TestPropertySource(properties = "app.onboarding-emails.enabled=true")
class OnboardingMailIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean private EmailService emailService;

    @Autowired private OnboardingMailService onboarding;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private IncomingSourceRepository incomingSourceRepository;
    @Autowired private IncomingEventRepository incomingEventRepository;

    private record Account(User user, UUID organizationId) {}

    private Account account(Duration welcomedAgo) {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        User user = userRepository.save(User.builder()
                .email("owner-" + UUID.randomUUID() + "@acme.test")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .onboardingWelcomeSentAt(Instant.now().minus(welcomedAgo))
                .build());
        Organization organization = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build());
        membershipRepository.save(Membership.builder()
                .userId(user.getId())
                .organizationId(organization.getId())
                .role(MembershipRole.OWNER)
                .build());
        return new Account(user, organization.getId());
    }

    private UUID project(UUID organizationId) {
        return projectRepository.save(Project.builder().organizationId(organizationId).name("Payments").build()).getId();
    }

    private Instant nudgedAt(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getOnboardingNudgeSentAt();
    }

    private void onboardingEnabled(boolean enabled) {
        OnboardingMailService target = AopTestUtils.getUltimateTargetObject(onboarding);
        ReflectionTestUtils.setField(target, "enabled", enabled);
    }

    @Nested
    @DisplayName("the day-2 nudge")
    class Nudge {

        @Test
        @DisplayName("reaches a verified owner of three days whose organization has sent nothing, exactly once")
        void nudgesOnce() {
            Account stuck = account(Duration.ofDays(3));

            onboarding.sendDueNudges();
            onboarding.sendDueNudges();

            verify(emailService, times(1)).sendOnboardingNudgeEmail(stuck.user().getEmail());
            assertThat(nudgedAt(stuck.user())).isNotNull();
        }

        @Test
        @DisplayName("does not reach an organization that has sent an event")
        void notWithAnEvent() {
            Account busy = account(Duration.ofDays(3));
            eventRepository.save(Event.builder()
                    .organizationId(busy.organizationId()).projectId(project(busy.organizationId()))
                    .eventType("payment.succeeded").payload("{}").build());

            onboarding.sendDueNudges();

            verify(emailService, never()).sendOnboardingNudgeEmail(busy.user().getEmail());
            assertThat(nudgedAt(busy.user())).isNull();
        }

        @Test
        @DisplayName("nor one that has received an incoming event")
        void notWithAnIncomingEvent() {
            Account receiving = account(Duration.ofDays(3));
            IncomingSource source = incomingSourceRepository.save(IncomingSource.builder()
                    .organizationId(receiving.organizationId()).projectId(project(receiving.organizationId()))
                    .name("Stripe").slug("stripe-" + UUID.randomUUID().toString().substring(0, 8))
                    .ingressPathToken(UUID.randomUUID().toString().replace("-", ""))
                    .build());
            incomingEventRepository.save(IncomingEvent.builder()
                    .organizationId(receiving.organizationId()).incomingSourceId(source.getId())
                    .requestId(UUID.randomUUID().toString()).method("POST")
                    .build());

            onboarding.sendDueNudges();

            verify(emailService, never()).sendOnboardingNudgeEmail(receiving.user().getEmail());
        }

        @Test
        @DisplayName("waits two days after the welcome")
        void notTooEarly() {
            Account fresh = account(Duration.ofHours(20));

            onboarding.sendDueNudges();

            verify(emailService, never()).sendOnboardingNudgeEmail(fresh.user().getEmail());
            assertThat(nudgedAt(fresh.user())).isNull();
        }

        @Test
        @DisplayName("skips a suspended organization, a disabled account and an unverified address")
        void skipsWhoShouldNotBeMailed() {
            Account suspended = account(Duration.ofDays(3));
            Organization organization = organizationRepository.findById(suspended.organizationId()).orElseThrow();
            organization.setSuspendedAt(Instant.now());
            organizationRepository.save(organization);

            Account disabled = account(Duration.ofDays(3));
            User disabledUser = userRepository.findById(disabled.user().getId()).orElseThrow();
            disabledUser.setStatus(UserStatus.DISABLED);
            userRepository.save(disabledUser);

            Account unverified = account(Duration.ofDays(3));
            User unverifiedUser = userRepository.findById(unverified.user().getId()).orElseThrow();
            unverifiedUser.setEmailVerified(false);
            userRepository.save(unverifiedUser);

            onboarding.sendDueNudges();

            verify(emailService, never()).sendOnboardingNudgeEmail(suspended.user().getEmail());
            verify(emailService, never()).sendOnboardingNudgeEmail(disabled.user().getEmail());
            verify(emailService, never()).sendOnboardingNudgeEmail(unverified.user().getEmail());
        }

        @Test
        @DisplayName("sends nothing and marks nobody when the deployment has not turned onboarding mail on")
        void notWhenDisabled() {
            Account stuck = account(Duration.ofDays(3));
            onboardingEnabled(false);
            try {
                onboarding.sendDueNudges();
            } finally {
                onboardingEnabled(true);
            }

            verify(emailService, never()).sendOnboardingNudgeEmail(anyString());
            assertThat(nudgedAt(stuck.user())).isNull();
        }
    }

    @Nested
    @DisplayName("the welcome")
    class Welcome {

        private final SessionOrigin origin = SessionOrigin.of(SessionClient.WEB, "a-browser", "198.51.100.4");

        private RegisterRequest registration(String email) {
            return RegisterRequest.builder()
                    .email(email).password("Str0ng!Passw0rd").organizationName("Acme").build();
        }

        @Test
        @DisplayName("is sent when the address is verified, and only once")
        void onVerification() {
            when(emailService.isEnabled()).thenReturn(true);
            String email = "verify-" + UUID.randomUUID() + "@acme.test";

            authService.register(registration(email), origin);
            verify(emailService, never()).sendWelcomeEmail(anyString());

            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendVerificationEmail(eq(email), token.capture());
            authService.verifyEmail(token.getValue());

            verify(emailService, times(1)).sendWelcomeEmail(email);
            User user = userRepository.findByEmail(email).orElseThrow();
            assertThat(user.getOnboardingWelcomeSentAt()).isNotNull();

            onboarding.welcome(user);
            verify(emailService, times(1)).sendWelcomeEmail(email);
        }

        @Test
        @DisplayName("is sent at registration when the account is created already verified")
        void atRegistrationWhenAlreadyVerified() {
            when(emailService.isEnabled()).thenReturn(false);
            String email = "auto-" + UUID.randomUUID() + "@acme.test";

            authService.register(registration(email), origin);

            verify(emailService, times(1)).sendWelcomeEmail(email);
        }

        @Test
        @DisplayName("is not sent when the deployment has not turned onboarding mail on")
        void notWhenDisabled() {
            when(emailService.isEnabled()).thenReturn(false);
            String email = "off-" + UUID.randomUUID() + "@acme.test";
            onboardingEnabled(false);
            try {
                authService.register(registration(email), origin);
            } finally {
                onboardingEnabled(true);
            }

            verify(emailService, never()).sendWelcomeEmail(anyString());
            assertThat(userRepository.findByEmail(email).orElseThrow().getOnboardingWelcomeSentAt()).isNull();
        }
    }
}
