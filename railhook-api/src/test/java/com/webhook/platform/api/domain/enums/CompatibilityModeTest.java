package com.webhook.platform.api.domain.enums;

import com.webhook.platform.common.util.JsonSchemaUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule BACKWARD, FORWARD and FULL were only ever names for.
 *
 * <p>One base schema and one changed schema per scenario, so what each mode lets through and what
 * it refuses is readable side by side — the two directions deliberately disagree, and a test that
 * asserted only "breaking" would hide that.
 */
class CompatibilityModeTest {

    private static final String BASE = """
            {"type":"object","properties":{
               "id":{"type":"string"},
               "note":{"type":"string"}
             },"required":["id"]}
            """;

    private static JsonSchemaUtils.SchemaDiff against(String newSchema) throws Exception {
        return JsonSchemaUtils.diff(BASE, newSchema);
    }

    @Test
    void none_allowsEverything() throws Exception {
        String stripped = """
                {"type":"object","properties":{"total":{"type":"number"}},"required":["total"]}
                """;

        assertThat(CompatibilityMode.NONE.violations(against(stripped))).isEmpty();
    }

    @Test
    void backward_allowsAnAddedOptionalProperty() throws Exception {
        String withOptional = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"},
                   "label":{"type":"string"}
                 },"required":["id"]}
                """;

        assertThat(CompatibilityMode.BACKWARD.violations(against(withOptional))).isEmpty();
    }

    @Test
    void backward_refusesAnAddedRequiredProperty() throws Exception {
        String withRequired = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"},
                   "label":{"type":"string"}
                 },"required":["id","label"]}
                """;

        assertThat(CompatibilityMode.BACKWARD.violations(against(withRequired)))
                .singleElement().asString().contains("$.label").contains("new required property");
    }

    @Test
    void backward_refusesAPropertyThatBecameRequired() throws Exception {
        String tightened = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"}
                 },"required":["id","note"]}
                """;

        assertThat(CompatibilityMode.BACKWARD.violations(against(tightened)))
                .singleElement().asString().contains("$.note").contains("became required");
    }

    @Test
    void backward_allowsDroppingARequiredProperty() throws Exception {
        String dropped = """
                {"type":"object","properties":{"note":{"type":"string"}}}
                """;

        // A consumer written against the new schema no longer asks for `id`, so old events
        // still read. FORWARD is the mode that refuses this, and does below.
        assertThat(CompatibilityMode.BACKWARD.violations(against(dropped))).isEmpty();
    }

    @Test
    void forward_refusesDroppingARequiredProperty() throws Exception {
        String dropped = """
                {"type":"object","properties":{"note":{"type":"string"}}}
                """;

        assertThat(CompatibilityMode.FORWARD.violations(against(dropped)))
                .singleElement().asString().contains("$.id").contains("was removed");
    }

    @Test
    void forward_allowsAnAddedRequiredProperty() throws Exception {
        String withRequired = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"},
                   "label":{"type":"string"}
                 },"required":["id","label"]}
                """;

        assertThat(CompatibilityMode.FORWARD.violations(against(withRequired))).isEmpty();
    }

    @Test
    void forward_refusesARequirementBeingLifted() throws Exception {
        String relaxed = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"}
                 },"required":[]}
                """;

        assertThat(CompatibilityMode.FORWARD.violations(against(relaxed)))
                .singleElement().asString().contains("$.id").contains("no longer required");
    }

    @Test
    void full_refusesWhatEitherDirectionRefuses() throws Exception {
        String addedRequired = """
                {"type":"object","properties":{
                   "id":{"type":"string"},
                   "note":{"type":"string"},
                   "label":{"type":"string"}
                 },"required":["id","label"]}
                """;
        String droppedRequired = """
                {"type":"object","properties":{"note":{"type":"string"}}}
                """;

        assertThat(CompatibilityMode.FULL.violations(against(addedRequired))).isNotEmpty();
        assertThat(CompatibilityMode.FULL.violations(against(droppedRequired))).isNotEmpty();
    }

    @Test
    void everyCheckingModeRefusesAChangedType() throws Exception {
        String retyped = """
                {"type":"object","properties":{
                   "id":{"type":"integer"},
                   "note":{"type":"string"}
                 },"required":["id"]}
                """;

        for (CompatibilityMode mode : new CompatibilityMode[] {
                CompatibilityMode.BACKWARD, CompatibilityMode.FORWARD, CompatibilityMode.FULL }) {
            assertThat(mode.violations(against(retyped)))
                    .as("%s must refuse a retyped property", mode)
                    .anySatisfy(v -> assertThat(v).contains("changed type from string to integer"));
        }
    }

    @Test
    void anIdenticalSchemaViolatesNothingInAnyMode() throws Exception {
        for (CompatibilityMode mode : CompatibilityMode.values()) {
            assertThat(mode.violations(against(BASE))).as("%s", mode).isEmpty();
        }
    }
}
