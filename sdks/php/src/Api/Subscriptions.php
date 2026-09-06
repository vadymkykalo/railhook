<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class Subscriptions
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Create a new subscription.
     */
    public function create(string $projectId, array $params): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/subscriptions",
            $params
        );
    }

    /**
     * Get subscription by ID.
     */
    public function get(string $projectId, string $subscriptionId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/subscriptions/{$subscriptionId}"
        );
    }

    /**
     * List all subscriptions for a project.
     */
    public function list(string $projectId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/subscriptions"
        );
    }

    /**
     * Update subscription.
     */
    public function update(string $projectId, string $subscriptionId, array $params): array
    {
        return $this->client->request(
            'PUT',
            "/api/v1/projects/{$projectId}/subscriptions/{$subscriptionId}",
            $params
        );
    }

    /**
     * Delete subscription.
     */
    public function delete(string $projectId, string $subscriptionId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/subscriptions/{$subscriptionId}"
        );
    }
}
