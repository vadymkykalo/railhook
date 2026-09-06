<?php

declare(strict_types=1);

/**
 * Live-API smoke check for the PHP SDK.
 *
 * NOT a unit test. phpunit.xml's only testsuite is the `tests` directory, and
 * this file lives under `scripts/`, so PHPUnit never collects it — the unit
 * suite must stay green with no backend running, and this file must never be
 * the reason it isn't.
 *
 * What it does: registers a throwaway org against a REAL running API, then
 * drives the whole send-and-inspect workflow through the SDK's own public
 * methods and asserts what actually comes back — status codes, field names,
 * pagination envelope, error envelope, and a signature the server itself
 * produced. Stubbed-cURL unit tests are structurally unable to catch a renamed
 * field; this is what catches it.
 *
 * Usage:
 *   make up                                      # from the repo root
 *   cd sdks/php && php scripts/live-api-smoke.php
 *   # or, with no local PHP:
 *   docker run --rm -v "$PWD":/app -w /app php:8.2-cli php scripts/live-api-smoke.php
 *
 * Env:
 *   SMOKE_API_BASE_URL   target API (default http://localhost:8080)
 *
 * Exit code is 0 only if every check passed.
 */

// Composer's autoloader when the dev deps are installed; otherwise a two-line
// PSR-4 shim, so this runs against a bare checkout with no `composer install`.
$autoload = __DIR__ . '/../vendor/autoload.php';
if (file_exists($autoload)) {
    require $autoload;
} else {
    spl_autoload_register(static function (string $class): void {
        if (!str_starts_with($class, 'Railhook\\')) {
            return;
        }
        $path = __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen('Railhook\\'))) . '.php';
        if (file_exists($path)) {
            require $path;
        }
    });
}

use Railhook\Exception\AuthenticationException;
use Railhook\Exception\RailhookException;
use Railhook\Exception\NotFoundException;
use Railhook\Exception\ValidationException;
use Railhook\Railhook;
use Railhook\Webhook;

const PASSWORD = 'SmokeCheck!2026x'; // meets AuthController's complexity policy

$baseUrl = getenv('SMOKE_API_BASE_URL') ?: 'http://localhost:8080';
$passed = 0;
$failures = [];

$check = static function (string $label, callable $fn) use (&$passed, &$failures): void {
    try {
        $fn();
    } catch (\Throwable $e) {
        $failures[] = "{$label}: {$e->getMessage()}";
        echo "  FAIL {$label}\n         {$e->getMessage()}\n";
        return;
    }
    $passed++;
    echo "  ok   {$label}\n";
};

$expectError = static function (string $label, callable $fn, callable $assertOn) use (&$passed, &$failures): void {
    try {
        $fn();
    } catch (\Throwable $e) {
        try {
            $assertOn($e);
        } catch (\Throwable $assertErr) {
            $failures[] = "{$label}: {$assertErr->getMessage()}";
            echo "  FAIL {$label}\n         {$assertErr->getMessage()}\n";
            return;
        }
        $passed++;
        echo "  ok   {$label}\n";
        return;
    }
    $failures[] = "{$label}: the call returned instead of throwing";
    echo "  FAIL {$label}\n         the call returned instead of throwing\n";
};

/** Assert two values are identical, or throw with a readable diff. */
function eqv(mixed $actual, mixed $expected, string $what): void
{
    if ($actual !== $expected) {
        throw new \RuntimeException(sprintf(
            '%s: expected %s, got %s',
            $what,
            json_encode($expected),
            json_encode($actual)
        ));
    }
}

function truthy(bool $cond, string $what): void
{
    if (!$cond) {
        throw new \RuntimeException($what);
    }
}

/**
 * Raw HTTP, used ONLY to bootstrap a tenant. The SDK is API-key scoped by
 * design — it has no register/login/create-project surface (see src/Railhook.php)
 * — so these three calls cannot go through it. Everything after this point does.
 */
function raw(string $baseUrl, string $method, string $path, ?array $body = null, array $headers = []): mixed
{
    $ch = curl_init($baseUrl . $path);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_HTTPHEADER => array_merge(['Content-Type: application/json'], $headers),
    ]);
    if ($body !== null) {
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
    }
    $response = curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $error = curl_error($ch);
    curl_close($ch);

    if ($error !== '') {
        throw new \RuntimeException("cURL error on {$method} {$path}: {$error}");
    }
    if ($status >= 400) {
        throw new \RuntimeException("{$method} {$path} -> HTTP {$status} {$response}");
    }

    return $response === '' ? null : json_decode((string) $response, true);
}

