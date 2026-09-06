package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkReplayResponse {
    private int totalRequested;
    private int replayed;
    private int skipped;
    private long totalMatched;
    private boolean hasMore;
    private String message;
}
