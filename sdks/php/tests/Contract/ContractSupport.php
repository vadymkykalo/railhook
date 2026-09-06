<?php

declare(strict_types=1);

namespace Railhook\Tests\Contract;

/**
 * Shared bootstrap for the PHP SDK's contract suite. Same pattern as
 * sdks/node/tests/contract/support.ts, sdks/python/tests/contract/support.py
 * and load/lib/setup.js: the Railhook client is API-key scoped only (no
 * register/login/create-project surface — see src/Railhook.php), so
 * bootstrapping a throwaway tenant needs a couple of raw cURL calls against
 * the JWT-authenticated endpoints before the SDK proper takes over.
 */
final class ContractSupport
{
    // meets AuthController's complexity policy (upper, lower, digit, special char)
    private const PASSWORD = 'ContractTest!2026x';

    public static function baseUrl(): string
    {
        return getenv('CONTRACT_API_BASE_URL') ?: 'http://localhost:8080';
    }

    /**
     * Probes with an intentionally invalid login: any HTTP response at all
     * proves the API is answering.
     *
     * Deliberately does NOT hit /actuator/health/liveness: under `make up`
     * (docker-compose.yml), actuator is served on its own MANAGEMENT_PORT
     * (8082) which is never published to the host — and on the main port
     * /actuator/health is a 500, not a 404, because nothing maps it. Nor
     * /v3/api-docs: springdoc is only permitAll when SWAGGER_ENABLED=true
     * (SecurityConfig.java) and .env.dist ships it false, so probing it
     * reports a perfectly healthy stack as unreachable and silently skips
     * this whole suite. /api/v1/auth/login is permitAll unconditionally.
     */
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

    /**
     * @return array{projectId: string, apiKey: string, accessToken: string}
     */
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