function apiIsUp(string $baseUrl): bool
{
    // An intentionally invalid login: any HTTP response at all proves the API is
    // answering. Deliberately NOT /v3/api-docs — springdoc is only exposed when
    // SWAGGER_ENABLED=true (SecurityConfig.java), and it is false by default, so
    // probing it reports a healthy stack as unreachable.
    $ch = curl_init($baseUrl . '/api/v1/auth/login');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => '{}',
        CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
        CURLOPT_TIMEOUT => 5,
    ]);
    curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return $status > 0;
}

/** Re-run $fn until $ready says the API caught up, or the attempts run out. */
function poll(callable $fn, callable $ready, int $attempts = 20, int $delayMs = 500): mixed
{
    $value = $fn();
    for ($i = 1; $i < $attempts; $i++) {
        if ($ready($value)) {
            return $value;
        }
        usleep($delayMs * 1000);
        $value = $fn();
    }

    return $value;
}

echo "Railhook PHP SDK — live API smoke check against {$baseUrl}\n\n";

if (!apiIsUp($baseUrl)) {
    fwrite(STDERR, "{$baseUrl} is not answering. Start the stack with `make up` from the repo root.\n");
    exit(2);
}

// ── Bootstrap (raw HTTP: register / project / API key) ──
$suffix = sprintf('%d-%d', (int) (microtime(true) * 1000), random_int(0, 999999));
$auth = raw($baseUrl, 'POST', '/api/v1/auth/register', [
    'email' => "php-smoke-{$suffix}@php-smoke.invalid",
    'password' => PASSWORD,
    'fullName' => 'PHP Smoke Check',
    'organizationName' => substr("php-smoke-{$suffix}", 0, 100),
]);

echo "register:\n";
$check('register returns an accessToken', static fn () => truthy(is_string($auth['accessToken']) && $auth['accessToken'] !== '', 'no accessToken'));
$check('register returns refreshToken: null (nothing may assume it is present)', static fn () => eqv($auth['refreshToken'], null, 'refreshToken'));
$check('register reports emailVerified', static fn () => eqv(is_bool($auth['emailVerified']), true, 'typeof emailVerified'));

$bearer = ['Authorization: Bearer ' . $auth['accessToken']];
$project = raw($baseUrl, 'POST', '/api/v1/projects', ['name' => substr("php-smoke-{$suffix}", 0, 100)], $bearer);
// name has a 2-char minimum (ApiKeyRequest); a one-letter name is a 400.
$apiKey = raw($baseUrl, 'POST', "/api/v1/projects/{$project['id']}/api-keys", [
    'name' => substr("php-smoke-key-{$suffix}", 0, 100),
    'scope' => 'READ_WRITE',
], $bearer);

$projectId = $project['id'];
$client = new Railhook($apiKey['key'], $baseUrl);

// ── Endpoints ──
echo "\nendpoints:\n";
$endpoint = $client->endpoints->create($projectId, [
    'url' => 'https://example.com/php-smoke',
    'description' => 'php live smoke check',
    'enabled' => true,
]);
$check('endpoints.create returns the documented Endpoint shape', static function () use ($endpoint, $projectId) {
    truthy(is_string($endpoint['id']), 'id is not a string');
    eqv($endpoint['projectId'], $projectId, 'projectId');
    eqv($endpoint['url'], 'https://example.com/php-smoke', 'url');
    eqv($endpoint['enabled'], true, 'enabled');
    truthy(is_string($endpoint['createdAt']), 'createdAt is not a string');
    truthy(is_string($endpoint['secret']), 'secret is not a string');
});

$endpointPage = $client->endpoints->list($projectId);
$check('endpoints.list returns a page envelope, not a bare array', static function () use ($endpointPage, $endpoint) {
    truthy(array_key_exists('content', $endpointPage), 'no `content` key — the API sends a page envelope');
    truthy(is_array($endpointPage['content']), 'content is not an array');
    truthy(is_int($endpointPage['totalElements']), 'totalElements is not an int');
    $ids = array_column($endpointPage['content'], 'id');
    truthy(in_array($endpoint['id'], $ids, true), 'created endpoint missing from content');
});

