package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JsonDiffEntry {
    private String path;
    private DiffType type;
    private Object leftValue;
    private Object rightValue;
}
