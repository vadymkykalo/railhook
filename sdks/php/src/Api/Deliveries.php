<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class Deliveries
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Get delivery by ID.
     */
    public function get(string $deliveryId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/deliveries/{$deliveryId}"
        );
    }

    /**
     * List deliveries for a project with optional filters.
     *
     * @param string $projectId Project ID
     * @param array $params Optional filters: status, endpointId, fromDate, toDate, page, size
     */
    public function list(string $projectId, array $params = []): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/deliveries/projects/{$projectId}",
            null,
            $params
        );
    }

    /**
     * Get all delivery attempts.
     */
    public function getAttempts(string $deliveryId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/deliveries/{$deliveryId}/attempts"
        );
    }

    /**
     * Replay a failed delivery.
     */
    public function replay(string $deliveryId): void
    {
        $this->client->request(
            'POST',
            "/api/v1/deliveries/{$deliveryId}/replay"
        );
    }
}
