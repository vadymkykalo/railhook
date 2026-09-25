package com.webhook.platform.api.domain.enums;

import com.webhook.platform.common.util.JsonSchemaUtils.FieldChange;
import com.webhook.platform.common.util.JsonSchemaUtils.SchemaDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * BACKWARD: a consumer of the new schema must read events produced under the old one, so a new
 * required property breaks it. FORWARD: a consumer of the old schema must read new events, so
 * removing a required property breaks it. FULL is both. NONE checks nothing and is the default,
 * including for auto-discovered schemas. A type change breaks every checking mode.
 */
public enum CompatibilityMode {

    NONE,
    BACKWARD,
    FORWARD,
    FULL;

    public List<String> violations(SchemaDiff diff) {
        if (this == NONE) {
            return List.of();
        }

        List<String> violations = new ArrayList<>();

        for (FieldChange change : diff.changed()) {
            violations.add(change.path() + " changed type from " + change.oldType()
                    + " to " + change.type());
        }

        if (this == BACKWARD || this == FULL) {
            for (FieldChange change : diff.added()) {
                if (change.required()) {
                    violations.add(change.path() + " is a new required property, so events already "
                            + "produced under the previous version do not carry it");
                }
            }
            for (FieldChange change : diff.tightened()) {
                violations.add(change.path() + " became required, so events already produced under "
                        + "the previous version may not carry it");
            }
        }

        if (this == FORWARD || this == FULL) {
            for (FieldChange change : diff.removed()) {
                if (change.required()) {
                    violations.add(change.path() + " was removed, and the previous version required "
                            + "it — a consumer written against that version will not find it");
                }
            }
            for (FieldChange change : diff.relaxed()) {
                violations.add(change.path() + " is no longer required, and a consumer written "
                        + "against the previous version expects it on every event");
            }
        }

        return violations;
    }
}
