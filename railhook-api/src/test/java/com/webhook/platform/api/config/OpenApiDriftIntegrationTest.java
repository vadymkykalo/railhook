package com.webhook.platform.api.config;

import com.webhook.platform.api.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// To regenerate: mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true
// All three must be on or springdoc serves a bare default and everything reads as drift.
// Pinned here because make exports SWAGGER_ENABLED=false into maven.
@TestPropertySource(properties = {
        "swagger.enabled=true",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@Tag("ratchet")
class OpenApiDriftIntegrationTest extends AbstractIntegrationTest {

    private static final int MAX_REPORTED = 60;

    private static final String REGENERATE_PROPERTY = "openapi.regenerate";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("committed openapi.yaml matches the spec the API serves")
    void committedSpecMatchesLiveSpec() throws Exception {
        String liveYaml = mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                // Explicit UTF-8: MockMvc falls back to ISO-8859-1 without a charset.
                .getContentAsString(StandardCharsets.UTF_8);

        Path committedSpec = locateCommittedSpec();

        if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
            Files.writeString(committedSpec, liveYaml, StandardCharsets.UTF_8);
            fail("Regenerated %s from the live spec (-D%s was set). Review the diff and commit it, "
                    + "then re-run without the flag.", committedSpec, REGENERATE_PROPERTY);
        }

        Object live = withoutEnvironmentSpecificKeys(parse(liveYaml));
        Object committed = withoutEnvironmentSpecificKeys(
                parse(Files.readString(committedSpec, StandardCharsets.UTF_8)));

        List<String> differences = new ArrayList<>();
        walk(committed, live, "", differences);

        assertThat(differences)
                .as("%s has drifted from the spec the API serves.%n%s%n"
                        + "Regenerate with: mvn test -pl railhook-api "
                        + "-Dtest=OpenApiDriftIntegrationTest -D%s=true",
                        committedSpec, render(differences), REGENERATE_PROPERTY)
                .isEmpty();
    }

    private static Object parse(String yaml) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    // servers comes from the incoming request: where this instance runs, not what the API offers.
    @SuppressWarnings("unchecked")
    private static Object withoutEnvironmentSpecificKeys(Object spec) {
        if (spec instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>((Map<Object, Object>) spec);
            copy.remove("servers");
            return copy;
        }
        return spec;
    }

    // Surefire runs in the module directory, but the spec lives at the repo root.
    private static Path locateCommittedSpec() {
        Path fromModule = Path.of("..", "openapi.yaml").normalize();
        return Files.exists(fromModule) ? fromModule : Path.of("openapi.yaml");
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object committed, Object live, String path, List<String> out) {
        if (committed == null || live == null) {
            if (committed != live) {
                out.add("  CHANGED  %s: %s -> %s".formatted(path, committed, live));
            }
            return;
        }
        if (!committed.getClass().equals(live.getClass())) {
            out.add("  CHANGED  %s: type %s -> %s".formatted(
                    path, committed.getClass().getSimpleName(), live.getClass().getSimpleName()));
            return;
        }
        if (committed instanceof Map) {
            Map<Object, Object> committedMap = (Map<Object, Object>) committed;
            Map<Object, Object> liveMap = (Map<Object, Object>) live;
            Set<Object> keys = new LinkedHashSet<>(committedMap.keySet());
            keys.addAll(liveMap.keySet());
            Set<String> sortedKeys = new TreeSet<>();
            keys.forEach(key -> sortedKeys.add(String.valueOf(key)));
            for (String key : sortedKeys) {
                String child = path.isEmpty() ? key : path + "." + key;
                if (!committedMap.containsKey(key)) {
                    out.add("  ADDED    " + child);
                } else if (!liveMap.containsKey(key)) {
                    out.add("  REMOVED  " + child);
                } else {
                    walk(committedMap.get(key), liveMap.get(key), child, out);
                }
            }
        } else if (committed instanceof List<?> committedList) {
            if (!committed.equals(live)) {
                out.add("  CHANGED  %s: list of %d -> %d".formatted(
                        path, committedList.size(), ((List<?>) live).size()));
            }
        } else if (!Objects.equals(committed, live)) {
            out.add("  CHANGED  %s: %s -> %s".formatted(path, committed, live));
        }
    }

    private static String render(List<String> differences) {
        if (differences.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder("--- committed openapi.yaml vs live /v3/api-docs.yaml ---\n");
        differences.stream().limit(MAX_REPORTED).forEach(line -> report.append(line).append('\n'));
        if (differences.size() > MAX_REPORTED) {
            report.append("  ... and ").append(differences.size() - MAX_REPORTED).append(" more\n");
        }
        return report.toString();
    }
}
