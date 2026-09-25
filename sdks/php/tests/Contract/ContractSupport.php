<?php

declare(strict_types=1);

namespace Railhook\Tests\Contract;

// The SDK has no register/login surface, so the throwaway tenant is bootstrapped with raw cURL.
final class ContractSupport
{
    // Meets the API's password complexity policy.
    private const PASSWORD = 'ContractTest!2026x';

    public static function baseUrl(): string
    {
        return getenv('CONTRACT_API_BASE_URL') ?: 'http://localhost:8080';
    }

    // A login probe: actuator is not published to the host and /v3/api-docs is off by default, so
    // either would silently skip the suite on a healthy stack.
    public static function isApiReachable(): bool
    {
        $ch = curl_init(self::baseUrl() . '/api/v1/auth/login');
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => '{}',
            CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
            CURLOPT_TIMEOUT => 3,
        ]);
        curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $error = curl_error($ch);
        curl_close($ch);

        return $error === '' && $httpCode > 0;
    }

    private static function call(string $method, string $path, array $body, array $headers = []): array
    {
        $ch = curl_init(self::baseUrl() . $path);
        $defaultHeaders = array_merge(['Content-Type: application/json'], $headers);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_HTTPHEADER => $defaultHeaders,
            CURLOPT_POSTFIELDS => json_encode($body),
            CURLOPT_TIMEOUT => 10,
        ]);
        $response = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        if ($httpCode >= 400) {
            throw new \RuntimeException("$method $path failed: HTTP $httpCode $response");
        }

        return json_decode((string) $response, true) ?? [];
    }

    /** @return array{projectId: string, apiKey: string, accessToken: string} */
    public static function bootstrapContractProject(string $prefix): array
    {
        $suffix = (string) (int) (microtime(true) * 1000) . '-' . bin2hex(random_bytes(4));

        $auth = self::call('POST', '/api/v1/auth/register', [
            'email' => "{$prefix}-{$suffix}@php-contract-test.invalid",
            'password' => self::PASSWORD,
            'fullName' => "PHP Contract Test {$prefix}",
            'organizationName' => substr("php-contract-{$suffix}", 0, 100),
        ]);
        $accessToken = $auth['accessToken'];
        $authHeader = ["Authorization: Bearer {$accessToken}"];

        $project = self::call('POST', '/api/v1/projects', [
            'name' => substr("php-contract-{$suffix}", 0, 100),
        ], $authHeader);

        $apiKeyResponse = self::call(
            'POST',
            "/api/v1/projects/{$project['id']}/api-keys",
            ['name' => "php-contract-key-{$suffix}", 'scope' => 'READ_WRITE'],
            $authHeader
        );

        return [
            'projectId' => $project['id'],
            'apiKey' => $apiKeyResponse['key'],
            'accessToken' => $accessToken,
        ];
    }
}
