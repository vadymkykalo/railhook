<?php

declare(strict_types=1);

namespace Railhook\Tests;

use Railhook\Exception\RailhookException;
use Railhook\Webhook;
use PHPUnit\Framework\TestCase;

// The reference algorithm, not a round-trip: a round-trip would only prove we agree with our own bug.
class StandardWebhookTest extends TestCase
{
    private const MESSAGE_ID = 'msg_p5jXN8AQM9LWM0D4loKWxJek';
    private const PAYLOAD = '{"test": 2432232314}';
    private const SECRET_B64 = 'MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw';

    /** Exactly what the reference libraries do. */
    private function sign(
        int $ts,
        string $secretB64 = self::SECRET_B64,
        string $messageId = self::MESSAGE_ID,
        string $payload = self::PAYLOAD
    ): string {
        $key = base64_decode($secretB64, true);
        return base64_encode(hash_hmac('sha256', "{$messageId}.{$ts}.{$payload}", $key, true));
    }

    private function headers(int $ts, string $signature): array
    {
        return [
            'webhook-id' => self::MESSAGE_ID,
            'webhook-timestamp' => (string) $ts,
            'webhook-signature' => $signature,
        ];
    }

    private function sharedSecret(string $b64 = self::SECRET_B64): string
    {
        return 'whsec_' . $b64;
    }

    public function testAcceptsAReferenceSignature(): void
    {
        $ts = time();
        $this->assertTrue(Webhook::verifyStandardWebhook(
            self::PAYLOAD,
            $this->headers($ts, 'v1,' . $this->sign($ts)),
            $this->sharedSecret()
        ));
    }

    public function testHeaderNamesAreCaseInsensitive(): void
    {
        // Frameworks disagree on how they case header names.
        $ts = time();
        $this->assertTrue(Webhook::verifyStandardWebhook(
            self::PAYLOAD,
            [
                'Webhook-Id' => self::MESSAGE_ID,
                'Webhook-Timestamp' => (string) $ts,
                'Webhook-Signature' => 'v1,' . $this->sign($ts),
            ],
            $this->sharedSecret()
        ));
    }

    public function testEitherSecretVerifiesDuringARotation(): void
    {
        $ts = time();
        $retired = 'b2xkLXNlY3JldC1ieXRlcy1oZXJlLXBhZGRpbmc=';
        $header = 'v1,' . $this->sign($ts) . ' v1,' . $this->sign($ts, $retired);

        $this->assertTrue(Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($ts, $header), $this->sharedSecret()));
        $this->assertTrue(Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($ts, $header), $this->sharedSecret($retired)));
    }

    public function testRotationHeaderVerifiesWhenTheMatchIsLastBesideAnUnknownVersion(): void
    {
        $ts = time();
        $retired = 'b2xkLXNlY3JldC1ieXRlcy1oZXJlLXBhZGRpbmc=';
        $header = 'v1,' . $this->sign($ts, $retired) . ' v2,' . $this->sign($ts) . ' v1,' . $this->sign($ts);

        $this->assertTrue(Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($ts, $header), $this->sharedSecret()));
    }

    public function testRotationHeaderStillEnforcesTheTolerance(): void
    {
        $old = time() - 3600;
        $retired = 'b2xkLXNlY3JldC1ieXRlcy1oZXJlLXBhZGRpbmc=';
        $header = 'v1,' . $this->sign($old) . ' v1,' . $this->sign($old, $retired);

        $this->expectException(RailhookException::class);
        $this->expectExceptionMessage('outside tolerance window');
        Webhook::verifyStandardWebhook(self::PAYLOAD, $this->headers($old, $header), $this->sharedSecret());
    }

    public function testAcceptsArrayValuedHeadersAsLaravelAndSymfonyGiveThem(): void
    {
        // $request->headers->all() in both frameworks maps each name to a list of values.
        $ts = time();
        $headers = array_map(
            fn (string $value): array => [$value],
            $this->headers($ts, 'v1,' . $this->sign($ts))
        );

        $this->assertTrue(Webhook::verifyStandardWebhook(self::PAYLOAD, $headers, $this->sharedSecret()));
    }

    public function testRejectsAReplayDespiteAValidSignature(): void
    {
        // A signature over a fixed body never expires by itself, so without the timestamp
        // check a captured request stays replayable for as long as the secret lives.
        $old = time() - 3600;
        $this->expectException(RailhookException::class);
        Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($old, 'v1,' . $this->sign($old)), $this->sharedSecret());
    }

    public function testRejectsASignatureFromAnotherMessage(): void
    {
        $ts = time();
        $other = $this->sign($ts, self::SECRET_B64, 'msg_somethingelse');
        $this->expectException(RailhookException::class);
        Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($ts, 'v1,' . $other), $this->sharedSecret());
    }

    public function testRejectsATamperedBody(): void
    {
        $ts = time();
        $this->expectException(RailhookException::class);
        Webhook::verifyStandardWebhook(
            '{"test": 1}', $this->headers($ts, 'v1,' . $this->sign($ts)), $this->sharedSecret());
    }

    public function testMissingHeadersAreReportedNotTreatedAsUnsigned(): void
    {
        $this->expectException(RailhookException::class);
        Webhook::verifyStandardWebhook(
            self::PAYLOAD, ['webhook-id' => self::MESSAGE_ID], $this->sharedSecret());
    }

    public function testUnknownSignatureVersionIsIgnored(): void
    {
        $ts = time();
        $this->expectException(RailhookException::class);
        Webhook::verifyStandardWebhook(
            self::PAYLOAD, $this->headers($ts, 'v2,' . $this->sign($ts)), $this->sharedSecret());
    }
}
