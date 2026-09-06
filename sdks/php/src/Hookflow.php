<?php

declare(strict_types=1);

namespace Hookflow;

use Hookflow\Api\Events;
use Hookflow\Api\Endpoints;
use Hookflow\Api\Subscriptions;
use Hookflow\Api\Deliveries;
use Hookflow\Api\IncomingSources;
use Hookflow\Api\IncomingEvents;

class Hookflow
{
    private const SDK_VERSION = '2.11.0';

    private string $apiKey;
    private string $baseUrl;
    private int $timeout;

    public readonly Events $events;
    public readonly Endpoints $endpoints;
    public readonly Subscriptions $subscriptions;
    public readonly Deliveries $deliveries;
    public readonly IncomingSources $incomingSources;
    public readonly IncomingEvents $incomingEvents;

    public function __construct(
        string $apiKey,
        string $baseUrl = 'http://localhost:8080',
        int $timeout = 30
    ) {
        if (empty($apiKey)) {
            throw new \InvalidArgumentException('API key is required');
        }

        $this->apiKey = $apiKey;
        $this->baseUrl = rtrim($baseUrl, '/');
        $this->timeout = $timeout;

        $this->events = new Events($this);
        $this->endpoints = new Endpoints($this);
        $this->subscriptions = new Subscriptions($this);
        $this->deliveries = new Deliveries($this);
        $this->incomingSources = new IncomingSources($this);
        $this->incomingEvents = new IncomingEvents($this);
    }

    /**
     * Generic GET request. Use for endpoints not yet covered by the SDK.
     */
    public function get(string $path, ?array $queryParams = null): mixed
    {
        return $this->request('GET', $path, queryParams: $queryParams);
    }

    /**
     * Generic POST request. Use for endpoints not yet covered by the SDK.
     */
    public function post(string $path, ?array $body = null, ?string $idempotencyKey = null): mixed
    {
        return $this->request('POST', $path, body: $body, idempotencyKey: $idempotencyKey);
    }

    /**
     * Generic PUT request. Use for endpoints not yet covered by the SDK.
     */
    public function put(string $path, ?array $body = null): mixed
    {
        return $this->request('PUT', $path, body: $body);
    }

    /**
     * Generic PATCH request. Use for endpoints not yet covered by the SDK.
     */
    public function patch(string $path, ?array $body = null): mixed
    {
        return $this->request('PATCH', $path, body: $body);
    }

    /**
     * Generic DELETE request. Use for endpoints not yet covered by the SDK.
     */
    public function delete(string $path): mixed
    {
        return $this->request('DELETE', $path);
    }

    public function request(
        string $method,
        string $path,
        ?array $body = null,
        ?array $queryParams = null,
        ?string $idempotencyKey = null
    ): mixed {
        $url = $this->baseUrl . $path;

        if ($queryParams) {
            $url .= '?' . http_build_query($queryParams);
        }

        $headers = [
            'X-API-Key: ' . $this->apiKey,
            'Content-Type: application/json',
            'User-Agent: hookflow-php/' . self::SDK_VERSION,
        ];

        if ($idempotencyKey) {
            $headers[] = 'Idempotency-Key: ' . $idempotencyKey;
        }

        $ch = curl_init();
        curl_setopt_array($ch, [
            CURLOPT_URL => $url,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => $this->timeout,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_HEADER => true,
        ]);

        switch (strtoupper($method)) {
            case 'POST':
                curl_setopt($ch, CURLOPT_POST, true);
                if ($body !== null) {
                    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
                }
                break;
            case 'PUT':
                curl_setopt($ch, CURLOPT_CUSTOMREQUEST, 'PUT');
                if ($body !== null) {
                    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
                }
                break;
            case 'PATCH':
                curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method);
                if ($body !== null) {
                    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
                }
                break;
            case 'DELETE':
                curl_setopt($ch, CURLOPT_CUSTOMREQUEST, 'DELETE');
                break;
            case 'GET':
                break;
            default:
                // Without this, anything the switch does not name (OPTIONS,
                // HEAD, …) fell through and went out as a GET.
                curl_setopt($ch, CURLOPT_CUSTOMREQUEST, strtoupper($method));
                if ($body !== null) {
                    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
                }
                break;
        }

        $response = curl_exec($ch);
        $error = curl_error($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $headerSize = curl_getinfo($ch, CURLINFO_HEADER_SIZE);
        curl_close($ch);

        if ($error) {
            throw new Exception\HookflowException("cURL error: $error", 0);
        }

        $headerStr = substr($response, 0, $headerSize);
        $bodyStr = substr($response, $headerSize);

        $rateLimitInfo = $this->extractRateLimitInfo($headerStr);

        if ($httpCode === 204) {
            return null;
        }

        $data = json_decode($bodyStr, true) ?? [];

        if ($httpCode >= 400) {
            throw $this->handleError($httpCode, $data, $rateLimitInfo);
        }

        return $data;
    }

    /**
     * @return array{limit:int,remaining:int,reset:int}|null `reset` is a Unix
     *         timestamp in **seconds** — the raw X-RateLimit-Reset value.
     */
    private function extractRateLimitInfo(string $headers): ?array
    {
        $limit = null;
        $remaining = null;
        $reset = null;

        foreach (explode("\r\n", $headers) as $header) {
            if (stripos($header, 'X-RateLimit-Limit:') === 0) {
                $limit = (int) trim(substr($header, 18));
            } elseif (stripos($header, 'X-RateLimit-Remaining:') === 0) {
                $remaining = (int) trim(substr($header, 22));
            } elseif (stripos($header, 'X-RateLimit-Reset:') === 0) {
                $reset = (int) trim(substr($header, 18));
            }
        }

        if ($limit !== null && $remaining !== null && $reset !== null) {
            return ['limit' => $limit, 'remaining' => $remaining, 'reset' => $reset];
        }

        return null;
    }

    private function handleError(int $status, array $body, ?array $rateLimitInfo): Exception\HookflowException
    {
        $message = $body['message'] ?? 'Unknown error';

        return match ($status) {
            401 => new Exception\AuthenticationException($message),
            404 => new Exception\NotFoundException($message),
            429 => new Exception\RateLimitException($message, $rateLimitInfo ?? [
                // `reset` is a Unix timestamp in seconds, matching the raw
                // X-RateLimit-Reset header — not milliseconds.
                'limit' => 0,
                'remaining' => 0,
                'reset' => time() + 60,
            ]),
            400 => new Exception\ValidationException($message, $body['fieldErrors'] ?? []),
            // Everything the match does not name (403, 413, 422, 5xx) keeps the
            // envelope's own `error` code, so getErrorCode() is not null for
            // exactly the statuses the README's error table documents.
            default => new Exception\HookflowException($message, $status, $body['error'] ?? null),
        };
    }
}
