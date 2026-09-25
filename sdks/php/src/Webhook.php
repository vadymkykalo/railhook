<?php

declare(strict_types=1);

namespace Railhook;

use Railhook\Exception\RailhookException;

class Webhook
{
    private const DEFAULT_TOLERANCE_MS = 300000;

    /** The Standard Webhooks headers carry seconds, not milliseconds. */
    private const DEFAULT_STANDARD_TOLERANCE_SECONDS = 300;

    /**
     * Verify an `X-Signature` header (`t=<unix-ms>,v1=<hex>[,v1=...]`); during a secret rotation
     * any one `v1` matching is enough.
     *
     * @param string $payload Raw request body
     * @param string $signature X-Signature header value
     * @param string $secret Endpoint webhook secret
     * @param int $toleranceMs Maximum age of the signature in milliseconds
     * @throws RailhookException If the signature is invalid or expired
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
        // Collected, not overwritten: during a rotation keeping only the last v1 would
        // reject whichever secret the receiver currently holds.
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

        // No early exit, so timing does not reveal which candidate matched.
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
     * Verify the Standard Webhooks headers; during a rotation any one matching signature is enough.
     *
     * @param string $payload Raw request body
     * @param array<string, string|string[]> $headers Request headers, any case
     * @param string $secret The endpoint's `standardWebhooksSecret` (`whsec_…`); a raw secret is used as-is
     * @param int $toleranceSeconds How far the timestamp may be from now, either way
     * @throws RailhookException If the signature is invalid or expired
     */
    public static function verifyStandardWebhook(
        string $payload,
        array $headers,
        string $secret,
        int $toleranceSeconds = self::DEFAULT_STANDARD_TOLERANCE_SECONDS
    ): bool {
        // Laravel's and Symfony's `$request->headers->all()` map each name to a list of values.
        $normalized = [];
        foreach ($headers as $key => $value) {
            $normalized[strtolower((string) $key)] = is_array($value) ? ($value[0] ?? null) : $value;
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

        $key = str_starts_with($secret, 'whsec_')
            ? base64_decode(substr($secret, strlen('whsec_')), true)
            : $secret;
        if ($key === false) {
            throw new RailhookException('Malformed whsec_ secret', 400, 'invalid_signature');
        }

        $expected = base64_encode(
            hash_hmac('sha256', $messageId . '.' . $timestampSeconds . '.' . $payload, $key, true)
        );

        // No early exit, so timing does not reveal which candidate matched.
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
     * Verify the request and decode it into an event. `type` is set only when the body carries a
     * `type` key; ids come from the headers.
     *
     * @param string $payload Raw request body
     * @param array $headers Request headers, any case
     * @param string $secret Endpoint webhook secret
     * @param int $toleranceMs Maximum age of the signature in milliseconds
     * @return array Keys: eventId, deliveryId, timestamp, type, data
     * @throws RailhookException If the signature is invalid or the payload is malformed
     */
    public static function constructEvent(
        string $payload,
        array $headers,
        string $secret,
        int $toleranceMs = self::DEFAULT_TOLERANCE_MS
    ): array {
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

    /** Build an `X-Signature` value (`t=<ms>,v1=<hex>`) for tests; the timestamp defaults to now. */
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
