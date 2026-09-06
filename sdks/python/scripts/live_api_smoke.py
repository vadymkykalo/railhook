#!/usr/bin/env python3
"""Live-API smoke check for the Python SDK.

NOT a unit test. ``pytest.ini`` sets ``testpaths = tests`` and
``python_files = test_*.py``; this file is neither under ``tests/`` nor named
``test_*``, so ``pytest`` never collects it — the unit suite must stay green
with no backend running, and this file must never be the reason it isn't.

What it does: registers a throwaway org against a REAL running API, then
drives the whole send-and-inspect workflow through the SDK's own public
methods and asserts what actually comes back — status codes, field names,
pagination envelope, error envelope, and a signature the server itself
produced. Stubbed-transport unit tests are structurally unable to catch a
renamed field; this is what catches it.

Usage::

    make up                        # from the repo root
    cd sdks/python && python scripts/live_api_smoke.py

Env:
    SMOKE_API_BASE_URL   target API (default http://localhost:8080)

Exit code is 0 only if every check passed.
"""

from __future__ import annotations

import json
import os
import random
import sys
import time
from pathlib import Path
from typing import Any, Callable, Dict, Optional

# Run from a source checkout without installing the package first.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import requests  # noqa: E402

from railhook import (  # noqa: E402
    AuthenticationError,
    EndpointCreateParams,
    Event,
    Railhook,
    RailhookError,
    IncomingDestinationCreateParams,
    IncomingEventListParams,
    IncomingSourceCreateParams,
    NotFoundError,
    SubscriptionCreateParams,
    ValidationError,
    generate_signature,
    verify_signature,
)

BASE_URL = os.environ.get("SMOKE_API_BASE_URL", "http://localhost:8080")
PASSWORD = "SmokeCheck!2026x"  # meets AuthController's complexity policy

PASSED = 0
FAILURES: list[str] = []


def check(label: str, fn: Callable[[], Any]) -> None:
    global PASSED
    try:
        fn()
    except Exception as exc:  # noqa: BLE001 - a failed check is data, not a crash
        FAILURES.append(f"{label}: {exc}")
        print(f"  FAIL {label}\n         {exc}")
    else:
        PASSED += 1
        print(f"  ok   {label}")


def expect_error(label: str, fn: Callable[[], Any], assert_on: Callable[[Exception], None]) -> None:
    global PASSED
    try:
        fn()
    except Exception as exc:  # noqa: BLE001
        try:
            assert_on(exc)
        except Exception as assert_exc:  # noqa: BLE001
            FAILURES.append(f"{label}: {assert_exc}")
            print(f"  FAIL {label}\n         {assert_exc}")
        else:
            PASSED += 1
            print(f"  ok   {label}")
    else:
        FAILURES.append(f"{label}: the call returned instead of raising")
        print(f"  FAIL {label}\n         the call returned instead of raising")


def eq(actual: Any, expected: Any, what: str) -> None:
    assert actual == expected, f"{what}: expected {expected!r}, got {actual!r}"


def raw(method: str, path: str, body: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None) -> Any:
    """Raw HTTP, used ONLY to bootstrap a tenant.

    The SDK is API-key scoped by design — it has no register/login/create-project
    surface (see ``railhook/client.py``) — so these three calls cannot go through
    it. Everything after this point does.
    """
    res = requests.request(method, f"{BASE_URL}{path}", json=body, headers=headers, timeout=15)
    if res.status_code >= 400:
        raise RuntimeError(f"{method} {path} -> HTTP {res.status_code} {res.text}")
    return res.json() if res.text else None


def api_is_up() -> bool:
    try:
        # An intentionally invalid login: any HTTP response at all proves the API
        # is answering. Deliberately NOT /v3/api-docs — springdoc is only exposed
        # when SWAGGER_ENABLED=true (SecurityConfig.java), and it is false by
        # default, so probing it reports a healthy stack as unreachable.
        requests.post(f"{BASE_URL}/api/v1/auth/login", json={}, timeout=5)
        return True
    except requests.RequestException:
        return False


def poll(fn: Callable[[], Any], ready: Callable[[Any], bool], attempts: int = 20, delay: float = 0.5) -> Any:
    value = fn()
    for _ in range(attempts - 1):
        if ready(value):
            return value
        time.sleep(delay)
        value = fn()
    return value


