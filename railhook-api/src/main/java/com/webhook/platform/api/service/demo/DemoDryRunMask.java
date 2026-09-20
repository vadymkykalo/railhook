package com.webhook.platform.api.service.demo;

import com.webhook.platform.api.dto.DeliveryDryRunResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Takes the working signature off a delivery dry-run before the public demo sees it.
 *
 * <p>The dry-run is the one read path that hands back a real {@code X-Signature}, computed with
 * the Endpoint's own signing secret. That is why it declares WRITE: holding it is being able to
 * mint a body a receiver accepts as genuine. A demo token goes to anyone who asks, so the demo
 * must not hold it — and a stranger who could ask the demo to sign arbitrary bytes would have a
 * signing oracle for every Endpoint in the demo organization, whatever those Endpoints are today
 * and whatever a future seeding makes them.
 *
 * <p>Refusing the endpoint instead was the other option, and it costs more than it saves: the
 * Studio's whole point is showing the bytes that would go out, and a visitor who cannot dry-run
 * cannot see what a transformation does to a real Delivery. So the demo gets the dry-run and not
 * the capability: the same body, the same header names in the same order, the same everything
 * else — with the signature replaced by {@link #MASKED}, which is not a signature and cannot be
 * mistaken for one. Nothing here is reversible into the secret, because nothing here was
 * computed from it.
 *
 * <p>Applied at the controller, on the way out, rather than inside {@code DeliveryDryRunService}:
 * the service exists to produce exactly what a real attempt would send, and a service that knew
 * about demo sessions would be a service that could get that wrong for everyone else.
 */
public final class DemoDryRunMask {

    /**
     * What a demo session is shown in place of the signature. Deliberately not in the shape of a
     * real signature header: a visitor copying this into a request gets a rejection from the
     * receiver, not a puzzle.
     */
    public static final String MASKED = "masked-in-demo";

    /** The header the real signature would have been in. */
    static final String SIGNATURE_HEADER = "X-Signature";

    private DemoDryRunMask() {
    }

    /**
     * Returns the dry-run with its signature masked. The response is this request's own object,
     * so it is rewritten in place; the header map is rebuilt to keep its insertion order.
     */
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
