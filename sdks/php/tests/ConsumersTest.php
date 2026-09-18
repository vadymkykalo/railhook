<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Api\Consumers;
use Railhook\Api\PortalSessions;
use Railhook\Railhook;

/**
 * Consumers and portal sessions, with the transport replaced: each call is
 * checked for the method, path, body and query it hands to Railhook::request.
 */
class ConsumersTest extends TestCase
{
    private const PROJECT = 'proj-123';
    private const CONSUMER = 'con-456';

    /** @var list<array{0:string,1:string,2:?array,3:?array}> */
    private array $calls = [];

    private function client(mixed $response = []): Railhook
    {
        $client = $this->getMockBuilder(Railhook::class)
            ->setConstructorArgs(['test_api_key'])
            ->onlyMethods(['request'])
            ->getMock();
        $client->method('request')->willReturnCallback(
            function (string $method, string $path, ?array $body = null, ?array $query = null) use ($response) {
                $this->calls[] = [$method, $path, $body, $query];
                return $response;
            }
        );
        return $client;
    }

    public function testClientInitializesTheModules(): void
    {
        $client = new Railhook('test_api_key');

        $this->assertInstanceOf(Consumers::class, $client->consumers);
        $this->assertInstanceOf(PortalSessions::class, $client->portalSessions);
    }

    public function testCreatePostsTheExternalIdAndName(): void
    {
        $client = $this->client(['id' => self::CONSUMER, 'externalId' => 'user-42']);

        $consumer = $client->consumers->create(self::PROJECT, ['externalId' => 'user-42', 'name' => 'Acme Ltd']);

        $this->assertSame(
            ['POST', '/api/v1/projects/proj-123/consumers', ['externalId' => 'user-42', 'name' => 'Acme Ltd'], null],
            $this->calls[0]
        );
        $this->assertSame(self::CONSUMER, $consumer['id']);
    }

    public function testListPassesTheFiltersAsQuery(): void
    {
        $client = $this->client(['content' => [], 'totalElements' => 0]);

        $client->consumers->list(self::PROJECT, ['externalId' => 'user-42', 'page' => 0, 'size' => 10]);

        $this->assertSame(
            ['GET', '/api/v1/projects/proj-123/consumers', null, ['externalId' => 'user-42', 'page' => 0, 'size' => 10]],
            $this->calls[0]
        );
    }

    public function testGetUpdateDeleteAndListEndpointsAddressTheConsumer(): void
    {
        $client = $this->client();
        $path = '/api/v1/projects/proj-123/consumers/con-456';

        $client->consumers->get(self::PROJECT, self::CONSUMER);
        $client->consumers->update(self::PROJECT, self::CONSUMER, ['externalId' => 'user-42', 'name' => 'Acme Inc']);
        $client->consumers->listEndpoints(self::PROJECT, self::CONSUMER);
        $client->consumers->delete(self::PROJECT, self::CONSUMER);

        $this->assertSame(['GET', $path], array_slice($this->calls[0], 0, 2));
        $this->assertSame(['PUT', $path, ['externalId' => 'user-42', 'name' => 'Acme Inc']], array_slice($this->calls[1], 0, 3));
        $this->assertSame(['GET', $path . '/endpoints'], array_slice($this->calls[2], 0, 2));
        $this->assertSame(['DELETE', $path], array_slice($this->calls[3], 0, 2));
    }

    public function testPortalSessionCreatePostsTheTtlAndOrigin(): void
    {
        $client = $this->client(['token' => 'rhp_abc', 'url' => 'https://railhook.test/portal#rhp_abc']);

        $session = $client->portalSessions->create(self::PROJECT, self::CONSUMER, [
            'ttlMinutes' => 30,
            'allowedOrigin' => 'https://app.example.com',
        ]);

        $this->assertSame(
            ['POST', '/api/v1/projects/proj-123/consumers/con-456/portal-sessions',
                ['ttlMinutes' => 30, 'allowedOrigin' => 'https://app.example.com'], null],
            $this->calls[0]
        );
        $this->assertSame('rhp_abc', $session['token']);
    }

    public function testPortalSessionCreateWithoutParamsSendsNoBody(): void
    {
        $client = $this->client(['token' => 'rhp_abc']);

        $client->portalSessions->create(self::PROJECT, self::CONSUMER);

        // json_encode([]) would be a JSON array, which the API cannot read as the request object.
        $this->assertNull($this->calls[0][2]);
    }

    public function testPortalSessionRevokeDeletesTheSessions(): void
    {
        $client = $this->client(null);

        $client->portalSessions->revoke(self::PROJECT, self::CONSUMER);

        $this->assertSame(
            ['DELETE', '/api/v1/projects/proj-123/consumers/con-456/portal-sessions'],
            array_slice($this->calls[0], 0, 2)
        );
    }

    public function testEndpointCreateForwardsTheConsumerId(): void
    {
        $client = $this->client(['id' => 'ep-1', 'consumerId' => self::CONSUMER]);

        $client->endpoints->create(self::PROJECT, ['url' => 'https://example.com/hook', 'consumerId' => self::CONSUMER]);

        $this->assertSame(self::CONSUMER, $this->calls[0][2]['consumerId']);
    }
}
