package com.webhook.platform.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// Validation and connection resolve the name twice; SsrfProtectionCustomizer re-checks the dialled address.
@Tag("ratchet")
@DisplayName("Outbound WebClients declare their SSRF posture")
class OutboundWebClientSsrfDeclarationTest {

    private static final List<Path> SOURCE_ROOTS = List.of(
            Paths.get("src/main/java"),
            Paths.get("../railhook-worker/src/main/java"));

    // "The URL is validated first" is not a reason: that is the check the connector backstops.
    private static final Set<String> NO_ATTACKER_CONTROLLED_HOST = new TreeSet<>(Set.of(
            // Pinned to hooks.slack.com: no name for a rebind to move.
            "SlackNodeExecutor",

            // Fixed vendor endpoints compiled into the provider, never operator- or user-supplied.
            "WayForPayBillingProvider",
            "BillingAutoConfiguration",

            // Google's endpoints are literal defaults in application.yml, overridden only by tests.
            "GoogleSignInService"));

    @Test
    void everyOutboundClientAppliesTheConnectorOrIsListedWithAReason() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    if (!buildsAWebClient(source)) {
                        continue;
                    }
                    String className = file.getFileName().toString().replace(".java", "");
                    if (source.contains("SsrfProtectionCustomizer")
                            || NO_ATTACKER_CONTROLLED_HOST.contains(className)) {
                        continue;
                    }
                    offenders.add(className);
                }
            }
        }

        assertThat(offenders)
                .as("""
                    These build an outbound WebClient without SsrfProtectionCustomizer.

                    Apply it, as EndpointService does:

                        webClientBuilder.clientConnector(new ReactorClientHttpConnector(
                                SsrfProtectionCustomizer.apply(HttpClient.create(), allowPrivateIps, allowedHosts)))

                    If the client genuinely cannot be aimed at a host an attacker chooses, add it
                    to NO_ATTACKER_CONTROLLED_HOST with the reason. Validating the URL beforehand
                    is not that reason: the whole point of the connector is that the name can
                    resolve differently between the check and the connection.
                    """)
                .isEmpty();
    }

    private static boolean buildsAWebClient(String source) {
        return source.contains("WebClient.Builder") || source.contains("WebClient.builder()");
    }
}
