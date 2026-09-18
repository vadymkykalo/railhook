<?php

declare(strict_types=1);

namespace Railhook\Api;

use Railhook\Railhook;

/**
 * Portal sessions: open the customer portal for one of your consumers.
 */
class PortalSessions
{
    private Railhook $client;

    public function __construct(Railhook $client)
    {
        $this->client = $client;
    }

    /**
     * Open a portal session. Hand the returned `url` to the consumer's browser;
     * the `token` in it is returned here and never again.
     *
     * @param array $params Optional: ttlMinutes (1-1440, default 60), allowedOrigin (https origin that embeds the portal)
     * @return array id, consumerId, url, token, allowedOrigin, expiresAt
     */
    public function create(string $projectId, string $consumerId, array $params = []): array
    {
        return $this->client->request(
            'POST',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}/portal-sessions",
            // No body rather than `[]`: json_encode([]) is a JSON array, not an object.
            $params === [] ? null : $params
        );
    }

    /**
     * End every open portal session of the consumer.
     */
    public function revoke(string $projectId, string $consumerId): void
    {
        $this->client->request(
            'DELETE',
            "/api/v1/projects/{$projectId}/consumers/{$consumerId}/portal-sessions"
        );
    }
}
