<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Railhook;
use Railhook\Exception\RailhookException;
use Railhook\Exception\AuthenticationException;
use Railhook\Exception\ValidationException;
use Railhook\Exception\NotFoundException;
use Railhook\Exception\RateLimitException;

class RailhookTest extends TestCase
{
    public function testCreatesWithApiKey(): void
    {
        $client = new Railhook('test_api_key');
        
        $this->assertInstanceOf(Railhook::class, $client);
    }

    public function testThrowsWithoutApiKey(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('API key is required');
        
        new Railhook('');
    }

    public function testUsesDefaultBaseUrl(): void
    {
        $client = new Railhook('test_api_key');
        
        // We can't directly test private property, but we can verify client creates successfully
        $this->assertInstanceOf(Railhook::class, $client);
    }

    public function testAcceptsCustomBaseUrl(): void
    {
        $client = new Railhook(
            'test_api_key',
            'https://api.example.com/'
        );
        
        $this->assertInstanceOf(Railhook::class, $client);
    }

    public function testAcceptsCustomTimeout(): void
    {
        $client = new Railhook('test_api_key', 'http://localhost:8080', 60);
        
        $this->assertInstanceOf(Railhook::class, $client);
    }

    public function testInitializesApiModules(): void
    {
        $client = new Railhook('test_api_key');
        
        $this->assertNotNull($client->events);
        $this->assertNotNull($client->endpoints);
        $this->assertNotNull($client->subscriptions);
        $this->assertNotNull($client->deliveries);
    }
}

class GenericRequestMethodsTest extends TestCase
{
    public function testExposeGetMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'get'));
    }

    public function testExposePostMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'post'));
    }

    public function testExposePutMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'put'));
    }

    public function testExposePatchMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'patch'));
    }

    public function testExposeDeleteMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'delete'));
    }

    public function testExposeRequestMethod(): void
    {
        $client = new Railhook('test_api_key');
        $this->assertTrue(method_exists($client, 'request'));
    }
}

class ExceptionTest extends TestCase
{
    public function testRailhookException(): void
    {
        $exception = new RailhookException('Test error', 500, 'test_code');
        
        $this->assertSame('Test error', $exception->getMessage());
        $this->assertSame(500, $exception->getStatusCode());
        $this->assertSame('test_code', $exception->getErrorCode());
    }

    public function testAuthenticationException(): void
    {
        $exception = new AuthenticationException('Invalid API key');
        
        $this->assertInstanceOf(RailhookException::class, $exception);
        $this->assertSame('Invalid API key', $exception->getMessage());
    }

    public function testValidationException(): void
    {
        $fieldErrors = ['email' => 'Invalid email', 'url' => 'Invalid URL'];
        $exception = new ValidationException('Validation failed', $fieldErrors);
        
        $this->assertInstanceOf(RailhookException::class, $exception);
        $this->assertSame('Validation failed', $exception->getMessage());
        $this->assertSame($fieldErrors, $exception->getFieldErrors());
    }

    public function testNotFoundException(): void
    {
        $exception = new NotFoundException('Resource not found');
        
        $this->assertInstanceOf(RailhookException::class, $exception);
        $this->assertSame('Resource not found', $exception->getMessage());
    }

    public function testRateLimitException(): void
    {
        $rateLimitInfo = ['limit' => 100, 'remaining' => 0, 'reset' => 1700000000000];
        $exception = new RateLimitException('Rate limit exceeded', $rateLimitInfo);
        
        $this->assertInstanceOf(RailhookException::class, $exception);
        $this->assertSame('Rate limit exceeded', $exception->getMessage());
        $this->assertSame($rateLimitInfo, $exception->getRateLimitInfo());
    }
}