$fetched = $client->endpoints->get($projectId, $endpoint['id']);
$check('endpoints.get round-trips the endpoint', static fn () => eqv($fetched['id'], $endpoint['id'], 'id'));

$testResult = $client->endpoints->test($projectId, $endpoint['id']);
$check('endpoints.test returns httpStatusCode/latencyMs (not httpStatus)', static function () use ($testResult) {
    truthy(is_bool($testResult['success']), 'success is not a bool');
    truthy(is_int($testResult['latencyMs']), 'latencyMs is not an int');
    truthy(array_key_exists('httpStatusCode', $testResult), 'no httpStatusCode key in the test result');
});

$rotated = $client->endpoints->rotateSecret($projectId, $endpoint['id']);
$check('endpoints.rotateSecret returns a different secret', static function () use ($rotated, $endpoint) {
    truthy(is_string($rotated['secret']), 'secret is not a string');
    truthy($rotated['secret'] !== $endpoint['secret'], 'secret did not change');
});

// ── Subscriptions ──
echo "\nsubscriptions:\n";
$subscription = $client->subscriptions->create($projectId, [
    'endpointId' => $endpoint['id'],
    'eventType' => 'order.completed',
]);
$check('subscriptions.create returns the documented Subscription shape', static function () use ($subscription, $endpoint) {
    eqv($subscription['endpointId'], $endpoint['id'], 'endpointId');
    eqv($subscription['eventType'], 'order.completed', 'eventType');
    truthy(is_int($subscription['maxAttempts']), 'maxAttempts is not an int');
    truthy(is_int($subscription['timeoutSeconds']), 'timeoutSeconds is not an int');
    truthy(array_key_exists('transformationId', $subscription), 'transformationId missing from the response');
});

$subs = $client->subscriptions->list($projectId);
$check('subscriptions.list returns a bare array (it is NOT paginated)', static function () use ($subs, $subscription) {
    truthy(!array_key_exists('content', $subs), 'subscriptions.list came back paginated');
    truthy(in_array($subscription['id'], array_column($subs, 'id'), true), 'created subscription missing');
});

// ── Events ──
echo "\nevents:\n";
$event = $client->events->send('order.completed', ['orderId' => 'ord_12345', 'amount' => 99.99], "php-smoke-{$suffix}");
$check('events.send returns eventId / type / createdAt / deliveriesCreated', static function () use ($event) {
    truthy(is_string($event['eventId']), 'eventId is not a string');
    eqv($event['type'], 'order.completed', 'type');
    truthy(is_string($event['createdAt']), 'createdAt is not a string');
    eqv($event['deliveriesCreated'], 1, 'deliveriesCreated');
});

// ── Deliveries ──
echo "\ndeliveries:\n";
$page = poll(
    static fn () => $client->deliveries->list($projectId, ['size' => 5]),
    static fn (array $p) => count($p['content']) > 0
);
$check('deliveries.list returns the paginated envelope with the delivery in it', static function () use ($page) {
    truthy(is_array($page['content']), 'content is not an array');
    truthy(is_int($page['totalElements']), 'totalElements is not an int');
    truthy(is_int($page['number']), 'number is not an int');
    truthy(is_int($page['size']), 'size is not an int');
    truthy(count($page['content']) > 0, 'no delivery was created for the event');
});

$delivery = $page['content'][0];
$check('Delivery carries the documented field names (nextRetryAt, not nextAttemptAt)', static function () use ($delivery, $event, $endpoint, $subscription) {
    eqv($delivery['eventId'], $event['eventId'], 'eventId');
    eqv($delivery['endpointId'], $endpoint['id'], 'endpointId');
    eqv($delivery['subscriptionId'], $subscription['id'], 'subscriptionId');
    truthy(is_int($delivery['attemptCount']), 'attemptCount is not an int');
    truthy(array_key_exists('nextRetryAt', $delivery), 'nextRetryAt missing from the delivery');
});

$one = $client->deliveries->get($delivery['id']);
$check('deliveries.get round-trips the delivery', static fn () => eqv($one['id'], $delivery['id'], 'id'));

