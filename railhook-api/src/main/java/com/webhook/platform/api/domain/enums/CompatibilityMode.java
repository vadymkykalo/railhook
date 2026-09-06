package com.webhook.platform.api.domain.enums;

import com.webhook.platform.common.util.JsonSchemaUtils.FieldChange;
import com.webhook.platform.common.util.JsonSchemaUtils.SchemaDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * The promise a schema version makes about the one before it, and the rule that holds it to it.
 *
 * <p>The rule lives on the enum rather than in the registry service on purpose: this value was
 * stored, echoed back and read by nothing for as long as it existed, which is exactly what
 * happens when a declaration and the code that would honour it live apart. Now the declaration
 * carries its own consequence, and a mode without one cannot be added without noticing.
 *
 * <p>Two directions, and they disagree about which changes cost something:
 *
 * <ul>
 *   <li>{@link #BACKWARD} — a consumer written against the <em>new</em> schema must still be able
 *       to read events produced under the old one. Adding a required property breaks that: the old
 *       events do not carry it. Dropping a property does not.
 *   <li>{@link #FORWARD} — a consumer written against the <em>old</em> schema must still be able
 *       to read events produced under the new one. Dropping a property it required breaks that.
 *       Adding one does not.
 *   <li>{@link #FULL} — both, so a required property may be neither added nor taken away.
 *   <li>{@link #NONE} — no promise, and no check. This is the default, and what auto-discovered
 *       schemas get, so nothing a project has not asked for is ever refused.
 * </ul>
 *
 * <p>Changing a property's type breaks every direction and is refused by all three checking modes.
 */
public enum CompatibilityMode {

    NONE,
    BACKWARD,
    FORWARD,
    FULL;

    /**
     * Why this diff is not allowed under this mode, one sentence per offending property. Empty
     * means the new schema keeps the promise.
     */
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