def main() -> int:
    print(f"Railhook Python SDK — live API smoke check against {BASE_URL}\n")

    if not api_is_up():
        print(f"{BASE_URL} is not answering. Start the stack with `make up` from the repo root.", file=sys.stderr)
        return 2

    # ── Bootstrap (raw HTTP: register / project / API key) ──
    suffix = f"{int(time.time() * 1000)}-{random.randint(0, 999999)}"
    auth = raw(
        "POST",
        "/api/v1/auth/register",
        {
            "email": f"py-smoke-{suffix}@py-smoke.invalid",
            "password": PASSWORD,
            "fullName": "Python Smoke Check",
            "organizationName": f"py-smoke-{suffix}"[:100],
        },
    )

    print("register:")
    check("register returns an accessToken", lambda: eq(isinstance(auth["accessToken"], str), True, "typeof accessToken"))
    check(
        "register returns refreshToken: None (nothing may assume it is present)",
        lambda: eq(auth["refreshToken"], None, "refreshToken"),
    )
    check("register reports emailVerified", lambda: eq(isinstance(auth["emailVerified"], bool), True, "typeof emailVerified"))

    bearer = {"Authorization": f"Bearer {auth['accessToken']}"}
    project = raw("POST", "/api/v1/projects", {"name": f"py-smoke-{suffix}"[:100]}, bearer)
    # name has a 2-char minimum (ApiKeyRequest); a one-letter name is a 400.
    api_key = raw(
        "POST",
        f"/api/v1/projects/{project['id']}/api-keys",
        {"name": f"py-smoke-key-{suffix}"[:100], "scope": "READ_WRITE"},
        bearer,
    )

    project_id = project["id"]
    client = Railhook(api_key=api_key["key"], base_url=BASE_URL)

    # ── Endpoints ──
    print("\nendpoints:")
    endpoint = client.endpoints.create(
        project_id,
        EndpointCreateParams(url="https://example.com/py-smoke", description="python live smoke check"),
    )
    check(
        "endpoints.create returns the declared Endpoint shape",
        lambda: (
            eq(isinstance(endpoint.id, str), True, "typeof id"),
            eq(endpoint.project_id, project_id, "project_id"),
            eq(endpoint.url, "https://example.com/py-smoke", "url"),
            eq(endpoint.enabled, True, "enabled"),
            eq(isinstance(endpoint.created_at, str), True, "typeof created_at"),
            eq(bool(endpoint.secret), True, "secret is non-empty"),
        ),
    )

    endpoint_page = client.endpoints.list(project_id)
    check(
        "endpoints.list returns a page envelope, not a bare list",
        lambda: (
            eq(isinstance(endpoint_page.content, list), True, "page.content is a list"),
            eq(isinstance(endpoint_page.total_elements, int), True, "typeof total_elements"),
            eq(any(e.id == endpoint.id for e in endpoint_page), True, "created endpoint present in the page"),
        ),
    )

    fetched = client.endpoints.get(project_id, endpoint.id)
    check("endpoints.get round-trips the endpoint", lambda: eq(fetched.id, endpoint.id, "id"))

    test_result = client.endpoints.test(project_id, endpoint.id)
    check(
        "endpoints.test parses httpStatusCode/latencyMs (not httpStatus)",
        lambda: (
            eq(isinstance(test_result.success, bool), True, "typeof success"),
            eq(isinstance(test_result.latency_ms, int), True, "typeof latency_ms"),
            eq(test_result.http_status_code is not None, True, "http_status_code was parsed out of the response"),
        ),
    )

    rotated = client.endpoints.rotate_secret(project_id, endpoint.id)
    check(
        "endpoints.rotate_secret returns a different secret",
        lambda: (
            eq(bool(rotated.secret), True, "secret is non-empty"),
            eq(rotated.secret != endpoint.secret, True, "secret changed"),
        ),
    )

    # ── Subscriptions ──
    print("\nsubscriptions:")
    subscription = client.subscriptions.create(
        project_id,
        SubscriptionCreateParams(endpoint_id=endpoint.id, event_type="order.completed"),
    )
    check(
        "subscriptions.create returns the declared Subscription shape",
        lambda: (
            eq(subscription.endpoint_id, endpoint.id, "endpoint_id"),
            eq(subscription.event_type, "order.completed", "event_type"),
            eq(isinstance(subscription.max_attempts, int), True, "typeof max_attempts"),
            eq(isinstance(subscription.timeout_seconds, int), True, "typeof timeout_seconds"),
        ),
    )

    subs = client.subscriptions.list(project_id)
    check(
        "subscriptions.list returns a bare list (it is NOT paginated)",
        lambda: (
            eq(isinstance(subs, list), True, "is a list"),
            eq(any(s.id == subscription.id for s in subs), True, "created subscription present"),
        ),
    )

    # ── Events ──
    print("\nevents:")
    event = client.events.send(
        Event(type="order.completed", data={"orderId": "ord_12345", "amount": 99.99}),
        idempotency_key=f"py-smoke-{suffix}",
    )
    check(
        "events.send returns event_id / type / created_at / deliveries_created",
        lambda: (
            eq(isinstance(event.event_id, str), True, "typeof event_id"),
            eq(event.type, "order.completed", "type"),
            eq(isinstance(event.created_at, str), True, "typeof created_at"),
            eq(event.deliveries_created, 1, "deliveries_created"),
        ),
    )

    # ── Deliveries ──
    print("\ndeliveries:")
    page = poll(lambda: client.deliveries.list(project_id), lambda p: len(p.content) > 0)
    check(
        "deliveries.list returns the paginated envelope with the delivery in it",
        lambda: (
            eq(isinstance(page.content, list), True, "page.content is a list"),
            eq(isinstance(page.total_elements, int), True, "typeof total_elements"),
            eq(isinstance(page.number, int), True, "typeof number"),
            eq(len(page.content) > 0, True, "a delivery was created for the event"),
        ),
    )

    delivery = page.content[0]
    check(
        "Delivery parses next_retry_at (the API field is nextRetryAt, not nextAttemptAt)",
        lambda: (
            eq(delivery.event_id, event.event_id, "event_id"),
            eq(delivery.endpoint_id, endpoint.id, "endpoint_id"),
            eq(delivery.subscription_id, subscription.id, "subscription_id"),
            eq(hasattr(delivery, "next_retry_at"), True, "next_retry_at exists"),
        ),
    )

    one = client.deliveries.get(delivery.id)
    check("deliveries.get round-trips the delivery", lambda: eq(one.id, delivery.id, "id"))

    attempts = poll(lambda: client.deliveries.get_attempts(delivery.id), lambda a: len(a) > 0)
    check(
        "deliveries.get_attempts parses duration_ms/created_at/http_status_code",
        lambda: (
            eq(len(attempts) > 0, True, "an attempt was recorded"),
            eq(attempts[0].delivery_id, delivery.id, "delivery_id"),
            eq(isinstance(attempts[0].attempt_number, int), True, "typeof attempt_number"),
            eq(attempts[0].created_at is not None, True, "created_at (was attemptedAt: a KeyError)"),
            eq(attempts[0].duration_ms is not None, True, "duration_ms (was latencyMs: a KeyError)"),
            eq(attempts[0].http_status_code is not None, True, "http_status_code (was httpStatus: always None)"),
        ),
    )

    # ── Incoming ──
    print("\nincoming:")
    source = client.incoming_sources.create(
        project_id,
        IncomingSourceCreateParams(
            name="Python Smoke Source",
            slug=f"py-smoke-{suffix}"[:60],
            provider_type="GENERIC",
            verification_mode="NONE",
        ),
    )
    check(
        "incoming_sources.create returns an ingress URL and token",
        lambda: (
            eq(isinstance(source.ingress_url, str), True, "typeof ingress_url"),
            eq(isinstance(source.ingress_path_token, str), True, "typeof ingress_path_token"),
            eq(source.status, "ACTIVE", "status"),
        ),
    )

    source_page = client.incoming_sources.list(project_id)
    check(
        "incoming_sources.list returns a page envelope",
        lambda: eq(any(s.id == source.id for s in source_page.content), True, "created source present"),
    )

    destination = client.incoming_sources.create_destination(
        project_id,
        source.id,
        IncomingDestinationCreateParams(url="https://example.com/py-smoke-destination", enabled=True),
    )
    check(
        "create_destination returns the declared IncomingDestination shape",
        lambda: (
            eq(destination.incoming_source_id, source.id, "incoming_source_id"),
            eq(isinstance(destination.max_attempts, int), True, "typeof max_attempts"),
            eq(destination.auth_type, "NONE", "auth_type"),
        ),
    )

    dest_page = client.incoming_sources.list_destinations(project_id, source.id)
    check(
        "list_destinations returns a page envelope",
        lambda: eq(any(d.id == destination.id for d in dest_page.content), True, "created destination present"),
    )

    # Push a webhook through the source's own ingress URL — the only way to make
    # an Incoming Event exist. permitAll, no credentials (SecurityConfig.java).
    raw("POST", f"/ingress/{source.ingress_path_token}", {"hello": "incoming"})

    incoming = poll(
        lambda: client.incoming_events.list(project_id, IncomingEventListParams(source_id=source.id)),
        lambda p: len(p.content) > 0,
    )
    check(
        "incoming_events.list returns the received webhook",
        lambda: (
            eq(len(incoming.content) > 0, True, "the ingress POST produced an incoming event"),
            eq(incoming.content[0].incoming_source_id, source.id, "incoming_source_id"),
            eq(incoming.content[0].method, "POST", "method"),
        ),
    )

    if incoming.content:
        incoming_id = incoming.content[0].id
        got = client.incoming_events.get(project_id, incoming_id)
        check("incoming_events.get round-trips the event", lambda: eq(got.id, incoming_id, "id"))

        fwd = client.incoming_events.get_attempts(project_id, incoming_id)
        check(
            "incoming_events.get_attempts unwraps the page envelope into a list",
            lambda: eq(isinstance(fwd, list), True, "is a list"),
        )

        replayed = client.incoming_events.replay(project_id, incoming_id)
        check(
            "incoming_events.replay returns status / event_id / destinations_count",
            lambda: (
                eq(replayed.event_id, incoming_id, "event_id"),
                eq(isinstance(replayed.destinations_count, int), True, "typeof destinations_count"),
                eq(isinstance(replayed.status, str), True, "typeof status"),
            ),
        )

    # ── Errors ──
    print("\nerrors:")
    bad_client = Railhook(api_key="not-a-real-key", base_url=BASE_URL)

    def _auth(exc: Exception) -> None:
        assert isinstance(exc, AuthenticationError), f"expected AuthenticationError, got {type(exc).__name__}"
        eq(exc.status, 401, "status")

    expect_error(
        "an invalid API key raises AuthenticationError(401)",
        lambda: bad_client.events.send(Event(type="order.completed", data={})),
        _auth,
    )

    def _notfound(exc: Exception) -> None:
        assert isinstance(exc, NotFoundError), f"expected NotFoundError, got {type(exc).__name__}"
        eq(exc.status, 404, "status")

    expect_error(
        "an unknown delivery raises NotFoundError(404)",
        lambda: client.deliveries.get("00000000-0000-0000-0000-000000000000"),
        _notfound,
    )

    def _validation(exc: Exception) -> None:
        assert isinstance(exc, ValidationError), f"expected ValidationError, got {type(exc).__name__}"
        eq(exc.status, 400, "status")
        assert exc.field_errors.get("type"), "field_errors['type'] was not parsed out of the envelope"

    expect_error(
        "a malformed event type raises ValidationError(400) carrying field_errors",
        lambda: client.events.send(Event(type="NOT A VALID TYPE", data={})),
        _validation,
    )

    def _forbidden(exc: Exception) -> None:
        assert isinstance(exc, RailhookError), f"expected RailhookError, got {type(exc).__name__}"
        eq(exc.status, 403, "status")
        eq(exc.code, "forbidden", 'code (taken from the envelope\'s "error" field)')

    expect_error(
        "another project's resources raise a 403 RailhookError",
        lambda: client.endpoints.list("00000000-0000-0000-0000-000000000000"),
        _forbidden,
    )

    # ── Signature verification against a signature the SERVER produced ──
    print("\nsignature:")
    dry_run = client.post(
        f"/api/v1/projects/{project_id}/transform-preview/delivery-dry-run",
        {
            "payload": json.dumps({"orderId": "ord_12345"}),
            "endpointId": endpoint.id,
            "eventType": "order.completed",
        },
    )
    signature = dry_run["signature"]
    # Signed over the *transformed* payload the endpoint would actually receive,
    # which is pretty-printed — not over what we sent in.
    body = dry_run["transformedPayload"]

    import re

    check(
        "the server produces X-Signature as t=<unix-ms>,v1=<hex>",
        lambda: eq(bool(re.fullmatch(r"t=\d{13},v1=[0-9a-f]{64}", signature)), True, f"format of {signature}"),
    )
    check(
        "verify_signature accepts the signature the server computed",
        lambda: eq(verify_signature(body, signature, rotated.secret), True, "verify_signature"),
    )
    expect_error(
        "verify_signature rejects a tampered body",
        lambda: verify_signature(body + " ", signature, rotated.secret),
        lambda exc: eq(getattr(exc, "code", None), "invalid_signature", "code"),
    )
    expect_error(
        "verify_signature rejects a signature outside the 300s tolerance",
        lambda: verify_signature(
            body, generate_signature(body, rotated.secret, int(time.time() * 1000) - 301_000), rotated.secret
        ),
        lambda exc: eq(getattr(exc, "code", None), "timestamp_expired", "code"),
    )

    # ── Cleanup ──
    client.subscriptions.delete(project_id, subscription.id)
    client.endpoints.delete(project_id, endpoint.id)
    client.incoming_sources.delete(project_id, source.id)

    print(f"\n{PASSED} checks passed, {len(FAILURES)} failed.")
    if FAILURES:
        print("\nFailures:", file=sys.stderr)
        for failure in FAILURES:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"\nsmoke check aborted: {exc}", file=sys.stderr)
        raise
