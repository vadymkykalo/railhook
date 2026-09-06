package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.DlqActionResponse;
import com.webhook.platform.api.dto.DlqItemResponse;
import com.webhook.platform.api.dto.DlqRetryRequest;
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.DlqService;
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
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/dlq")
@Tag(name = "Dead Letter Queue", description = "DLQ management operations")
@SecurityRequirement(name = "bearerAuth")
@SecurityRequirement(name = "apiKey")
@RequiredArgsConstructor
public class DlqController {

    private final DlqService dlqService;

    @Operation(summary = "List DLQ items", description = "Returns paginated list of failed deliveries in DLQ")
    @GetMapping
    public ResponseEntity<Page<DlqItemResponse>> listDlqItems(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "endpointId", required = false) UUID endpointId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        dlqService.validateProjectOwnership(projectId);
        
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<DlqItemResponse> items = dlqService.listDlqItems(projectId, endpointId, pageable);
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Get DLQ stats", description = "Returns DLQ statistics for the project")
    @GetMapping("/stats")
    public ResponseEntity<DlqStatsResponse> getDlqStats(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        dlqService.validateProjectOwnership(projectId);
        
        DlqStatsResponse stats = dlqService.getDlqStats(projectId);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Get DLQ item details", description = "Returns details of a specific DLQ item")
    @GetMapping("/{deliveryId}")
    public ResponseEntity<DlqItemResponse> getDlqItem(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("deliveryId") UUID deliveryId,
            AuthContext auth) {
        auth.validateProjectAccess(projectId);
        DlqItemResponse item = dlqService.getDlqItem(projectId, deliveryId);
        return ResponseEntity.ok(item);
    }

    @Operation(summary = "Retry single DLQ item", description = "Retries a single failed delivery")
    @ApiResponse(responseCode = "200", description = "Delivery queued for retry")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/{deliveryId}/retry")
    public ResponseEntity<DlqActionResponse> retrySingle(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("deliveryId") UUID deliveryId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        
        int retried = dlqService.retryDeliveries(projectId, Collections.singletonList(deliveryId));
        return ResponseEntity.ok(DlqActionResponse.builder().retried(retried).build());
    }

    @Operation(summary = "Bulk retry DLQ items", description = "Retries multiple failed deliveries")
    @ApiResponse(responseCode = "200", description = "Deliveries queued for retry")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@PostMapping("/retry")
    public ResponseEntity<DlqActionResponse> retryBulk(
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody DlqRetryRequest request,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        
        int retried = dlqService.retryDeliveries(projectId, request.getDeliveryIds());
        return ResponseEntity.ok(DlqActionResponse.builder()
                .retried(retried)
                .requested(request.getDeliveryIds().size())
                .build());
    }

    @Operation(summary = "Purge all DLQ items", description = "Permanently deletes all items in DLQ for the project")
    @ApiResponse(responseCode = "200", description = "DLQ purged")
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireAccess(AccessLevel.WRITE)
@DeleteMapping
    public ResponseEntity<DlqActionResponse> purgeAll(
            @PathVariable("projectId") UUID projectId,
            AuthContext auth) {
        auth.requireWriteAccess();
        auth.validateProjectAccess(projectId);
        
        int purged = dlqService.purgeAllDlq(projectId);
        return ResponseEntity.ok(DlqActionResponse.builder().purged(purged).build());
    }
}
