# @railhook/node

Node.js SDK for [Railhook](https://github.com/vadymkykalo/railhook). No runtime dependencies.

```bash
npm install @railhook/node
```

## Send an event

```typescript
import { Railhook } from '@railhook/node';

const client = new Railhook({
  apiKey: process.env.RAILHOOK_API_KEY!,
  baseUrl: 'https://railhook.io', // default http://localhost:8080
});

const event = await client.events.send(
  { type: 'order.completed', data: { orderId: 'ord_123', amount: 99.99 } },
  'order-123-completed', // optional idempotency key
);
console.log(event.eventId, event.deliveriesCreated);
```

## Verify a webhook

The signature covers the raw body, so read it before any JSON parser does.

```typescript
import express from 'express';
import { constructEvent } from '@railhook/node';

const app = express();

app.post('/webhooks', express.raw({ type: 'application/json' }), (req, res) => {
  try {
    const event = constructEvent(req.body.toString(), req.headers, process.env.WEBHOOK_SECRET!);
    console.log(event.eventId, event.data);
    res.sendStatus(200);
  } catch {
    res.sendStatus(400);
  }
});
```

`verifyStandardWebhook` checks the `webhook-*` headers instead. During a secret rotation
either secret's signature is accepted.

The client also covers endpoints, subscriptions, deliveries, consumers and portal sessions,
and incoming sources and events. It does not retry: one call is one HTTP request.

Full docs: https://railhook.io/docs/tools/sdks/

## Develop

```bash
npm install
npm test
npm run build
npm run smoke:live   # against a running stack (make up)
```

## License

MIT
