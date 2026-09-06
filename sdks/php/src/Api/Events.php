<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

class Events
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Send an event to be delivered to subscribed endpoints.
     *
     * @param string $type Event type (e.g., "order.completed")
     * @param array $data Event payload data
     * @param string|null $idempotencyKey Unique key to prevent duplicates
     * @return array Event response with eventId, type, createdAt, deliveriesCreated, and
     *               schemaWarnings when the project validates schemas with the WARN policy
     */
    public function send(string $type, array $data, ?string $idempotencyKey = null): array
    {
        return $this->client->request(
            'POST',
            '/api/v1/events',
            ['type' => $type, 'data' => $data],
            null,
            $idempotencyKey
        );
    }
}
