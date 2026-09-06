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

/**
 * Every outbound {@code WebClient} either closes the DNS-rebinding window or says why it need not.
 *
 * <p>Validating a URL and then connecting to it are two different resolutions of the same name.
 * Between them the name can move, which is what {@code SsrfProtectionCustomizer} exists to catch:
 * it re-checks the address the connector actually dials. {@code EndpointService},
 * {@code EndpointVerificationService}, {@code AlertNotificationService}, the worker's
 * {@code WebClientConfig} and {@code MtlsWebClientFactory} all apply it.
 *
 * <p>{@code HttpNodeExecutor} did not — a client a user aims by typing a URL into a workflow
 * node, whose own javadoc claimed it "reuses SSRF protection". Nothing in the build noticed,
 * because there was nothing to notice it. This is that something.
 *
 * <p>Source-level rather than reflective, for the same reason
 * {@code NativeQueryTenantPredicateTest} is: the connector is buried inside an already-built
 * client, and a runtime check would only cover whichever clients some test happens to
 * construct — the opposite of what a ratchet is for.
 *
 * <p>Deliberately a plain {@code *Test} — see {@code scripts/check-test-routing.sh}.
 */
@Tag("ratchet")
@DisplayName("Outbound WebClients declare their SSRF posture")
class OutboundWebClientSsrfDeclarationTest {

    private static final List<Path> SOURCE_ROOTS = List.of(
            Paths.get("src/main/java"),
            Paths.get("../webhook-platform-worker/src/main/java"));

    /**
     * Clients that build without the connector, and the reason each may.
     *
     * <p>Adding an entry asserts that the client cannot be pointed at an address the caller
     * chooses. "The URL is validated first" is not such a reason — that is precisely the check
     * the connector exists to backstop.
     */
    private static final Set<String> NO_ATTACKER_CONTROLLED_HOST = new TreeSet<>(Set.of(
            // Pinned to a literal host prefix before the request is built: a workflow author can
            // only ever reach hooks.slack.com, so there is no name for a rebind to move.
            "SlackNodeExecutor",

            // Fixed vendor endpoints compiled into the provider, never operator- or user-supplied.
            "WayForPayBillingProvider",
            "BillingAutoConfiguration"));

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
