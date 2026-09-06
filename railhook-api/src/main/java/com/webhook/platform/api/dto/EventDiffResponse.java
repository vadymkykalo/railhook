package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.webhook.platform.api.domain.enums.DiffType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventDiffResponse {
    private UUID leftEventId;
    private UUID rightEventId;
    private String eventType;
    private Instant leftCreatedAt;
    private Instant rightCreatedAt;
    private String leftPayload;
    private String rightPayload;
    private List<DiffEntry> diffs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DiffEntry {
        private String path;
        private DiffType type;
        private Object leftValue;
        private Object rightValue;
    }
}
