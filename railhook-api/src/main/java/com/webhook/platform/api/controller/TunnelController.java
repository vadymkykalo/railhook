package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.entity.TunnelRequestLog;
import com.webhook.platform.api.domain.entity.TunnelSession;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import com.webhook.platform.api.dto.TunnelCreateResponse;
import com.webhook.platform.api.dto.TunnelSessionResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.service.TunnelBandwidthService;
import com.webhook.platform.api.service.TunnelRegistry;
import com.webhook.platform.api.service.TunnelService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tunnels")
@Tag(name = "Tunnels", description = "CLI tunnel session management")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class TunnelController {

    private final TunnelService tunnelService;
    private final TunnelRegistry tunnelRegistry;
    private final TunnelRequestLogRepository requestLogRepository;
    private final TunnelBandwidthService bandwidthService;

    @Operation(operationId = "createTunnel", summary = "Create tunnel session",
            description = "Creates a new tunnel session for CLI use. The tunnelToken is returned only once.")
    @RequireQuota(QuotaType.TUNNELS)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping
    public ResponseEntity<TunnelCreateResponse> create(
            @RequestParam("localPort") int localPort,
            @RequestParam(value = "projectId", required = false) UUID projectId,
            @RequestParam(value = "clientInfo", required = false) String clientInfo,
            AuthContext auth) {
        auth.requireWriteAccess();
        if (projectId != null) {
            auth.validateProjectAccess(projectId);
        }

        TunnelSession session = tunnelService.createSession(
                auth.requireUserId(), projectId, localPort, clientInfo);

        TunnelCreateResponse response = TunnelCreateResponse.builder()
                .id(session.getId())
                .tunnelToken(session.getTunnelToken())
                .publicSlug(session.getPublicSlug())
                .publicUrl(tunnelService.buildPublicUrl(session.getPublicSlug()))
                .localPort(session.getLocalPort())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "listTunnels", summary = "List active tunnels", description = "Lists all active tunnel sessions for the organization, optionally filtered by project")
    @GetMapping
    public ResponseEntity<List<TunnelSessionResponse>> list(
            @RequestParam(value = "projectId", required = false) UUID projectId,
            AuthContext auth) {
        if (projectId != null) {
            auth.validateProjectAccess(projectId);
        }
        List<TunnelSessionResponse> sessions = projectId != null
                ? tunnelService.listActiveByProject( projectId)
                : tunnelService.listActive();
        return ResponseEntity.ok(sessions);
    }

    @Operation(summary = "Close tunnel session", description = "Closes an active tunnel session belonging to your organization")
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(
            @PathVariable("sessionId") UUID sessionId,
            AuthContext auth) {
        auth.requireWriteAccess();
        tunnelService.closeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Tunnel request log", description = "Lists recent requests forwarded through a tunnel")
    @GetMapping("/{sessionId}/requests")
    public ResponseEntity<Page<TunnelRequestLog>> requestLog(
            @PathVariable("sessionId") UUID sessionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            AuthContext auth) {
        // Verify ownership
        tunnelService.getBySessionAndOrg(sessionId);
        size = Math.min(size, 100);
        Page<TunnelRequestLog> logs = requestLogRepository
                .findByTunnelSessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(page, size));
        return ResponseEntity.ok(logs);
    }

    @Operation(summary = "Tunnel status", description = "Returns current tunnel registry stats and user's active tunnels")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(AuthContext auth) {
        List<TunnelSessionResponse> myTunnels = tunnelService.listActiveByUser(auth.requireUserId());
        return ResponseEntity.ok(Map.of(
                "activeTunnels", tunnelRegistry.activeCount(),
                "pendingRequests", tunnelRegistry.pendingRequestCount(),
                "myTunnels", myTunnels,
                "bandwidthBytesThisMonth", bandwidthService.getCurrentUsage()
        ));
    }
}