$attempts = poll(
    static fn () => $client->deliveries->getAttempts($delivery['id']),
    static fn (array $a) => count($a) > 0
);
$check('deliveries.getAttempts returns httpStatusCode/durationMs/createdAt', static function () use ($attempts, $delivery) {
    truthy(count($attempts) > 0, 'no attempt was recorded');
    $a = $attempts[0];
    truthy(is_string($a['id']), 'id is not a string');
    eqv($a['deliveryId'], $delivery['id'], 'deliveryId');
    truthy(is_int($a['attemptNumber']), 'attemptNumber is not an int');
    truthy(array_key_exists('httpStatusCode', $a), 'httpStatusCode missing (the README used to say httpStatus)');
    truthy(array_key_exists('durationMs', $a), 'durationMs missing (the README used to say latencyMs)');
    truthy(array_key_exists('createdAt', $a), 'createdAt missing');
});

// ── Incoming ──
echo "\nincoming:\n";
$source = $client->incomingSources->create($projectId, [
    'name' => 'PHP Smoke Source',
    'slug' => substr("php-smoke-{$suffix}", 0, 60),
    'providerType' => 'GENERIC',
    'verificationMode' => 'NONE',
]);
$check('incomingSources.create returns an ingress URL and token', static function () use ($source) {
    truthy(is_string($source['ingressUrl']), 'ingressUrl is not a string');
    truthy(is_string($source['ingressPathToken']), 'ingressPathToken is not a string');
    eqv($source['status'], 'ACTIVE', 'status');
});

$sourcePage = $client->incomingSources->list($projectId);
$check('incomingSources.list returns a page envelope', static function () use ($sourcePage, $source) {
    truthy(in_array($source['id'], array_column($sourcePage['content'], 'id'), true), 'created source missing');
});

$destination = $client->incomingSources->createDestination($projectId, $source['id'], [
    'url' => 'https://example.com/php-smoke-destination',
    'enabled' => true,
]);
$check('createDestination returns the documented IncomingDestination shape', static function () use ($destination, $source) {
    eqv($destination['incomingSourceId'], $source['id'], 'incomingSourceId');
    truthy(is_int($destination['maxAttempts']), 'maxAttempts is not an int');
    eqv($destination['authType'], 'NONE', 'authType');
});

$destPage = $client->incomingSources->listDestinations($projectId, $source['id']);
$check('listDestinations returns a page envelope', static function () use ($destPage, $destination) {
    truthy(in_array($destination['id'], array_column($destPage['content'], 'id'), true), 'created destination missing');
});

// Push a webhook through the source's own ingress URL — the only way to make an
// Incoming Event exist. permitAll, no credentials (SecurityConfig.java).
raw($baseUrl, 'POST', "/ingress/{$source['ingressPathToken']}", ['hello' => 'incoming']);

$incoming = poll(
    static fn () => $client->incomingEvents->list($projectId, ['sourceId' => $source['id'], 'size' => 5]),
    static fn (array $p) => count($p['content']) > 0
);
$check('incomingEvents.list returns the received webhook', static function () use ($incoming, $source) {
    truthy(count($incoming['content']) > 0, 'the ingress POST produced no incoming event');
    eqv($incoming['content'][0]['incomingSourceId'], $source['id'], 'incomingSourceId');
    eqv($incoming['content'][0]['method'], 'POST', 'method');
});

if (count($incoming['content']) > 0) {
    $incomingId = $incoming['content'][0]['id'];

    $got = $client->incomingEvents->get($projectId, $incomingId);
    $check('incomingEvents.get round-trips the event', static fn () => eqv($got['id'], $incomingId, 'id'));

    $fwd = $client->incomingEvents->getAttempts($projectId, $incomingId);
    $check('incomingEvents.getAttempts returns a page envelope', static function () use ($fwd) {
        truthy(array_key_exists('content', $fwd), 'forward attempts are not in a page envelope');
    });

    $replayed = $client->incomingEvents->replay($projectId, $incomingId);
    $check('incomingEvents.replay returns status / eventId / destinationsCount', static function () use ($replayed, $incomingId) {
        eqv($replayed['eventId'], $incomingId, 'eventId');
        truthy(is_int($replayed['destinationsCount']), 'destinationsCount is not an int');
        truthy(is_string($replayed['status']), 'status is not a string');
    });
}

