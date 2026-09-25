<?php

declare(strict_types=1);

namespace Railhook\Tests\Contract;

// Against a real API: the unit tests stub cURL and would stay green through a renamed field.

use PHPUnit\Framework\TestCase;
use Railhook\Railhook;
use Railhook\Exception\AuthenticationException;

class ClientContractTest extends TestCase
{
    private static bool $apiReachable = false;
    private static array $ctx;

    public static function setUpBeforeClass(): void
    {
        self::$apiReachable = ContractSupport::isApiReachable();
        if (!self::$apiReachable) {
            return;
        }
        self::$ctx = ContractSupport::bootstrapContractProject('php-sdk-client');
    }

    private function skipIfApiUnreachable(): void
    {
        if (!self::$apiReachable) {
            $this->markTestSkipped(
                'API not reachable at ' . ContractSupport::baseUrl() . ' (tried POST /api/v1/auth/login) — '
                . 'run `make up && make wait-healthy` first. See tests/Contract/README.md.'
            );
        }
    }

    private function makeClient(): Railhook
    {
        return new Railhook(self::$ctx['apiKey'], ContractSupport::baseUrl());
    }

    public function testEndpointsCreateReturnsTheShapeTheSdkExpects(): void
    {
        $this->skipIfApiUnreachable();
        $client = $this->makeClient();

        $endpoint = $client->endpoints->create(self::$ctx['projectId'], [
            'url' => 'https://example.com/webhook',
            'description' => 'contract test endpoint',
            'enabled' => true,
        ]);

        $this->assertIsString($endpoint['id']);
        $this->assertSame(self::$ctx['projectId'], $endpoint['projectId']);
        $this->assertSame('https://example.com/webhook', $endpoint['url']);
        $this->assertIsBool($endpoint['enabled']);
        $this->assertIsString($endpoint['createdAt']);
    }

    public function testSubscriptionsCreateReturnsTheShapeTheSdkExpects(): void
    {
        $this->skipIfApiUnreachable();
        $client = $this->makeClient();

        $endpoint = $client->endpoints->create(self::$ctx['projectId'], [
            'url' => 'https://example.com/webhook2',
        ]);
        $subscription = $client->subscriptions->create(self::$ctx['projectId'], [
            'endpointId' => $endpoint['id'],
            'eventType' => 'contract.test.created',
            'orderingEnabled' => false,
        ]);

        $this->assertIsString($subscription['id']);
        $this->assertSame($endpoint['id'], $subscription['endpointId']);
        $this->assertSame('contract.test.created', $subscription['eventType']);
        $this->assertIsBool($subscription['enabled']);
        $this->assertIsInt($subscription['maxAttempts']);
    }

    public function testEventsSendAcceptedAndFansOut(): void
    {
        $this->skipIfApiUnreachable();
        $client = $this->makeClient();

        $endpoint = $client->endpoints->create(self::$ctx['projectId'], [
            'url' => 'https://example.com/webhook3',
        ]);
        $client->subscriptions->create(self::$ctx['projectId'], [
            'endpointId' => $endpoint['id'],
            'eventType' => 'contract.test.event_send',
        ]);

        $response = $client->events->send('contract.test.event_send', ['hello' => 'world']);

        $this->assertIsString($response['eventId']);
        $this->assertSame('contract.test.event_send', $response['type']);
        $this->assertSame(1, $response['deliveriesCreated']);
    }

    public function testDeliveriesListReturnsAPaginatedResponse(): void
    {
        $this->skipIfApiUnreachable();
        $client = $this->makeClient();

        $page = $client->deliveries->list(self::$ctx['projectId'], ['size' => 5]);

        $this->assertIsArray($page['content']);
        $this->assertIsInt($page['totalElements']);
    }

    public function testInvalidApiKeyIsRejectedAs401(): void
    {
        $this->skipIfApiUnreachable();
        $badClient = new Railhook('not-a-real-key', ContractSupport::baseUrl());

        $this->expectException(AuthenticationException::class);
        $badClient->events->send('contract.test.bad_key', []);
    }
}
