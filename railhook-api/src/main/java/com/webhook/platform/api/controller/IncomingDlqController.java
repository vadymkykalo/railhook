package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.DlqActionResponse;
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.dto.IncomingDlqItemResponse;
import com.webhook.platform.api.dto.IncomingDlqRetryRequest;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.IncomingDlqService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.UUID;

/**
 * The Incoming half of the DLQ. Separate from {@link DlqController} rather than a mode of it:
 * the two directions abandon different obligations, and the handle for one of them is a Delivery
 * id while the handle for the other is a Forward Attempt id.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/incoming-dlq")
@Tag(name = "Dead Letter Queue", description = "DLQ management operations")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class IncomingDlqController {

    private final IncomingDlqService incomingDlqService;

    @Operation(operationId = "listIncomingDlqItems", summary = "List incoming DLQ items",
            description = "Returns paginated list of abandoned forwards in the incoming DLQ")
    @GetMapping
    public ResponseEntity<Page<IncomingDlqItemResponse>> listIncomingDlqItems(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "destinationId", required = false) UUID destinationId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        incomingDlqService.validateProjectOwnership(projectId);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(incomingDlqService.listDlqItems(projectId, destinationId, pageable));
    }

    @Operation(operationId = "getIncomingDlqStats", summary = "Get incoming DLQ stats",
            description = "Returns incoming DLQ statistics for the project")
    @GetMapping("/stats")
    public ResponseEntity<DlqStatsResponse> getIncomingDlqStats(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        incomingDlqService.validateProjectOwnership(projectId);

        return ResponseEntity.ok(incomingDlqService.getDlqStats(projectId));
    }

    @Operation(operationId = "getIncomingDlqItem", summary = "Get incoming DLQ item details",
            description = "Returns details of a single abandoned forward")
    @GetMapping("/{forwardAttemptId}")
    public ResponseEntity<IncomingDlqItemResponse> getIncomingDlqItem(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("forwardAttemptId") UUID forwardAttemptId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        return ResponseEntity.ok(incomingDlqService.getDlqItem(projectId, forwardAttemptId));
    }

    @Operation(operationId = "retryIncomingDlqItem", summary = "Retry single incoming DLQ item",
            description = "Re-forwards one abandoned forward to the destination that failed")
    @ApiResponse(responseCode = "200", description = "Forward queued for retry")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping("/{forwardAttemptId}/retry")
    public ResponseEntity<DlqActionResponse> retryIncomingDlqItem(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("forwardAttemptId") UUID forwardAttemptId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);

        int retried = incomingDlqService.retryForwards(projectId, Collections.singletonList(forwardAttemptId));
        return ResponseEntity.ok(DlqActionResponse.builder().retried(retried).build());
    }

    @Operation(operationId = "retryIncomingDlqItems", summary = "Bulk retry incoming DLQ items",
            description = "Re-forwards several abandoned forwards, each to the destination that failed")
    @ApiResponse(responseCode = "200", description = "Forwards queued for retry")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @PostMapping("/retry")
    public ResponseEntity<DlqActionResponse> retryIncomingDlqItems(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody IncomingDlqRetryRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);

        int retried = incomingDlqService.retryForwards(projectId, request.getForwardAttemptIds());
        return ResponseEntity.ok(DlqActionResponse.builder()
                .retried(retried)
                .requested(request.getForwardAttemptIds().size())
                .build());
    }

    @Operation(operationId = "purgeIncomingDlq", summary = "Purge all incoming DLQ items",
            description = "Permanently deletes every abandoned forward in the project's incoming DLQ")
    @ApiResponse(responseCode = "200", description = "Incoming DLQ purged")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
    @DeleteMapping
    public ResponseEntity<DlqActionResponse> purgeIncomingDlq(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);

        int purged = incomingDlqService.purgeAllDlq(projectId);
        return ResponseEntity.ok(DlqActionResponse.builder().purged(purged).build());
    }
}
