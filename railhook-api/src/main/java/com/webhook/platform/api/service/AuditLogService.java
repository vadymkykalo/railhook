package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditLogSpecification;
import com.webhook.platform.api.domain.entity.AuditLog;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.AuditLogRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AuditLogResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final int EXPORT_BATCH_SIZE = 500;
    private static final String CSV_HEADER =
            "Time,Action,Resource Type,Resource ID,User,Status,Duration (ms),IP,Error";

    /**
     * The filters a caller may narrow the log by. Dates are inclusive whole days in UTC, and a date
     * that does not parse is rejected rather than dropped: a silently ignored filter reads as an
     * empty result nobody can explain.
     */
    public record Query(String action, String status, String resourceType, String from, String to) {

        Specification<AuditLog> asSpecification(UUID organizationId) {
            return AuditLogSpecification.filter(organizationId, action, status, resourceType,
                    startOfDay(from), dayAfter(to));
        }

        private static Instant startOfDay(String date) {
            return parse(date, 0);
        }

        private static Instant dayAfter(String date) {
            return parse(date, 1);
        }

        private static Instant parse(String date, int plusDays) {
            if (date == null || date.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(date).plusDays(plusDays).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Not a date in yyyy-MM-dd form: " + date);
            }
        }
    }

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public Page<AuditLogResponse> list(Query query, Pageable pageable) {
        Page<AuditLog> raw = auditLogRepository.findAll(query.asSpecification(TenantContext.require()), pageable);
        Map<UUID, String> emails = resolveEmails(raw.getContent());
        return raw.map(entry -> toResponse(entry, emails));
    }

    /** Streams in batches: an organization's whole history does not fit in one page. */
    public void writeCsv(Query query, PrintWriter writer) {
        Specification<AuditLog> spec = query.asSpecification(TenantContext.require());
        writer.println(CSV_HEADER);

        int pageNumber = 0;
        while (true) {
            Page<AuditLog> batch = auditLogRepository.findAll(spec, PageRequest.of(pageNumber, EXPORT_BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            Map<UUID, String> emails = resolveEmails(batch.getContent());
            batch.getContent().forEach(entry -> writeRow(writer, entry, emails));
            if (!batch.hasNext()) {
                break;
            }
            pageNumber++;
        }
        writer.flush();
    }

    private void writeRow(PrintWriter writer, AuditLog entry, Map<UUID, String> emails) {
        String email = entry.getUserId() != null ? emails.getOrDefault(entry.getUserId(), "") : "";
        writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                entry.getCreatedAt(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId() != null ? entry.getResourceId() : "",
                email,
                entry.getStatus(),
                entry.getDurationMs() != null ? entry.getDurationMs() : "",
                entry.getClientIp() != null ? entry.getClientIp() : "",
                escapeCsv(entry.getErrorMessage()));
    }

    private AuditLogResponse toResponse(AuditLog entry, Map<UUID, String> emails) {
        return AuditLogResponse.builder()
                .id(entry.getId())
                .action(entry.getAction())
                .resourceType(entry.getResourceType())
                .resourceId(entry.getResourceId())
                .userId(entry.getUserId())
                .userEmail(entry.getUserId() != null ? emails.get(entry.getUserId()) : null)
                .organizationId(entry.getOrganizationId())
                .status(entry.getStatus())
                .errorMessage(entry.getErrorMessage())
                .durationMs(entry.getDurationMs())
                .clientIp(entry.getClientIp())
                .details(entry.getDetails())
                .createdAt(entry.getCreatedAt())
                .build();
    }

    private Map<UUID, String> resolveEmails(List<AuditLog> entries) {
        Set<UUID> userIds = entries.stream()
                .map(AuditLog::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
    }

    private String escapeCsv(String value) {
        return value == null ? "" : value.replace("\"", "\"\"");
    }
}
