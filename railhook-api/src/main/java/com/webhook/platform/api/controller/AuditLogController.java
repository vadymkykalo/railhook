package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.AuditLogResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogService auditLogService;

    @Operation(operationId = "listAuditLog", summary = "List audit log entries",
            description = "Returns the organization's audit log, newest first, filterable by action and actor")
    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> list(
            AuthContext auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        auth.requireJwt();
        return ResponseEntity.ok(auditLogService.list(
                new AuditLogService.Query(action, status, resourceType, from, to),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))));
    }

    @Operation(operationId = "exportAuditLog", summary = "Export the audit log as CSV",
            description = "The same entries the listing returns, under the same filters, streamed "
                    + "as CSV rather than paged. A from= or to= that is not a yyyy-MM-dd date is "
                    + "rejected rather than ignored.")
    @GetMapping("/export")
    public void exportCsv(
            AuthContext auth,
            HttpServletResponse response,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws IOException {
        auth.requireJwt();
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition",
                "attachment; filename=audit-log-" + LocalDate.now() + ".csv");
        auditLogService.writeCsv(
                new AuditLogService.Query(action, status, resourceType, from, to),
                response.getWriter());
    }
}
