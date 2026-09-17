package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member who loses access to an organization loses the tunnels they opened in it.
 *
 * <p>Removing or suspending a member revoked their sessions and nothing else. A tunnel is not a
 * session — the CLI authenticated once with the tunnel token and never presents an access token
 * again — so the tunnel stayed ACTIVE and the organization's public slug went on forwarding
 * traffic to the machine of somebody who was no longer allowed anywhere near it.
 */
class MemberAccessClosesTunnelsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MembershipService membershipService;
    @Autowired private TunnelService tunnelService;
    @Autowired private TunnelIngressService tunnelIngressService;
    @Autowired private TunnelSessionRepository tunnelSessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID otherOrganizationId;
    private UUID ownerId;
    private UUID memberId;
    private UUID colleagueId;

    @BeforeEach
    void seed() {
        organizationId = organization("member-tunnels");
        otherOrganizationId = organization("member-tunnels-elsewhere");
        ownerId = user();
        memberId = user();
        colleagueId = user();
        membership(ownerId, organizationId, MembershipRole.OWNER);
        membership(memberId, organizationId, MembershipRole.DEVELOPER);
        membership(colleagueId, organizationId, MembershipRole.DEVELOPER);
        membership(memberId, otherOrganizationId, MembershipRole.DEVELOPER);

        when(redisTunnelCoordinator.isActiveInCluster(anyString())).thenReturn(true);
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);
    }

    @Test
    void removingAMember_closesTheirTunnelsInThatOrganization_andNobodyElses() {
        TunnelSession membersTunnel = openTunnel(memberId, organizationId);
        TunnelSession colleaguesTunnel = openTunnel(colleagueId, organizationId);
        TunnelSession membersTunnelElsewhere = openTunnel(memberId, otherOrganizationId);

        TenantContext.runAs(organizationId, () -> membershipService.removeMember(memberId, MembershipRole.OWNER));

        assertClosedAndRefused(membersTunnel);
        assertStillOpen(colleaguesTunnel);
        assertStillOpen(membersTunnelElsewhere);
    }

    @Test
    void suspendingAMember_closesTheirTunnelsInThatOrganization_andNobodyElses() {
        TunnelSession membersTunnel = openTunnel(memberId, organizationId);
        TunnelSession colleaguesTunnel = openTunnel(colleagueId, organizationId);
        TunnelSession membersTunnelElsewhere = openTunnel(memberId, otherOrganizationId);

        TenantContext.runAs(organizationId,
                () -> membershipService.suspendMember(memberId, ownerId, MembershipRole.OWNER));

        assertClosedAndRefused(membersTunnel);
        assertStillOpen(colleaguesTunnel);
        assertStillOpen(membersTunnelElsewhere);
    }

    private void assertClosedAndRefused(TunnelSession tunnel) {
        assertThat(tunnelSessionRepository.findById(tunnel.getId()).orElseThrow().getStatus())
                .isEqualTo(TunnelStatus.CLOSED);
        verify(redisTunnelCoordinator).disconnect(tunnel.getPublicSlug());
        assertThat(tunnelIngressService.forward(tunnel.getPublicSlug(), request(), null))
                .isInstanceOf(TunnelIngressService.Outcome.Refused.class);
    }

    private void assertStillOpen(TunnelSession tunnel) {
        assertThat(tunnelSessionRepository.findById(tunnel.getId()).orElseThrow().getStatus())
                .isEqualTo(TunnelStatus.ACTIVE);
        verify(redisTunnelCoordinator, never()).disconnect(tunnel.getPublicSlug());
    }

    private TunnelSession openTunnel(UUID userId, UUID inOrganization) {
        return TenantContext.callAs(inOrganization,
                () -> tunnelService.createSession(userId, null, 3000, "test-cli"));
    }

    private static TunnelRequestMessage request() {
        return TunnelRequestMessage.builder()
                .requestId(UUID.randomUUID().toString())
                .method("POST")
                .path("/")
                .build();
    }

    private UUID organization(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, plan_id) "
                + "VALUES (?, ?, (SELECT id FROM plans WHERE name = 'free'))", id, name);
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, status) VALUES (?, ?, 'x', 'ACTIVE')",
                id, id + "@example.com");
        return id;
    }

    private void membership(UUID userId, UUID inOrganization, MembershipRole role) {
        jdbcTemplate.update("INSERT INTO memberships (user_id, organization_id, role, status) "
                + "VALUES (?, ?, ?, 'ACTIVE')", userId, inOrganization, role.name());
    }
}
