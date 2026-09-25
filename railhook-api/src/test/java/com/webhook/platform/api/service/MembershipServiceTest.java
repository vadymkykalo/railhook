package com.webhook.platform.api.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.MemberResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipServiceTest {

    private static final String INVITE_BASE = "http://localhost:5173/accept-invite?token=";

    @Mock private UserRepository userRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private EmailService emailService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private MembershipService membershipService;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        TenantContext.set(organizationId);

        // Cost 4: MembershipService takes the shared encoder bean instead of building one, and
        // the production cost of 12 would add ~200ms to every test that creates an invitee for
        // no assertion's benefit.
        membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, tokenBlacklistService,
                new BCryptPasswordEncoder(4), mock(TunnelService.class));

        when(membershipRepository.save(any(Membership.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(emailService.inviteUrl(anyString(), anyString()))
                .thenAnswer(i -> INVITE_BASE + i.getArgument(1) + "&orgId=" + i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** An existing member of the tenant organization, registered with the repository mocks. */
    private Membership existingMember(UUID userId, MembershipRole role, MembershipStatus status) {
        Membership membership = new Membership();
        membership.setUserId(userId);
        membership.setOrganizationId(organizationId);
        membership.setRole(role);
        membership.setStatus(status);

        User user = new User();
        user.setId(userId);
        user.setEmail("member@example.com");

        when(membershipRepository.findByUserIdAndOrganizationId(userId, organizationId))
                .thenReturn(Optional.of(membership));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        return membership;
    }

    /**
     * An invite has to be deliverable by hand, because in the shipped default configuration
     * ({@code EMAIL_ENABLED=false}) nothing is delivered at all.
     *
     * <p>The token used to reach only the API container's log, while the browser was told
     * "Invitation sent". So the owner who issues an invite is handed the accept-invite link
     * back in the response, and can re-issue it when it expires. What is deliberately
     * <em>not</em> handed back is the temporary password minted for a brand-new invitee: it
     * is non-expiring full account access, and {@code EmailService#sendTemporaryPasswordEmail}
     * refuses to log or return it for exactly that reason. The invite link plus
     * forgot-password is the safe way in.
     */
    @Nested
    class Invites {

        @Test
        void invitingANewUserHandsTheOwnerTheLinkToPassOn() {
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

            MemberResponse response = membershipService.addMember(
                    AddMemberRequest.builder().email("new@example.com").role(MembershipRole.DEVELOPER).build(),
                    MembershipRole.OWNER);

            assertThat(response.getStatus()).isEqualTo(MembershipStatus.INVITED);
            assertThat(response.getInviteUrl()).startsWith(INVITE_BASE);
            assertThat(response.getInviteUrl()).contains("orgId=" + organizationId);
            assertThat(response.getInviteExpiresAt()).isAfter(Instant.now());
        }

        @Test
        void invitingAnExistingUserCarriesNoLinkBecauseThereIsNoInvite() {
            User existing = new User();
            existing.setId(UUID.randomUUID());
            existing.setEmail("known@example.com");
            when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
            when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));

            MemberResponse response = membershipService.addMember(
                    AddMemberRequest.builder().email("known@example.com").role(MembershipRole.VIEWER).build(),
                    MembershipRole.OWNER);

            assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(response.getInviteUrl()).isNull();
            assertThat(response.getInviteExpiresAt()).isNull();
        }

        /**
         * An account can be registered for any address and used without ever proving it owns that
         * address. Inviting the address then made that account an ACTIVE member on the spot — and read
         * access needs no verified email — so whoever registered alice@customer.com first read the
         * customer's events the day the owner invited Alice. With email delivery on, an unverified
         * account is not taken to be the person at that address.
         */
        @Test
        void anAccountThatNeverProvedItsAddressIsNotAddedWhenThatAddressIsInvited() {
            when(emailService.isEnabled()).thenReturn(true);
            User squatter = User.builder().id(UUID.randomUUID()).email("alice@customer.com").emailVerified(false).build();
            when(userRepository.existsByEmail("alice@customer.com")).thenReturn(true);
            when(userRepository.findByEmail("alice@customer.com")).thenReturn(Optional.of(squatter));

            assertThatThrownBy(() -> membershipService.addMember(AddMemberRequest.builder().email("alice@customer.com").role(MembershipRole.VIEWER).build(), MembershipRole.OWNER))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("verif");
            verify(membershipRepository, never()).save(any(Membership.class));
        }

        @Test
        void anAccountThatProvedItsAddressIsAddedAsBefore() {
            when(emailService.isEnabled()).thenReturn(true);
            User alice = User.builder().id(UUID.randomUUID()).email("alice@customer.com").emailVerified(true).build();
            when(userRepository.existsByEmail("alice@customer.com")).thenReturn(true);
            when(userRepository.findByEmail("alice@customer.com")).thenReturn(Optional.of(alice));

            MemberResponse response = membershipService.addMember(AddMemberRequest.builder().email("alice@customer.com").role(MembershipRole.VIEWER).build(), MembershipRole.OWNER);

            assertThat(response.getUserId()).isEqualTo(alice.getId());
            assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        void invitingACaseVariantOfAnExistingAddressAddsThatAccount_notANewOne() {
            User existing = new User();
            existing.setId(UUID.randomUUID());
            existing.setEmail("known@example.com");
            when(userRepository.existsByEmail("known@example.com")).thenReturn(true);
            when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existing));

            MemberResponse response = membershipService.addMember(
                    AddMemberRequest.builder().email(" Known@Example.com").role(MembershipRole.VIEWER).build(),
                    MembershipRole.OWNER);

            assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(response.getUserId()).isEqualTo(existing.getId());
        }

        @Test
        void reissuingAnInviteMintsAFreshTokenAndPushesTheExpiryOut() {
            Membership pending = pendingInvite();
            String staleHash = pending.getInviteTokenHash();
            Instant staleExpiry = pending.getInviteExpiresAt();

            MemberResponse response = membershipService.reissueInvite(pending.getUserId(), MembershipRole.OWNER);

            assertThat(pending.getInviteTokenHash()).isNotEqualTo(staleHash);
            assertThat(pending.getInviteExpiresAt()).isAfter(staleExpiry);
            assertThat(pending.getStatus()).isEqualTo(MembershipStatus.INVITED);
            assertThat(response.getInviteUrl()).startsWith(INVITE_BASE);
            assertThat(response.getInviteExpiresAt()).isEqualTo(pending.getInviteExpiresAt());

            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendInviteEmail(eq("pending@example.com"), eq(organizationId.toString()), token.capture());
            assertThat(response.getInviteUrl()).contains(token.getValue());
        }

        @Test
        void reissuingAnInviteNeverMintsAnotherTemporaryPassword() {
            Membership pending = pendingInvite();

            membershipService.reissueInvite(pending.getUserId(), MembershipRole.OWNER);

            verify(emailService, never()).sendTemporaryPasswordEmail(anyString(), anyString());
        }

        @Test
        void onlyAnOwnerMayReissueAnInvite() {
            Membership pending = pendingInvite();

            assertThatThrownBy(() -> membershipService.reissueInvite(pending.getUserId(), MembershipRole.DEVELOPER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void anAcceptedMembershipHasNoInviteToReissue() {
            Membership accepted = pendingInvite();
            accepted.setStatus(MembershipStatus.ACTIVE);
            accepted.setInviteTokenHash(null);
            accepted.setInviteExpiresAt(null);

            assertThatThrownBy(() -> membershipService.reissueInvite(accepted.getUserId(), MembershipRole.OWNER))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void listedMembersCarryTheirInviteExpiryButNeverTheLink() {
            Membership pending = pendingInvite();
            User user = new User();
            user.setId(pending.getUserId());
            user.setEmail("pending@example.com");
            when(membershipRepository.findMembersWithUsers(organizationId))
                    .thenReturn(List.<Object[]>of(new Object[]{pending, user}));

            List<MemberResponse> members = membershipService.getOrganizationMembers();

            assertThat(members).singleElement().satisfies(member -> {
                assertThat(member.getInviteExpiresAt()).isEqualTo(pending.getInviteExpiresAt());
                assertThat(member.getInviteUrl()).isNull();
            });
        }

        /** A membership sitting at INVITED, registered with the repository mocks. */
        private Membership pendingInvite() {
            UUID userId = UUID.randomUUID();
            Membership membership = new Membership();
            membership.setUserId(userId);
            membership.setOrganizationId(organizationId);
            membership.setRole(MembershipRole.DEVELOPER);
            membership.setStatus(MembershipStatus.INVITED);
            membership.setInviteTokenHash("stale-hash");
            membership.setInviteExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

            User user = new User();
            user.setId(userId);
            user.setEmail("pending@example.com");

            when(membershipRepository.findByUserIdAndOrganizationId(userId, organizationId))
                    .thenReturn(Optional.of(membership));
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            return membership;
        }
    }

    /**
     * Invite tokens are never exposed in API responses, and the temporary password minted for a
     * brand-new invitee never reaches the logs: it is delivered only through EmailService.
     */
    @Nested
    class InviteTokenLeak {

        private ListAppender<ILoggingEvent> logAppender;
        private Logger membershipServiceLogger;

        @BeforeEach
        void attachLogAppender() {
            membershipServiceLogger = (Logger) LoggerFactory.getLogger(MembershipService.class);
            logAppender = new ListAppender<>();
            logAppender.start();
            membershipServiceLogger.addAppender(logAppender);
        }

        @AfterEach
        void detachLogAppender() {
            membershipServiceLogger.detachAppender(logAppender);
        }

        @Test
        void memberResponseDoesNotContainInviteTokenField() {
            Field[] fields = MemberResponse.class.getDeclaredFields();

            assertThat(Arrays.stream(fields).map(Field::getName))
                    .as("MemberResponse must not contain inviteToken field to prevent token leak")
                    .doesNotContain("inviteToken");
        }

        @Test
        void memberResponseJsonSerialization() throws Exception {
            MemberResponse response = MemberResponse.builder()
                    .userId(UUID.randomUUID())
                    .email("test@example.com")
                    .role(MembershipRole.DEVELOPER)
                    .status(MembershipStatus.INVITED)
                    .createdAt(Instant.now())
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            String json = mapper.writeValueAsString(response);

            assertThat(json).doesNotContain("inviteToken").doesNotContain("invite_token");
        }

        @Test
        void memberResponseBuilderDoesNotHaveInviteTokenMethod() {
            Method[] methods = MemberResponse.MemberResponseBuilder.class.getDeclaredMethods();

            assertThat(Arrays.stream(methods).map(Method::getName))
                    .as("MemberResponse.Builder must not have inviteToken() method")
                    .doesNotContain("inviteToken");
        }

        @Test
        void tempPasswordNeverReachesLogs_andIsSentViaEmail() {
            String email = "new-invitee@example.com";
            when(userRepository.existsByEmail(email)).thenReturn(false);
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

            membershipService.addMember(
                    AddMemberRequest.builder().email(email).role(MembershipRole.DEVELOPER).build(),
                    MembershipRole.OWNER);

            // The temp password must have been emailed, not just generated and discarded.
            ArgumentCaptor<String> tempPasswordCaptor = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendTemporaryPasswordEmail(eq(email), tempPasswordCaptor.capture());
            String tempPassword = tempPasswordCaptor.getValue();
            assertThat(tempPassword).isNotBlank();

            // No log event at any level may contain the temp password value.
            assertThat(logAppender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(tempPassword));
        }

        @Test
        void existingUserInvite_doesNotSendTemporaryPasswordEmail() {
            String email = "existing-user@example.com";
            User existingUser = User.builder()
                    .id(UUID.randomUUID())
                    .email(email)
                    .passwordHash("$2a$10$existinghash")
                    .build();
            when(userRepository.existsByEmail(email)).thenReturn(true);
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

            membershipService.addMember(
                    AddMemberRequest.builder().email(email).role(MembershipRole.VIEWER).build(),
                    MembershipRole.OWNER);

            // An already-registered user already has a usable password; no temp password
            // should be generated or emailed for them.
            verify(emailService, never()).sendTemporaryPasswordEmail(anyString(), anyString());
        }
    }

    /**
     * Which roles an owner can hand out.
     *
     * <p>OWNER is not granted through the member endpoints, and API_KEY is not a human role at all.
     * Adding a member and changing a member's role are two doors into the same grant, so both refuse
     * the same roles with the same answer — adding a member used to accept either.
     */
    @Nested
    class RoleGrant {

        @ParameterizedTest
        @EnumSource(value = MembershipRole.class, names = {"OWNER", "API_KEY"})
        @DisplayName("adding a member refuses a role that cannot be granted, and creates nothing")
        void addMemberRefusesUngrantableRoles(MembershipRole role) {
            when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> membershipService.addMember(
                    AddMemberRequest.builder().email("new@example.com").role(role).build(),
                    MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(membershipRepository, never()).save(any());
            verify(userRepository, never()).save(any());
        }

        @ParameterizedTest
        @EnumSource(value = MembershipRole.class, names = {"OWNER", "API_KEY"})
        @DisplayName("changing a member's role refuses the same roles, and leaves the membership as it was")
        void changeMemberRoleRefusesUngrantableRoles(MembershipRole role) {
            UUID memberId = UUID.randomUUID();
            Membership membership = existingMember(memberId, MembershipRole.DEVELOPER, MembershipStatus.ACTIVE);

            assertThatThrownBy(() -> membershipService.changeMemberRole(memberId, role, MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            assertThat(membership.getRole()).isEqualTo(MembershipRole.DEVELOPER);
        }
    }

    /**
     * Demoting or removing a member has to take effect now, not in fifteen minutes.
     *
     * <p>{@code JwtAuthenticationFilter} reads {@code organizationId} and {@code role} straight
     * out of the access token and re-checks neither against the database. So a demoted OWNER goes
     * on exercising OWNER authority, and a removed member goes on reaching the organization's
     * data, for the whole remaining access-token lifetime. Refreshing is already blocked — the
     * refresh path 404s once the membership is gone — which is exactly why the access token is
     * the gap worth closing.</p>
     */
    @Nested
    class RevokesSessions {

        private final UUID memberId = UUID.randomUUID();

        @BeforeEach
        void registerMember() {
            existingMember(memberId, MembershipRole.DEVELOPER, null);
        }

        @Test
        void demotingAMemberEndsTheirCurrentSessions() {
            membershipService.changeMemberRole(memberId, MembershipRole.VIEWER, MembershipRole.OWNER);

            verify(tokenBlacklistService).revokeAllUserTokens(memberId);
        }

        @Test
        void removingAMemberEndsTheirCurrentSessions() {
            membershipService.removeMember(memberId, MembershipRole.OWNER);

            verify(tokenBlacklistService).revokeAllUserTokens(memberId);
        }
    }

    /**
     * Suspending a member, rather than deleting them.
     *
     * <p>Removal was the only lever an owner had: a colleague on leave, a stolen laptop or a
     * half-finished offboarding all cost the membership row, and with it the record of who that
     * person was and what they held. {@code MembershipStatus.DISABLED} was in the enum the whole
     * time and nothing ever assigned it.</p>
     *
     * <p>What a suspension has to be, and what these tests pin down: the row and the role survive,
     * the live access token stops working immediately (the same epoch revocation a demotion and a
     * removal already do — an access token is self-contained, so without it the suspension only
     * starts a quarter of an hour later), and it cannot be used to make an organization
     * unadministrable — not by an owner suspending themselves, and not by suspending the last
     * owner who is still able to sign in.</p>
     */
    @Nested
    class Suspension {

        private final UUID ownerId = UUID.randomUUID();
        private final UUID memberId = UUID.randomUUID();
        private Membership membership;

        @BeforeEach
        void registerMember() {
            membership = existingMember(memberId, MembershipRole.DEVELOPER, MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("a suspended member keeps their row and their role, and is marked DISABLED")
        void suspendKeepsTheMembershipAndTheRole() {
            var response = membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
            assertThat(membership.getRole()).isEqualTo(MembershipRole.DEVELOPER);
            assertThat(response.getStatus()).isEqualTo(MembershipStatus.DISABLED);
            assertThat(response.getRole()).isEqualTo(MembershipRole.DEVELOPER);
            verify(membershipRepository, never()).delete(any(Membership.class));
        }

        @Test
        @DisplayName("suspending ends the member's current sessions, not only their next login")
        void suspendEndsCurrentSessions() {
            membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

            verify(tokenBlacklistService).revokeAllUserTokens(memberId);
        }

        @Test
        @DisplayName("only an owner can suspend")
        void suspendRequiresOwner() {
            assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.DEVELOPER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("an owner cannot suspend themselves")
        void ownerCannotSuspendThemselves() {
            membership.setRole(MembershipRole.OWNER);
            when(membershipRepository.findByUserIdAndOrganizationId(ownerId, organizationId))
                    .thenReturn(Optional.of(membership));

            assertThatThrownBy(() -> membershipService.suspendMember(ownerId, ownerId, MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            verify(tokenBlacklistService, never()).revokeAllUserTokens(any());
        }

        @Test
        @DisplayName("the last owner who can still sign in cannot be suspended")
        void lastOwnerCannotBeSuspended() {
            membership.setRole(MembershipRole.OWNER);
            when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                    organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(1L);

            assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        }

        @Test
        @DisplayName("an owner beside another owner who can still sign in may be suspended")
        void anOwnerBesideAnotherOwnerMayBeSuspended() {
            membership.setRole(MembershipRole.OWNER);
            when(membershipRepository.countByOrganizationIdAndRoleAndStatusNot(
                    organizationId, MembershipRole.OWNER, MembershipStatus.DISABLED)).thenReturn(2L);

            membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
        }

        @Test
        @DisplayName("an invite that has not been accepted is not a suspension's subject")
        void anInvitedMemberCannotBeSuspended() {
            membership.setStatus(MembershipStatus.INVITED);

            assertThatThrownBy(() -> membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("reinstating puts the member back to ACTIVE with the role they kept")
        void reinstateRestoresAccess() {
            membership.setStatus(MembershipStatus.DISABLED);

            var response = membershipService.reinstateMember(memberId, MembershipRole.OWNER);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(response.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
            assertThat(response.getRole()).isEqualTo(MembershipRole.DEVELOPER);
        }

        @Test
        @DisplayName("only an owner can reinstate")
        void reinstateRequiresOwner() {
            membership.setStatus(MembershipStatus.DISABLED);

            assertThatThrownBy(() -> membershipService.reinstateMember(memberId, MembershipRole.VIEWER))
                    .isInstanceOfAny(ResponseStatusException.class, ForbiddenException.class);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.DISABLED);
        }

        @Test
        @DisplayName("reinstating a member who is not suspended is a conflict, not a silent no-op")
        void reinstateOnlyAppliesToASuspendedMember() {
            membership.setStatus(MembershipStatus.INVITED);

            assertThatThrownBy(() -> membershipService.reinstateMember(memberId, MembershipRole.OWNER))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            assertThat(membership.getStatus()).isEqualTo(MembershipStatus.INVITED);
        }
    }

    /**
     * Requests refused because of the state a membership is in answer 409 with their message. They
     * used to throw IllegalStateException, which the error handler treats as a server fault: a 500
     * that hides from the person what they need to do.
     */
    @Nested
    class StateConflicts {

        @Test
        void reissuingAnInviteThatIsNoLongerPendingIsAConflict() {
            UUID userId = UUID.randomUUID();
            existingMember(userId, MembershipRole.DEVELOPER, MembershipStatus.ACTIVE);

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
    }
}
