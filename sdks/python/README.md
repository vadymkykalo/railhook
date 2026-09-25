# railhook

Python SDK for [Railhook](https://github.com/vadymkykalo/railhook).

```bash
pip install railhook
```

Before 2.12.0 this package was `webhook-platform`, imported as `hookflow`. That package gets no
more updates.

## Send an event

```python
import os

from railhook import Railhook, Event

client = Railhook(
    api_key=os.environ["RAILHOOK_API_KEY"],
    base_url="https://railhook.io",  # default http://localhost:8080
)

event = client.events.send(
    Event(type="order.completed", data={"order_id": "ord_123", "amount": 99.99}),
    idempotency_key="order-123-completed",  # optional
)
print(event.event_id, event.deliveries_created)
```

## Verify a webhook

The signature covers the raw body, so verify the bytes as received.

```python
import os

from flask import Flask, request
from railhook import construct_event, RailhookError

app = Flask(__name__)

@app.route("/webhooks", methods=["POST"])
def webhook():
    try:
        event = construct_event(
            request.get_data(as_text=True), dict(request.headers), os.environ["WEBHOOK_SECRET"]
        )
    except RailhookError:
        return "Invalid signature", 400
    print(event.event_id, event.data)
    return "OK", 200
```

`verify_standard_webhook` checks the `webhook-*` headers instead. During a secret rotation
either secret's signature is accepted.

The client also covers endpoints, subscriptions, deliveries, consumers and portal sessions,
and incoming sources and events. It does not retry: one call is one HTTP request.

Full docs: https://railhook.io/docs/tools/sdks/

## Develop

```bash
pip install -e ".[dev]"
pytest
python scripts/live_api_smoke.py   # against a running stack (make up)
```

## License

MIT
