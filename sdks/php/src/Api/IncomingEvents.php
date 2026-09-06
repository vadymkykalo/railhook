<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class IncomingEvents
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * List incoming events for a project.
     *
     * @param string $projectId Project UUID
     * @param array|null $queryParams Optional filters: sourceId, page, size
     * @return array Paginated incoming events
     */
    public function list(string $projectId, ?array $queryParams = null): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-events",
            null,
            $queryParams
        );
    }

    /**
     * Get incoming event by ID.
     */
    public function get(string $projectId, string $eventId): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-events/{$eventId}"
        );
    }

    /**
     * Get forward attempts for an incoming event.
     */
    public function getAttempts(string $projectId, string $eventId, ?array $queryParams = null): array
    {
        return $this->client->request(
            'GET',
            "/api/v1/projects/{$projectId}/incoming-events/{$eventId}/attempts",
            null,
            $queryParams
        );
    }

    /**
     * Replay an incoming event to all enabled destinations.
     */
    public function replay(string $projectId, string $eventId): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/incoming-events/{$eventId}/replay"
        );
    }
}
