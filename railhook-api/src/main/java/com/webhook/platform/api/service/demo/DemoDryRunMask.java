package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.dto.DeliveryDryRunResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** Masks the real X-Signature in demo dry-runs, or the demo would be a signing oracle. */
public final class DemoDryRunMask {

    // Deliberately not shaped like a signature, so pasting it into a request gets a plain rejection.
    public static final String MASKED = "masked-in-demo";

    static final String SIGNATURE_HEADER = "X-Signature";

    private DemoDryRunMask() {
    }

    // The header map is rebuilt to keep its insertion order.
    public static DeliveryDryRunResponse withoutSignature(DeliveryDryRunResponse response) {
        if (response == null) {
            return null;
        }
        if (response.getSignature() != null) {
            response.setSignature(MASKED);
        }
        Map<String, String> headers = response.getRequestHeaders();
        if (headers != null && headers.containsKey(SIGNATURE_HEADER)) {
            Map<String, String> masked = new LinkedHashMap<>(headers);
            masked.put(SIGNATURE_HEADER, MASKED);
            response.setRequestHeaders(masked);
        }
        return response;
    }
}