// ── Errors ──
echo "\nerrors:\n";
$badClient = new Railhook('not-a-real-key', $baseUrl);
$expectError(
    'an invalid API key throws AuthenticationException(401)',
    static fn () => $badClient->events->send('order.completed', []),
    static function (\Throwable $e) {
        truthy($e instanceof AuthenticationException, 'expected AuthenticationException, got ' . $e::class);
        eqv($e->getStatusCode(), 401, 'status');
    }
);
$expectError(
    'an unknown delivery throws NotFoundException(404)',
    static fn () => $client->deliveries->get('00000000-0000-0000-0000-000000000000'),
    static function (\Throwable $e) {
        truthy($e instanceof NotFoundException, 'expected NotFoundException, got ' . $e::class);
        eqv($e->getStatusCode(), 404, 'status');
    }
);
$expectError(
    'a malformed event type throws ValidationException(400) carrying fieldErrors',
    static fn () => $client->events->send('NOT A VALID TYPE', []),
    static function (\Throwable $e) {
        truthy($e instanceof ValidationException, 'expected ValidationException, got ' . $e::class);
        eqv($e->getStatusCode(), 400, 'status');
        truthy(!empty($e->getFieldErrors()['type']), 'fieldErrors[type] was not parsed out of the envelope');
    }
);
$expectError(
    "another project's resources throw a 403 RailhookException",
    static fn () => $client->endpoints->list('00000000-0000-0000-0000-000000000000'),
    static function (\Throwable $e) {
        truthy($e instanceof RailhookException, 'expected RailhookException, got ' . $e::class);
        eqv($e->getStatusCode(), 403, 'status');
        eqv($e->getErrorCode(), 'forbidden', 'error code (taken from the envelope\'s "error" field)');
    }
);

// ── Signature verification against a signature the SERVER produced ──
echo "\nsignature:\n";
$dryRun = $client->post("/api/v1/projects/{$projectId}/transform-preview/delivery-dry-run", [
    'payload' => json_encode(['orderId' => 'ord_12345']),
    'endpointId' => $endpoint['id'],
    'eventType' => 'order.completed',
]);
$signature = $dryRun['signature'];
// Signed over the *transformed* payload the endpoint would actually receive,
// which is pretty-printed — not over what we sent in.
$signedBody = $dryRun['transformedPayload'];

$check('the server produces X-Signature as t=<unix-ms>,v1=<hex>', static function () use ($signature) {
    truthy((bool) preg_match('/^t=\d{13},v1=[0-9a-f]{64}$/', $signature), "unexpected signature format: {$signature}");
});
$check('Webhook::verifySignature accepts the signature the server computed', static function () use ($signedBody, $signature, $rotated) {
    eqv(Webhook::verifySignature($signedBody, $signature, $rotated['secret']), true, 'verifySignature');
});
$expectError(
    'Webhook::verifySignature rejects a tampered body',
    static fn () => Webhook::verifySignature($signedBody . ' ', $signature, $rotated['secret']),
    static fn (\Throwable $e) => eqv($e instanceof RailhookException ? $e->getErrorCode() : null, 'invalid_signature', 'error code')
);
$expectError(
    'Webhook::verifySignature rejects a signature outside the 300s tolerance',
    static fn () => Webhook::verifySignature(
        $signedBody,
        Webhook::generateSignature($signedBody, $rotated['secret'], (int) (microtime(true) * 1000) - 301000),
        $rotated['secret']
    ),
    static fn (\Throwable $e) => eqv($e instanceof RailhookException ? $e->getErrorCode() : null, 'timestamp_expired', 'error code')
);

// ── Cleanup ──
$client->subscriptions->delete($projectId, $subscription['id']);
$client->endpoints->delete($projectId, $endpoint['id']);
$client->incomingSources->delete($projectId, $source['id']);

echo "\n{$passed} checks passed, " . count($failures) . " failed.\n";
if ($failures !== []) {
    fwrite(STDERR, "\nFailures:\n");
    foreach ($failures as $failure) {
        fwrite(STDERR, "  - {$failure}\n");
    }
    exit(1);
}
