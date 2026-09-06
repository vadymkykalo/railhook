<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class Endpoints
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Create a new endpoint.
     */
    public function create(string $projectId, array $params): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/endpoints",
            $params
        );
    }

    /**
     * Get endpoint by ID.
     */
    public function get(string $projectId, string $endpointId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/endpoints/{$endpointId}"
        );
    }

    /**
     * List all endpoints for a project.
     */
    public function list(string $projectId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/endpoints"
        );
    }

    /**
     * Update endpoint.
     */
    public function update(string $projectId, string $endpointId, array $params): array
    {
        return $this->client->request(
            'PUT',
            "/api/v1/projects/{$projectId}/endpoints/{$endpointId}",
            $params
        );
    }

    /**
     * Delete endpoint.
     */
    public function delete(string $projectId, string $endpointId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/endpoints/{$endpointId}"
        );
    }

    /**
     * Rotate endpoint webhook secret.
     */
    public function rotateSecret(string $projectId, string $endpointId): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/endpoints/{$endpointId}/rotate-secret"
        );
    }

    /**
     * Test endpoint connectivity.
     */
    public function test(string $projectId, string $endpointId): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/endpoints/{$endpointId}/test"
        );
    }
}
