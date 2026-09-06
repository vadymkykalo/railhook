<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Railhook;
use Railhook\Api\IncomingSources;
use Railhook\Api\IncomingEvents;

class IncomingTest extends TestCase
{
    public function testClientInitializesIncomingModules(): void
    {
        $client = new Railhook('test_api_key');

        $this->assertInstanceOf(IncomingSources::class, $client->incomingSources);
        $this->assertInstanceOf(IncomingEvents::class, $client->incomingEvents);
    }

    public function testIncomingSourcesHasExpectedMethods(): void
    {
        $client = new Railhook('test_api_key');
        $sources = $client->incomingSources;

        $this->assertTrue(method_exists($sources, 'create'));
        $this->assertTrue(method_exists($sources, 'get'));
        $this->assertTrue(method_exists($sources, 'list'));
        $this->assertTrue(method_exists($sources, 'update'));
        $this->assertTrue(method_exists($sources, 'delete'));
        $this->assertTrue(method_exists($sources, 'createDestination'));
        $this->assertTrue(method_exists($sources, 'getDestination'));
        $this->assertTrue(method_exists($sources, 'listDestinations'));
        $this->assertTrue(method_exists($sources, 'updateDestination'));
        $this->assertTrue(method_exists($sources, 'deleteDestination'));
    }

    public function testIncomingEventsHasExpectedMethods(): void
    {
        $client = new Railhook('test_api_key');
        $events = $client->incomingEvents;

        $this->assertTrue(method_exists($events, 'list'));
        $this->assertTrue(method_exists($events, 'get'));
        $this->assertTrue(method_exists($events, 'getAttempts'));
        $this->assertTrue(method_exists($events, 'replay'));
    }

    public function testIncomingSourcesCreateSignature(): void
    {
        $client = new Railhook('test_api_key');
        $ref = new \ReflectionMethod($client->incomingSources, 'create');

        $params = $ref->getParameters();
        $this->assertCount(2, $params);
        $this->assertSame('projectId', $params[0]->getName());
        $this->assertSame('params', $params[1]->getName());
        $this->assertSame('string', $params[0]->getType()->getName());
        $this->assertSame('array', $params[1]->getType()->getName());
    }

    public function testIncomingSourcesCreateDestinationSignature(): void
    {
        $client = new Railhook('test_api_key');
        $ref = new \ReflectionMethod($client->incomingSources, 'createDestination');

        $params = $ref->getParameters();
        $this->assertCount(3, $params);
        $this->assertSame('projectId', $params[0]->getName());
        $this->assertSame('sourceId', $params[1]->getName());
        $this->assertSame('params', $params[2]->getName());
    }

    public function testIncomingSourcesDeleteDestinationSignature(): void
    {
        $client = new Railhook('test_api_key');
        $ref = new \ReflectionMethod($client->incomingSources, 'deleteDestination');

        $params = $ref->getParameters();
        $this->assertCount(3, $params);
        $this->assertSame('projectId', $params[0]->getName());
        $this->assertSame('sourceId', $params[1]->getName());
        $this->assertSame('destinationId', $params[2]->getName());
        $this->assertSame('void', $ref->getReturnType()->getName());
    }

    public function testIncomingEventsListSignature(): void
    {
        $client = new Railhook('test_api_key');
        $ref = new \ReflectionMethod($client->incomingEvents, 'list');

        $params = $ref->getParameters();
        $this->assertCount(2, $params);
        $this->assertSame('projectId', $params[0]->getName());
        $this->assertSame('queryParams', $params[1]->getName());
        $this->assertTrue($params[1]->isOptional());
    }

    public function testIncomingEventsReplaySignature(): void
    {
        $client = new Railhook('test_api_key');
        $ref = new \ReflectionMethod($client->incomingEvents, 'replay');

        $params = $ref->getParameters();
        $this->assertCount(2, $params);
        $this->assertSame('projectId', $params[0]->getName());
        $this->assertSame('eventId', $params[1]->getName());
        $this->assertSame('array', $ref->getReturnType()->getName());
    }
}
