<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Webhook;
use Railhook\Exception\RailhookException;

class WebhookTest extends TestCase
{
    private const SECRET = 'whsec_test_secret_key_123';
    private const PAYLOAD = '{"type": "order.completed", "data": {"orderId": "12345"}}';

    public function testGenerateSignatureHasValidFormat(): void
    {
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET);
        
        $this->assertMatchesRegularExpression('/^t=\d+,v1=[a-f0-9]{64}$/', $signature);
    }

    public function testGenerateSignatureUsesProvidedTimestamp(): void
    {
        $timestamp = 1700000000000;
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        $this->assertStringContainsString("t={$timestamp}", $signature);
    }

    public function testGenerateSignatureIsConsistent(): void
    {
        $timestamp = 1700000000000;
        
        $sig1 = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        $sig2 = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        $this->assertSame($sig1, $sig2);
    }

    public function testGenerateSignatureDifferentPayloadsDifferentSignatures(): void
    {
        $timestamp = 1700000000000;
        
        $sig1 = Webhook::generateSignature('{"a": 1}', self::SECRET, $timestamp);
        $sig2 = Webhook::generateSignature('{"b": 2}', self::SECRET, $timestamp);
        
        $this->assertNotSame($sig1, $sig2);
    }

    public function testGenerateSignatureDifferentSecretsDifferentSignatures(): void
    {
        $timestamp = 1700000000000;
        
        $sig1 = Webhook::generateSignature(self::PAYLOAD, 'secret1', $timestamp);
        $sig2 = Webhook::generateSignature(self::PAYLOAD, 'secret2', $timestamp);
        
        $this->assertNotSame($sig1, $sig2);
    }

    public function testVerifySignatureValidSignature(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        $result = Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET);
        
        $this->assertTrue($result);
    }

    /**
     * After a rotation Railhook signs each delivery with the new secret and the retired
     * one for the endpoint's grace window, so a receiver that has not deployed the new
     * secret yet keeps working. The parser used to keep only the last v1 and rejected
     * whichever half of the pair the receiver was holding.
     */
    private function dualSignatureHeader(string $payload, string $newSecret, string $retiredSecret): string
    {
        $timestamp = (int) (microtime(true) * 1000);
        $retired = Webhook::generateSignature($payload, $retiredSecret, $timestamp);
        $retiredV1 = substr($retired, strpos($retired, 'v1=') + 3);

        return Webhook::generateSignature($payload, $newSecret, $timestamp) . ',v1=' . $retiredV1;
    }

    public function testVerifySignatureAcceptsTheNewSecretDuringRotation(): void
    {
        $header = $this->dualSignatureHeader(self::PAYLOAD, 'whsec_new', 'whsec_retired');

        $this->assertTrue(Webhook::verifySignature(self::PAYLOAD, $header, 'whsec_new'));
    }

    public function testVerifySignatureAcceptsTheRetiredSecretDuringRotation(): void
    {
        $header = $this->dualSignatureHeader(self::PAYLOAD, 'whsec_new', 'whsec_retired');

        $this->assertTrue(Webhook::verifySignature(self::PAYLOAD, $header, 'whsec_retired'));
    }

    public function testVerifySignatureStillRejectsAnUnrelatedSecretWithTwoSignatures(): void
    {
        $header = $this->dualSignatureHeader(self::PAYLOAD, 'whsec_new', 'whsec_retired');

        $this->expectException(RailhookException::class);
        Webhook::verifySignature(self::PAYLOAD, $header, 'whsec_someone_else');
    }

    public function testVerifySignatureStillRejectsATamperedBodyWithTwoSignatures(): void
    {
        $header = $this->dualSignatureHeader(self::PAYLOAD, 'whsec_new', 'whsec_retired');

        $this->expectException(RailhookException::class);
        Webhook::verifySignature(self::PAYLOAD . ' ', $header, 'whsec_new');
    }

    public function testVerifySignatureThrowsOnMissingSignature(): void
    {
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Missing signature header');
        
        Webhook::verifySignature(self::PAYLOAD, '', self::SECRET);
    }

    public function testVerifySignatureThrowsOnInvalidFormat(): void
    {
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid signature format');
        
        Webhook::verifySignature(self::PAYLOAD, 'invalid_format', self::SECRET);
    }

    public function testVerifySignatureThrowsOnMissingTimestamp(): void
    {
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid signature format');
        
        Webhook::verifySignature(self::PAYLOAD, 'v1=abc123', self::SECRET);
    }

    public function testVerifySignatureThrowsOnMissingV1(): void
    {
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid signature format');
        
        Webhook::verifySignature(self::PAYLOAD, 't=1700000000000', self::SECRET);
    }

    public function testVerifySignatureThrowsOnExpiredTimestamp(): void
    {
        $oldTimestamp = (int) (microtime(true) * 1000) - 600000; // 10 min ago
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $oldTimestamp);
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('outside tolerance window');
        
        Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET);
    }

    public function testVerifySignatureThrowsOnFutureTimestamp(): void
    {
        $futureTimestamp = (int) (microtime(true) * 1000) + 600000; // 10 min in future
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $futureTimestamp);
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('outside tolerance window');
        
        Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET);
    }

    public function testVerifySignatureAcceptsRecentTimestamp(): void
    {
        $recentTimestamp = (int) (microtime(true) * 1000) - 60000; // 1 min ago
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $recentTimestamp);
        
        $result = Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET);
        
        $this->assertTrue($result);
    }

    public function testVerifySignatureThrowsOnInvalidSignatureValue(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid signature');
        
        Webhook::verifySignature(self::PAYLOAD, "t={$timestamp},v1=invalid", self::SECRET);
    }

    public function testVerifySignatureThrowsOnTamperedPayload(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        $tamperedPayload = '{"type": "hacked"}';
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid signature');
        
        Webhook::verifySignature($tamperedPayload, $signature, self::SECRET);
    }

    public function testVerifySignatureRespectsCustomTolerance(): void
    {
        $oldTimestamp = (int) (microtime(true) * 1000) - 60000; // 1 min ago
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $oldTimestamp);
        
        // Should fail with 30s tolerance
        $this->expectException(RailhookException::class);
        Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET, 30000);
    }

    public function testVerifySignaturePassesWithLargerTolerance(): void
    {
        $oldTimestamp = (int) (microtime(true) * 1000) - 60000; // 1 min ago
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $oldTimestamp);
        
        // Should pass with 2min tolerance
        $result = Webhook::verifySignature(self::PAYLOAD, $signature, self::SECRET, 120000);
        
        $this->assertTrue($result);
    }

    public function testConstructEventFromValidRequest(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        $headers = [
            'x-signature' => $signature,
            'x-timestamp' => (string) $timestamp,
            'x-event-id' => 'evt_123',
            'x-delivery-id' => 'dlv_456',
        ];
        
        $event = Webhook::constructEvent(self::PAYLOAD, $headers, self::SECRET);
        
        $this->assertSame('evt_123', $event['eventId']);
        $this->assertSame('dlv_456', $event['deliveryId']);
        $this->assertSame($timestamp, $event['timestamp']);
        $this->assertSame('order.completed', $event['type']);
        $this->assertSame(['orderId' => '12345'], $event['data']);
    }

    public function testConstructEventHandlesUppercaseHeaders(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        $headers = [
            'X-Signature' => $signature,
            'X-Timestamp' => (string) $timestamp,
            'X-Event-Id' => 'evt_123',
            'X-Delivery-Id' => 'dlv_456',
        ];
        
        $event = Webhook::constructEvent(self::PAYLOAD, $headers, self::SECRET);
        
        $this->assertSame('evt_123', $event['eventId']);
    }

    public function testConstructEventThrowsOnMissingSignature(): void
    {
        $headers = ['x-timestamp' => '1700000000000'];
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Missing X-Signature header');
        
        Webhook::constructEvent(self::PAYLOAD, $headers, self::SECRET);
    }

    public function testConstructEventThrowsOnInvalidJson(): void
    {
        $invalidPayload = 'not valid json';
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature($invalidPayload, self::SECRET, $timestamp);
        
        $headers = ['x-signature' => $signature];
        
        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('Invalid JSON payload');
        
        Webhook::constructEvent($invalidPayload, $headers, self::SECRET);
    }

    public function testConstructEventHandlesFlatPayload(): void
    {
        $flatPayload = '{"type": "test.event", "value": 123}';
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature($flatPayload, self::SECRET, $timestamp);
        
        $headers = ['x-signature' => $signature];
        
        $event = Webhook::constructEvent($flatPayload, $headers, self::SECRET);
        
        $this->assertSame('test.event', $event['type']);
        $this->assertSame(['type' => 'test.event', 'value' => 123], $event['data']);
    }

    public function testConstructEventHandlesArrayHeaders(): void
    {
        $timestamp = (int) (microtime(true) * 1000);
        $signature = Webhook::generateSignature(self::PAYLOAD, self::SECRET, $timestamp);
        
        // Some frameworks pass headers as arrays
        $headers = [
            'x-signature' => [$signature],
            'x-timestamp' => [(string) $timestamp],
            'x-event-id' => ['evt_123'],
        ];
        
        $event = Webhook::constructEvent(self::PAYLOAD, $headers, self::SECRET);
        
        $this->assertSame('evt_123', $event['eventId']);
    }
}
