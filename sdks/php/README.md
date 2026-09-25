# railhook/php

PHP SDK for [Railhook](https://github.com/vadymkykalo/railhook). Needs PHP 8.1+, ext-json and
ext-curl.

```bash
composer require railhook/php
```

Before 2.12.0 this package was `webhook-platform/php` with the namespace `Hookflow\`. That
package is marked abandoned.

## Send an event

```php
<?php

use Railhook\Railhook;

$client = new Railhook(
    apiKey: getenv('RAILHOOK_API_KEY'),
    baseUrl: 'https://railhook.io', // default http://localhost:8080
);

$event = $client->events->send(
    type: 'order.completed',
    data: ['orderId' => 'ord_123', 'amount' => 99.99],
    idempotencyKey: 'order-123-completed', // optional
);
echo $event['eventId'], ' ', $event['deliveriesCreated'], "\n";
```

## Verify a webhook

The signature covers the raw body, so verify the bytes as received.

```php
<?php

use Railhook\Webhook;
use Railhook\Exception\RailhookException;

try {
    $event = Webhook::constructEvent(
        file_get_contents('php://input'),
        getallheaders(),
        getenv('WEBHOOK_SECRET'),
    );
    error_log("{$event['eventId']}: " . json_encode($event['data']));
    http_response_code(200);
} catch (RailhookException $e) {
    http_response_code(400);
}
```

`Webhook::verifyStandardWebhook` checks the `webhook-*` headers instead. During a secret
rotation either secret's signature is accepted.

The client also covers endpoints, subscriptions, deliveries, consumers and portal sessions,
and incoming sources and events. It does not retry: one call is one HTTP request.

Full docs: https://railhook.io/docs/tools/sdks/

## Develop

```bash
composer install
composer test
php scripts/live-api-smoke.php   # against a running stack (make up)
```

## License

MIT
