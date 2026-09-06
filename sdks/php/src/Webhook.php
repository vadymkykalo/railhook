<?php

declare(strict_types=1);

namespace Railhook;

use Railhook\Exception\RailhookException;

class Webhook
{
    private const DEFAULT_TOLERANCE_MS = 300000; // 5 minutes

    /** The Standard Webhooks headers carry seconds, not milliseconds. */
    private const DEFAULT_STANDARD_TOLERANCE_SECONDS = 300;

    /**
     * Verify webhook signature using HMAC-SHA256.
     *
     * The header is `t=<unix-ms>,v1=<hex>` and may carry more than one `v1`.
     * After you rotate an endpoint's secret, Railhook signs each delivery with
     * both the new secret and the retired one for the endpoint's grace window
     * (24 hours by default), so the new secret can be deployed whenever you like
     * rather than at the instant you press rotate. The delivery is authentic if
     * any `v1` matches.
     *
     * @param string $payload Raw request body
     * @param string $signature X-Signature header value (format: t=timestamp,v1=signature[,v1=...])
     * @param string $secret Endpoint webhook secret
     * @param int $toleranceMs Maximum age of signature in milliseconds
     * @return bool True if signature is valid
     * @throws RailhookException If signature is invalid or expired
     */
    public static function verifySignature(
        string $payload,
        string $signature,
        string $secret,
        int $toleranceMs = self::DEFAULT_TOLERANCE_MS
    ): bool {
        if (empty($signature)) {
            throw new RailhookException('Missing signature header', 400, 'invalid_signature');
        }

        $timestamp = null;
        // Collected, not overwritten: a header sent during a secret rotation carries
        // one v1 per valid secret, and keeping only the last would reject whichever
        // of the pair the receiver is currently holding.
        $signatures = [];

        foreach (explode(',', $signature) as $part) {
            if (str_starts_with($part, 't=')) {
                $timestamp = trim(substr($part, 2));
            } elseif (str_starts_with($part, 'v1=')) {
                $signatures[] = trim(substr($part, 3));
            }
        }

        if ($timestamp === null || $signatures === []) {
            throw new RailhookException(
                'Invalid signature format. Expected: t=timestamp,v1=signature',
                400,
                'invalid_signature'
            );
        }

        $timestampMs = (int) $timestamp;
        $nowMs = (int) (microtime(true) * 1000);

        if (abs($nowMs - $timestampMs) > $toleranceMs) {
            throw new RailhookException(
                'Webhook timestamp is outside tolerance window',
                400,
                'timestamp_expired'
            );
        }

        $signedPayload = "{$timestamp}.{$payload}";
        $expectedSignature = hash_hmac('sha256', $signedPayload, $secret);

        // Every candidate is compared, with no early exit, so the time taken does
        // not depend on which position matched.
        $matched = false;
        foreach ($signatures as $candidate) {
            if (hash_equals($expectedSignature, $candidate)) {
                $matched = true;
            }
        }

        if (!$matched) {
            throw new RailhookException('Invalid signature', 400, 'invalid_signature');
        }

        return true;
    }

