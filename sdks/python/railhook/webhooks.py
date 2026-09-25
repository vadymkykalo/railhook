"""Webhook signature verification utilities."""

import base64
import hashlib
import hmac
import time
from typing import Any, Dict, Optional

from .types import WebhookEvent
from .errors import RailhookError

DEFAULT_TOLERANCE_MS = 300000

# The Standard Webhooks headers carry seconds, not milliseconds.
DEFAULT_STANDARD_TOLERANCE_SECONDS = 300


def verify_signature(
    payload: str,
    signature: str,
    secret: str,
    tolerance_ms: int = DEFAULT_TOLERANCE_MS,
) -> bool:
    """
    Verify an ``X-Signature`` header (``t=<unix-ms>,v1=<hex>[,v1=...]``); during a secret
    rotation any one ``v1`` matching is enough.

    Args:
        payload: Raw request body
        signature: X-Signature header value
        secret: Endpoint webhook secret
        tolerance_ms: Maximum age of the signature in milliseconds

    Raises:
        RailhookError: If the signature is invalid or expired
    """
    if not signature:
        raise RailhookError(
            "Missing signature header", 400, "invalid_signature"
        )

    timestamp: Optional[str] = None
    # Collected, not overwritten: during a rotation keeping only the last v1 would reject
    # whichever secret the receiver currently holds.
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

    # Digits only: a malformed header is a rejected request, not a ValueError escaping
    # to the caller's framework as a 500.
    if not (timestamp.isascii() and timestamp.isdigit()):
        raise RailhookError(
            "Invalid signature format. Expected: t=timestamp,v1=signature",
            400,
            "invalid_signature",
        )

    timestamp_ms = int(timestamp)
    now_ms = int(time.time() * 1000)

    if abs(now_ms - timestamp_ms) > tolerance_ms:
        raise RailhookError(
            "Webhook timestamp is outside tolerance window",
            400,
            "timestamp_expired",
        )

    signed_payload = f"{timestamp}.{payload}"
    expected_signature = hmac.new(
        secret.encode("utf-8"),
        signed_payload.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    # No early exit, so timing does not reveal which candidate matched. As bytes, because
    # compare_digest raises TypeError on a non-ASCII str and the header is sender-controlled.
    matched = False
    expected_bytes = expected_signature.encode("utf-8")
    for candidate in signatures:
        if hmac.compare_digest(candidate.encode("utf-8"), expected_bytes):
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
    """Verify the Standard Webhooks headers (``webhook-id``, ``-timestamp``, ``-signature``).

    During a rotation's grace window any one matching signature is enough.

    Args:
        payload: the raw request body.
        headers: the request headers.
        secret: the endpoint's ``standardWebhooksSecret`` (``whsec_…``); a raw secret is used as-is.
        tolerance_seconds: how far the timestamp may be from now, either way.

    Raises:
        RailhookError: if the signature is invalid or expired.
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

    if secret.startswith("whsec_"):
        key = base64.b64decode(secret[len("whsec_") :])
    else:
        key = secret.encode("utf-8")

    signed_content = f"{message_id}.{timestamp_seconds}.{payload}".encode("utf-8")
    expected = base64.b64encode(hmac.new(key, signed_content, hashlib.sha256).digest()).decode()

    # No early exit, so timing does not reveal which candidate matched. As bytes, because
    # compare_digest raises TypeError on a non-ASCII str.
    matched = False
    expected_bytes = expected.encode("utf-8")
    for part in str(signature).strip().split():
        version, _, candidate = part.partition(",")
        if version != "v1" or not candidate:
            continue
        if hmac.compare_digest(candidate.encode("utf-8"), expected_bytes):
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
    Verify the request and parse it into a WebhookEvent. ``type`` is set only when the body
    carries a ``type`` key; ids come from the headers.

    Args:
        payload: Raw request body
        headers: Request headers, any case
        secret: Endpoint webhook secret
        tolerance_ms: Maximum age of the signature in milliseconds

    Raises:
        RailhookError: If the signature is invalid or the payload is malformed
    """
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
    """Build an ``X-Signature`` value (``t=<ms>,v1=<hex>``) for tests; the timestamp defaults to now."""
    ts = timestamp_ms or int(time.time() * 1000)
    signed_payload = f"{ts}.{payload}"
    signature = hmac.new(
        secret.encode("utf-8"),
        signed_payload.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    return f"t={ts},v1={signature}"
