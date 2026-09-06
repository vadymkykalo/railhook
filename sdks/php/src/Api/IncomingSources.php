<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class IncomingSources
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Create a new incoming webhook source.
     *
     * @param string $projectId Project UUID
     * @param array $params Source parameters: name (required), slug, providerType, verificationMode, hmacSecret, hmacHeaderName, hmacSignaturePrefix, rateLimitPerSecond
     * @return array IncomingSource response
     */
    public function create(string $projectId, array $params): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/incoming-sources",
            $params
        );
    }

    /**
     * Get incoming source by ID.
     */
    public function get(string $projectId, string $sourceId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}"
        );
    }

    /**
     * List incoming sources for a project.
     */
    public function list(string $projectId, ?array $queryParams = null): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-sources",
            null,
            $queryParams
        );
    }

    /**
     * Update incoming source.
     */
    public function update(string $projectId, string $sourceId, array $params): array
    {
        return $this->client->request(
            'PUT',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}",
            $params
        );
    }

    /**
     * Delete (disable) incoming source.
     */
    public function delete(string $projectId, string $sourceId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}"
        );
    }

    // ── Destinations ──

    /**
     * Create a forwarding destination for an incoming source.
     *
     * @param string $projectId Project UUID
     * @param string $sourceId Source UUID
     * @param array $params Destination parameters: url (required), authType, authConfig, customHeadersJson, enabled, maxAttempts, timeoutSeconds, retryDelays, payloadTransform
     * @return array IncomingDestination response
     */
    public function createDestination(string $projectId, string $sourceId, array $params): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}/destinations",
            $params
        );
    }

    /**
     * Get destination by ID.
     */
    public function getDestination(string $projectId, string $sourceId, string $destinationId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}/destinations/{$destinationId}"
        );
    }

    /**
     * List destinations for an incoming source.
     */
    public function listDestinations(string $projectId, string $sourceId, ?array $queryParams = null): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}/destinations",
            null,
            $queryParams
        );
    }

    /**
     * Update a forwarding destination.
     */
    public function updateDestination(string $projectId, string $sourceId, string $destinationId, array $params): array
    {
        return $this->client->request(
            'PUT',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}/destinations/{$destinationId}",
            $params
        );
    }

    /**
     * Delete a forwarding destination.
     */
    public function deleteDestination(string $projectId, string $sourceId, string $destinationId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/incoming-sources/{$sourceId}/destinations/{$destinationId}"
        );
    }
}
