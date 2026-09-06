package com.webhook.platform.api.security;

import com.webhook.platform.api.tenancy.TenantContext;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.MemberResponse;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.MembershipService;
import com.webhook.platform.api.service.TokenBlacklistService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that invite tokens are never exposed in API responses.
 * Security fix for invite token leak vulnerability.
 */
class InviteTokenLeakTest {

    private final UUID tenantOrgId = UUID.randomUUID();


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(tenantOrgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void testMemberResponseDoesNotContainInviteTokenField() {
        // Given: Create a MemberResponse using builder
        MemberResponse response = MemberResponse.builder()
                .userId(java.util.UUID.randomUUID())
                .email("test@example.com")
                .role(com.webhook.platform.api.domain.enums.MembershipRole.DEVELOPER)
                .status(com.webhook.platform.api.domain.enums.MembershipStatus.INVITED)
                .createdAt(java.time.Instant.now())
                .build();

        // Then: MemberResponse should not have inviteToken field accessible
        // Reflection check to ensure field doesn't exist
        java.lang.reflect.Field[] fields = MemberResponse.class.getDeclaredFields();
        boolean hasInviteToken = false;
        for (java.lang.reflect.Field field : fields) {
            if (field.getName().equals("inviteToken")) {
                hasInviteToken = true;
                break;
            }
        }

        assertFalse(hasInviteToken, 
            "MemberResponse must not contain inviteToken field to prevent token leak");
    }

    @Test
    void testMemberResponseJsonSerialization() throws Exception {
        // Given: A MemberResponse
        MemberResponse response = MemberResponse.builder()
                .userId(java.util.UUID.randomUUID())
                .email("test@example.com")
                .role(com.webhook.platform.api.domain.enums.MembershipRole.DEVELOPER)
                .status(com.webhook.platform.api.domain.enums.MembershipStatus.INVITED)
                .createdAt(java.time.Instant.now())
                .build();

        // When: Serialize to JSON
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String json = mapper.writeValueAsString(response);

        // Then: JSON should not contain inviteToken
        assertFalse(json.contains("inviteToken"), 
            "Serialized JSON must not contain inviteToken field");
        assertFalse(json.contains("invite_token"), 
            "Serialized JSON must not contain invite_token field (snake_case)");
    }

    @Test
    void testMemberResponseBuilderDoesNotHaveInviteTokenMethod() {
        // Verify that builder doesn't have inviteToken() method
        java.lang.reflect.Method[] methods = MemberResponse.MemberResponseBuilder.class.getDeclaredMethods();
        boolean hasInviteTokenMethod = false;
        for (java.lang.reflect.Method method : methods) {
            if (method.getName().equals("inviteToken")) {
                hasInviteTokenMethod = true;
                break;
            }
        }

        assertFalse(hasInviteTokenMethod,
            "MemberResponse.Builder must not have inviteToken() method");
    }

    // -----------------------------------------------------------------
    // The temp password generated for a brand-new invited user must
    // never reach the logs, and must be delivered exclusively via EmailService.
    // -----------------------------------------------------------------

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
        if (membershipServiceLogger != null && logAppender != null) {
            membershipServiceLogger.detachAppender(logAppender);
        }
    }

    @Test
    void testTempPasswordNeverReachesLogs_andIsSentViaEmail() {
        UserRepository userRepository = mock(UserRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        EmailService emailService = mock(EmailService.class);

        MembershipService membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, mock(TokenBlacklistService.class), new BCryptPasswordEncoder(4));

        UUID orgId = UUID.randomUUID();
        String email = "new-invitee@example.com";

        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(membershipRepository.existsByUserIdAndOrganizationId(any(), eq(orgId))).thenReturn(false);
        when(membershipRepository.save(any(Membership.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AddMemberRequest request = AddMemberRequest.builder()
                .email(email)
                .role(MembershipRole.DEVELOPER)
                .build();

        membershipService.addMember( request, MembershipRole.OWNER);

        // The temp password must have been emailed, not just generated and discarded.
        ArgumentCaptor<String> tempPasswordCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendTemporaryPasswordEmail(eq(email), tempPasswordCaptor.capture());
        String tempPassword = tempPasswordCaptor.getValue();
        assertNotNull(tempPassword);
        assertFalse(tempPassword.isBlank());

        // No log event at any level may contain the temp password value.
        for (ILoggingEvent event : logAppender.list) {
            String formatted = event.getFormattedMessage();
            assertFalse(formatted.contains(tempPassword),
                    "Log message must not contain the temp password: " + formatted);
        }
    }

    @Test
    void testExistingUserInvite_doesNotSendTemporaryPasswordEmail() {
        UserRepository userRepository = mock(UserRepository.class);
        MembershipRepository membershipRepository = mock(MembershipRepository.class);
        EmailService emailService = mock(EmailService.class);

        MembershipService membershipService = new MembershipService(
                userRepository, membershipRepository, emailService, mock(TokenBlacklistService.class), new BCryptPasswordEncoder(4));

        UUID orgId = UUID.randomUUID();
        String email = "existing-user@example.com";
        User existingUser = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash("$2a$10$existinghash")
                .build();

        when(userRepository.existsByEmail(email)).thenReturn(true);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));
        when(membershipRepository.existsByUserIdAndOrganizationId(existingUser.getId(), orgId)).thenReturn(false);
        when(membershipRepository.save(any(Membership.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AddMemberRequest request = AddMemberRequest.builder()
                .email(email)
                .role(MembershipRole.VIEWER)
                .build();

        membershipService.addMember( request, MembershipRole.OWNER);

        // An already-registered user already has a usable password; no temp password
        // should be generated or emailed for them.
        org.mockito.Mockito.verify(emailService, org.mockito.Mockito.never())
                .sendTemporaryPasswordEmail(anyString(), anyString());
    }
}
