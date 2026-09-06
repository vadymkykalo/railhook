package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AddMemberRequest;
import com.webhook.platform.api.dto.ChangeMemberRoleRequest;
import com.webhook.platform.api.dto.MemberResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireOrgAccess;
import com.webhook.platform.api.service.MembershipService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
@Tag(name = "Organizations", description = "Organization member management")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
public class MemberController {

    private final MembershipService membershipService;

    public MemberController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Operation(summary = "List members", description = "Returns all members of the organization")
    @RequireOrgAccess
    @GetMapping
    public ResponseEntity<List<MemberResponse>> getMembers(
            @PathVariable("orgId") UUID orgId,
            AuthContext auth) {
        auth.requireJwt();
        List<MemberResponse> response = membershipService.getOrganizationMembers();
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Add member", description = "Invites a user to the organization")
    @ApiResponse(responseCode = "201", description = "Member added")
    @RequireOrgAccess
    @RequireQuota(QuotaType.MEMBERS)
    @PostMapping
    public ResponseEntity<MemberResponse> addMember(
            @PathVariable("orgId") UUID orgId,
            @Valid @RequestBody AddMemberRequest request,
            AuthContext auth) {
        auth.requireJwt();
        MemberResponse response = membershipService.addMember(
                request,
                auth.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Change member role", description = "Updates a member's role (OWNER, ADMIN, MEMBER, VIEWER)")
    @RequireOrgAccess
    @PatchMapping("/{userId}")
    public ResponseEntity<MemberResponse> changeMemberRole(
            @PathVariable("orgId") UUID orgId,
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody ChangeMemberRoleRequest request,
            AuthContext auth) {
        auth.requireJwt();
        MemberResponse response = membershipService.changeMemberRole(
                userId,
                request.getRole(),
                auth.role());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Re-issue invite",
            description = "Issues a fresh invite token for a member whose invite is still pending, "
                    + "replacing the previous one and restarting its 48-hour expiry. The accept-invite "
                    + "link is returned so an owner can pass it on when email delivery is not configured.")
    @ApiResponse(responseCode = "200", description = "Invite re-issued")
    @RequireOrgAccess
    @PostMapping("/{userId}/invite")
    public ResponseEntity<MemberResponse> reissueInvite(
            @PathVariable("orgId") UUID orgId,
            @PathVariable("userId") UUID userId,
            AuthContext auth) {
        auth.requireJwt();
        MemberResponse response = membershipService.reissueInvite(userId, auth.role());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Suspend member",
            description = "Suspends a member: the membership and its role are kept, the member is "
                    + "refused access, and their current sessions end immediately")
    @ApiResponse(responseCode = "200", description = "Member suspended")
    @RequireOrgAccess
    @RequireAccess(AccessLevel.OWNER)
    @PostMapping("/{userId}/suspend")
    public ResponseEntity<MemberResponse> suspendMember(
            @PathVariable("orgId") UUID orgId,
            @PathVariable("userId") UUID userId,
            AuthContext auth) {
        auth.requireJwt();
        auth.requireOwnerAccess();
        MemberResponse response = membershipService.suspendMember(
                userId,
                auth.requireUserId(),
                auth.role());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Reinstate member",
            description = "Lifts a suspension, restoring the member's access in the role they kept")
    @ApiResponse(responseCode = "200", description = "Member reinstated")
    @RequireOrgAccess
    @RequireAccess(AccessLevel.OWNER)
    @PostMapping("/{userId}/reinstate")
    public ResponseEntity<MemberResponse> reinstateMember(
            @PathVariable("orgId") UUID orgId,
            @PathVariable("userId") UUID userId,
            AuthContext auth) {
        auth.requireJwt();
        auth.requireOwnerAccess();
        MemberResponse response = membershipService.reinstateMember(userId, auth.role());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Remove member", description = "Removes a member from the organization")
    @ApiResponse(responseCode = "204", description = "Member removed")
    @RequireOrgAccess
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable("orgId") UUID orgId,
            @PathVariable("userId") UUID userId,
            AuthContext auth) {
        auth.requireJwt();
        membershipService.removeMember( userId, auth.role());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Accept invite", description = "Accepts an organization membership invite using the invite token")
    @ApiResponse(responseCode = "200", description = "Invite accepted")
    @PostMapping("/accept-invite")
    public ResponseEntity<MemberResponse> acceptInvite(
            @PathVariable("orgId") UUID orgId,
            @RequestParam("token") String token,
            AuthContext auth) {
        MemberResponse response = membershipService.acceptInvite(orgId, token, auth.requireUserId());
        return ResponseEntity.ok(response);
    }
}
