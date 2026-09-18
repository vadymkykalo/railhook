<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

/**
 * Consumers: your own users, and the endpoints registered for them.
 */
class Consumers
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Register one of your users, by your own id for them.
     *
     * @param array $params externalId (required), name (optional; defaults to externalId)
     */
    public function create(string $projectId, array $params): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/consumers",
            $params
        );
    }

    /**
     * Get consumer by ID.
     */
    public function get(string $projectId, string $consumerId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}"
        );
    }

    /**
     * List a project's consumers.
     *
     * @param array|null $queryParams Optional: externalId (find the one you know by your own id), page, size
     * @return array Paginated consumers
     */
    public function list(string $projectId, ?array $queryParams = null): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/consumers",
            null,
            $queryParams
        );
    }

    /**
     * Update consumer.
     *
     * @param array $params externalId (required), name (optional; omitted leaves it unchanged)
     */
    public function update(string $projectId, string $consumerId, array $params): array
    {
        return $this->client->request(
            'PUT',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}",
            $params
        );
    }

    /**
     * Delete the consumer, delete its endpoints and end its portal sessions.
     */
    public function delete(string $projectId, string $consumerId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}"
        );
    }

    /**
     * The endpoints registered for this consumer.
     */
    public function listEndpoints(string $projectId, string $consumerId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}/endpoints"
        );
    }
}