    /**
     * Verify the {@link https://www.standardwebhooks.com Standard Webhooks} headers.
     *
     * Endpoints receive both header sets by default (`signatureScheme: BOTH`), so use
     * whichever suits you — this one if you would rather verify the same way as the other
     * providers you integrate with, `verifySignature` if you already verify `X-Signature`.
     *
     * Two things differ from Railhook's own scheme beyond the header names: the message id
     * is part of what is signed, and the digest is base64 rather than hex. Rotation behaves
     * the same — through the grace window the header carries a space-separated signature per
     * valid secret, and any one matching is enough.
     *
     * @param string $payload Raw request body
     * @param array $headers Request headers (case-insensitive)
     * @param string $secret The endpoint's `standardWebhooksSecret` (`whsec_…`). A raw
     *                       secret is accepted too and used as-is.
     * @param int $toleranceSeconds How far the timestamp may be from now, either way
     * @return bool True if the signature is valid
     * @throws RailhookException If it is not
     */
    public static function verifyStandardWebhook(
        string $payload,
        array $headers,
        string $secret,
        int $toleranceSeconds = self::DEFAULT_STANDARD_TOLERANCE_SECONDS
    ): bool {
        $normalized = [];
        foreach ($headers as $key => $value) {
            $normalized[strtolower((string) $key)] = $value;
        }

        $messageId = $normalized['webhook-id'] ?? null;
        $timestamp = $normalized['webhook-timestamp'] ?? null;
        $signature = $normalized['webhook-signature'] ?? null;

        if (!$messageId || !$timestamp || !$signature) {
            throw new RailhookException(
                'Missing webhook-id, webhook-timestamp or webhook-signature header',
                400,
                'invalid_signature'
            );
        }

        if (!is_numeric(trim((string) $timestamp))) {
            throw new RailhookException('Invalid webhook-timestamp header', 400, 'invalid_signature');
        }
        $timestampSeconds = (int) trim((string) $timestamp);

        if (abs(time() - $timestampSeconds) > $toleranceSeconds) {
            throw new RailhookException(
                'Webhook timestamp is outside tolerance window',
                400,
                'timestamp_expired'
            );
        }

        // `whsec_<base64>` is the conventional form and is what the endpoint's
        // standardWebhooksSecret gives you: the base64 body decodes to the key bytes.
        // Anything else is taken literally, so a raw secret still works.
        $key = str_starts_with($secret, 'whsec_')
            ? base64_decode(substr($secret, strlen('whsec_')), true)
            : $secret;
        if ($key === false) {
            throw new RailhookException('Malformed whsec_ secret', 400, 'invalid_signature');
        }

        $expected = base64_encode(
            hash_hmac('sha256', $messageId . '.' . $timestampSeconds . '.' . $payload, $key, true)
        );

        // Space-separated, one per valid secret during a rotation window. Every candidate is
        // compared with no early exit, so the time taken does not reveal which one matched.
        $matched = false;
        foreach (preg_split('/\s+/', trim((string) $signature)) as $part) {
            $comma = strpos($part, ',');
            if ($comma === false || substr($part, 0, $comma) !== 'v1') {
                continue;
            }
            if (hash_equals($expected, substr($part, $comma + 1))) {
                $matched = true;
            }
        }

        if (!$matched) {
            throw new RailhookException('Invalid signature', 400, 'invalid_signature');
        }

        return true;
    }

    /**
     * Construct a webhook event from request, verifying signature.
     *
     * What Railhook actually PUTs on the wire is the event's **payload**, not
     * an envelope: a `$client->events->send(type: 'order.completed', data:
     * [...])` arrives at your endpoint as the `data` array alone, with the
     * identifiers carried in headers (`X-Event-Id`, `X-Delivery-Id`,
     * `X-Timestamp`, `X-Sequence-Number`). So `eventId` / `deliveryId` /
     * `timestamp` are always populated for a real delivery and `data` is the
     * decoded body, but `type` is only populated when the body itself carries
     * a `type` key — which for a default subscription it does not. Route on
     * the payload, or configure the subscription's `payloadTemplate` to wrap
     * the event so that `type` becomes part of the body.
     *
     * @param string $payload Raw request body
     * @param array $headers Request headers (case-insensitive)
     * @param string $secret Endpoint webhook secret
     * @param int $toleranceMs Maximum age of signature in milliseconds
     * @return array Parsed webhook event with eventId, deliveryId, timestamp, type, data
     * @throws RailhookException If signature is invalid or payload is malformed
     */
    public static function constructEvent(
        string $payload,
        array $headers,
        string $secret,
        int $toleranceMs = self::DEFAULT_TOLERANCE_MS
    ): array {
        // Normalize headers to lowercase
        $normalizedHeaders = [];
        foreach ($headers as $key => $value) {
            $normalizedHeaders[strtolower($key)] = is_array($value) ? $value[0] : $value;
        }

        $signature = $normalizedHeaders['x-signature'] ?? '';
        $timestamp = $normalizedHeaders['x-timestamp'] ?? '';
        $eventId = $normalizedHeaders['x-event-id'] ?? '';
        $deliveryId = $normalizedHeaders['x-delivery-id'] ?? '';

        if (empty($signature)) {
            throw new RailhookException('Missing X-Signature header', 400, 'missing_header');
        }

        self::verifySignature($payload, $signature, $secret, $toleranceMs);

        $data = json_decode($payload, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            throw new RailhookException('Invalid JSON payload', 400, 'invalid_payload');
        }

        return [
            'eventId' => $eventId,
            'deliveryId' => $deliveryId,
            'timestamp' => $timestamp ? (int) $timestamp : (int) (microtime(true) * 1000),
            'type' => $data['type'] ?? '',
            'data' => $data['data'] ?? $data,
        ];
    }

    /**
     * Generate a signature for testing purposes.
     *
     * @param string $payload Request body
     * @param string $secret Webhook secret
     * @param int|null $timestampMs Optional timestamp in milliseconds (defaults to now)
     * @return string Signature string in format t=timestamp,v1=signature
     */
    public static function generateSignature(
        string $payload,
        string $secret,
        ?int $timestampMs = null
    ): string {
        $ts = $timestampMs ?? (int) (microtime(true) * 1000);
        $signedPayload = "{$ts}.{$payload}";
        $signature = hash_hmac('sha256', $signedPayload, $secret);

        return "t={$ts},v1={$signature}";
    }
}
