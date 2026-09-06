"""Webhook signature verification utilities."""

import base64
import hashlib
import hmac
import time
from typing import Any, Dict, Optional

from .types import WebhookEvent
from .errors import RailhookError

DEFAULT_TOLERANCE_MS = 300000  # 5 minutes

# The Standard Webhooks headers carry seconds, not milliseconds.
DEFAULT_STANDARD_TOLERANCE_SECONDS = 300


def verify_signature(
    payload: str,
    signature: str,
    secret: str,
    tolerance_ms: int = DEFAULT_TOLERANCE_MS,
) -> bool:
    """
    Verify webhook signature using HMAC-SHA256.

    The header is ``t=<unix-ms>,v1=<hex>`` and may carry more than one ``v1``.
    After you rotate an endpoint's secret, Railhook signs each delivery with both
    the new secret and the retired one for the endpoint's grace window (24 hours
    by default), so the new secret can be deployed whenever you like rather than
    at the instant you press rotate. The delivery is authentic if *any* ``v1``
    matches.

    Args:
        payload: Raw request body as string
        signature: X-Signature header value (format: t=timestamp,v1=signature[,v1=...])
        secret: Endpoint webhook secret
        tolerance_ms: Maximum age of signature in milliseconds

    Returns:
        True if signature is valid

    Raises:
        RailhookError: If signature is invalid or expired
    """
    if not signature:
        raise RailhookError(
            "Missing signature header", 400, "invalid_signature"
        )

    # Parse signature
    timestamp: Optional[str] = None
    # Collected, not overwritten: a header sent during a secret rotation carries one
    # v1 per valid secret, and keeping only the last would reject whichever of the
    # pair the receiver is currently holding.
    signatures: list[str] = []

    for part in signature.split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            if key == "t":
                timestamp = value.strip()
            elif key == "v1":
                signatures.append(value.strip())

    if not timestamp or not signatures:
        raise RailhookError(
            "Invalid signature format. Expected: t=timestamp,v1=signature",
            400,
            "invalid_signature",
        )

    # Check timestamp
    timestamp_ms = int(timestamp)
    now_ms = int(time.time() * 1000)

    if abs(now_ms - timestamp_ms) > tolerance_ms:
        raise RailhookError(
            "Webhook timestamp is outside tolerance window",
            400,
            "timestamp_expired",
        )

    # Verify signature
    signed_payload = f"{timestamp}.{payload}"
    expected_signature = hmac.new(
        secret.encode("utf-8"),
        signed_payload.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    # Every candidate is compared, with no early exit, so the time taken does not
    # depend on which position matched.
    matched = False
    for candidate in signatures:
        if hmac.compare_digest(candidate, expected_signature):
            matched = True

    if not matched:
        raise RailhookError("Invalid signature", 400, "invalid_signature")

    return True


def verify_standard_webhook(
    payload: str,
    headers: Dict[str, Any],
    secret: str,
    tolerance_seconds: int = DEFAULT_STANDARD_TOLERANCE_SECONDS,
) -> bool:
    """Verify the `Standard Webhooks <https://www.standardwebhooks.com>`_ headers.

    Endpoints receive both header sets by default (``signatureScheme: "BOTH"``), so use
    whichever suits you — this one if you would rather verify the same way as other
    providers you integrate with, :func:`verify_signature` if you already verify
    ``X-Signature``.

    Two things differ from Railhook's own scheme beyond the header names: the message id is
    part of what is signed, and the digest is base64 rather than hex. Rotation behaves the
    same — through the grace window the header carries a space-separated signature per valid
    secret, and any one matching is enough.

    Args:
        payload: the raw request body.
        headers: the request headers, including the three ``webhook-*`` ones.
        secret: the endpoint's ``standardWebhooksSecret`` (``whsec_…``). A raw secret is
            accepted too and used as-is.
        tolerance_seconds: how far the timestamp may be from now, either way.

    Returns:
        True if the signature is valid.

    Raises:
        RailhookError: if it is not.
    """
    lowered = {str(k).lower(): v for k, v in headers.items()}
    message_id = lowered.get("webhook-id")
    timestamp = lowered.get("webhook-timestamp")
    signature = lowered.get("webhook-signature")

    if not message_id or not timestamp or not signature:
        raise RailhookError(
            "Missing webhook-id, webhook-timestamp or webhook-signature header",
            400,
            "invalid_signature",
        )

    try:
        timestamp_seconds = int(str(timestamp).strip())
    except ValueError:
        raise RailhookError("Invalid webhook-timestamp header", 400, "invalid_signature")

    if abs(int(time.time()) - timestamp_seconds) > tolerance_seconds:
        raise RailhookError(
            "Webhook timestamp is outside tolerance window", 400, "timestamp_expired"
        )

    # ``whsec_<base64>`` is the conventional form, and is what the endpoint's
    # standardWebhooksSecret gives you: the base64 body decodes to the key bytes. Anything
    # else is taken literally, so a raw secret still works.
    if secret.startswith("whsec_"):
        key = base64.b64decode(secret[len("whsec_") :])
    else:
        key = secret.encode("utf-8")

    signed_content = f"{message_id}.{timestamp_seconds}.{payload}".encode("utf-8")
    expected = base64.b64encode(hmac.new(key, signed_content, hashlib.sha256).digest()).decode()

    # Space-separated, one per valid secret during a rotation window. Every candidate is
    # compared with no early exit, so the time taken does not reveal which one matched.
    matched = False
    for part in str(signature).strip().split():
        version, _, candidate = part.partition(",")
        if version != "v1" or not candidate:
            continue
        if hmac.compare_digest(candidate, expected):
            matched = True

    if not matched:
        raise RailhookError("Invalid signature", 400, "invalid_signature")

    return True


def construct_event(
    payload: str,
    headers: Dict[str, str],
    secret: str,
    tolerance_ms: int = DEFAULT_TOLERANCE_MS,
) -> WebhookEvent:
    """
    Construct a webhook event from request, verifying signature.

    What Railhook actually PUTs on the wire is the event's **payload**, not an
    envelope: a ``client.events.send(Event(type="order.completed", data={...}))``
    arrives at your endpoint as the ``data`` object alone, with the identifiers
    carried in headers (``X-Event-Id``, ``X-Delivery-Id``, ``X-Timestamp``,
    ``X-Sequence-Number``). So ``event_id`` / ``delivery_id`` / ``timestamp``
    are always populated for a real delivery and ``data`` is the parsed body,
    but ``type`` is only populated when the body itself carries a ``type`` key
    — which for a default subscription it does not. Route on the payload, or
    configure the subscription's ``payload_template`` to wrap the event so that
    ``type`` becomes part of the body.

    Args:
        payload: Raw request body as string
        headers: Request headers (case-insensitive dict)
        secret: Endpoint webhook secret
        tolerance_ms: Maximum age of signature in milliseconds

    Returns:
        Parsed and verified WebhookEvent

    Raises:
        RailhookError: If signature is invalid or payload is malformed
    """
    # Get headers (case-insensitive)
    headers_lower = {k.lower(): v for k, v in headers.items()}

    signature = headers_lower.get("x-signature", "")
    timestamp = headers_lower.get("x-timestamp", "")
    event_id = headers_lower.get("x-event-id", "")
    delivery_id = headers_lower.get("x-delivery-id", "")

    if not signature:
        raise RailhookError(
            "Missing X-Signature header", 400, "missing_header"
        )

    verify_signature(payload, signature, secret, tolerance_ms)

    # Parse payload
    import json

    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        raise RailhookError("Invalid JSON payload", 400, "invalid_payload")

    return WebhookEvent(
        event_id=event_id,
        delivery_id=delivery_id,
        timestamp=int(timestamp) if timestamp else int(time.time() * 1000),
        type=data.get("type", ""),
        data=data.get("data", data),
    )


def generate_signature(
    payload: str,
    secret: str,
    timestamp_ms: Optional[int] = None,
) -> str:
    """
    Generate a signature for testing purposes.

    Args:
        payload: Request body as string
        secret: Webhook secret
        timestamp_ms: Optional timestamp in milliseconds (defaults to now)

    Returns:
        Signature string in format t=timestamp,v1=signature
    """
    ts = timestamp_ms or int(time.time() * 1000)
    signed_payload = f"{ts}.{payload}"
    signature = hmac.new(
        secret.encode("utf-8"),
        signed_payload.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    return f"t={ts},v1={signature}"
