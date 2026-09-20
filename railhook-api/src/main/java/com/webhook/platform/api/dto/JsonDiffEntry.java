package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One place two JSON documents disagree, named by its JSONPath.
 *
 * <p>Shared by everything that compares two JSON documents — two Events, two versions of a
 * Transformation's template — so a caller that can read one diff can read the other. Was nested
 * inside {@code EventDiffResponse}; the field names and the wire shape are unchanged.
 */
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
