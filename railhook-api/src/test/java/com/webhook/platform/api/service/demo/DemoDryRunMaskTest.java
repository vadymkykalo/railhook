package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.dto.DeliveryDryRunResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// A working X-Signature is a capability: whoever holds it can present a body to a receiver.
class DemoDryRunMaskTest {

    private static final String REAL_SIGNATURE = "t=1758380710,v1=6f3a1c0d9b2e4a58c7d1e0f9a8b7c6d5";

    private static DeliveryDryRunResponse dryRun() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Timestamp", "1758380710000");
        headers.put("X-Signature", REAL_SIGNATURE);
        headers.put("X-Order-Value", "101.48");
        return DeliveryDryRunResponse.builder()
                .transformedPayload("{\"order_id\":\"ord_9001\"}")
                .requestHeaders(headers)
                .signature(REAL_SIGNATURE)
                .endpointUrl("https://api.acme-shop.example/webhooks/railhook")
                .success(true)
                .errors(List.of())
                .durationMs(3)
                .build();
    }

    @Test
    void theSignatureIsGoneFromBothPlacesItAppears() {
        DeliveryDryRunResponse masked = DemoDryRunMask.withoutSignature(dryRun());

        assertThat(masked.getSignature()).isEqualTo(DemoDryRunMask.MASKED);
        assertThat(masked.getRequestHeaders().get("X-Signature")).isEqualTo(DemoDryRunMask.MASKED);
        assertThat(masked.getRequestHeaders().toString()).doesNotContain(REAL_SIGNATURE);
    }

    @Test
    void aDryRunThatNeverComputedOneIsLeftAlone() {
        DeliveryDryRunResponse unsigned = DeliveryDryRunResponse.builder()
                .transformedPayload("{}")
                .requestHeaders(new LinkedHashMap<>(Map.of("Content-Type", "application/json")))
                .success(true)
                .errors(List.of())
                .build();

        DeliveryDryRunResponse masked = DemoDryRunMask.withoutSignature(unsigned);

        assertThat(masked.getSignature()).isNull();
        assertThat(masked.getRequestHeaders()).doesNotContainKey("X-Signature");
    }
}
