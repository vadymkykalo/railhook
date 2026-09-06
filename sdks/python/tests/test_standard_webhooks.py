"""The point of this scheme is that a receiver can verify with a library they already have,
so these tests reproduce the reference algorithm rather than round-tripping against our own
implementation — a round-trip would only prove we agree with our own bug."""

import base64
import hashlib
import hmac
import time

import pytest

from railhook import verify_standard_webhook
from railhook.errors import RailhookError

MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek"
PAYLOAD = '{"test": 2432232314}'
SECRET_B64 = "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
SHARED_SECRET = f"whsec_{SECRET_B64}"


def sign(ts: int, secret_b64: str = SECRET_B64, message_id: str = MESSAGE_ID, payload: str = PAYLOAD) -> str:
    """Exactly what the reference libraries do."""
    key = base64.b64decode(secret_b64)
    to_sign = f"{message_id}.{ts}.{payload}".encode()
    return base64.b64encode(hmac.new(key, to_sign, hashlib.sha256).digest()).decode()


def headers(ts: int, signature: str) -> dict:
    return {
        "webhook-id": MESSAGE_ID,
        "webhook-timestamp": str(ts),
        "webhook-signature": signature,
    }


def test_accepts_a_reference_signature():
    ts = int(time.time())
    assert verify_standard_webhook(PAYLOAD, headers(ts, f"v1,{sign(ts)}"), SHARED_SECRET)


def test_header_names_are_case_insensitive():
    # HTTP header names are case-insensitive, and frameworks disagree about how they
    # present them — a receiver should not have to care which one they are using.
    ts = int(time.time())
    upper = {
        "Webhook-Id": MESSAGE_ID,
        "Webhook-Timestamp": str(ts),
        "Webhook-Signature": f"v1,{sign(ts)}",
    }
    assert verify_standard_webhook(PAYLOAD, upper, SHARED_SECRET)


def test_either_secret_verifies_during_a_rotation():
    ts = int(time.time())
    retired = "b2xkLXNlY3JldC1ieXRlcy1oZXJlLXBhZGRpbmc="
    header = f"v1,{sign(ts)} v1,{sign(ts, retired)}"

    assert verify_standard_webhook(PAYLOAD, headers(ts, header), SHARED_SECRET)
    assert verify_standard_webhook(PAYLOAD, headers(ts, header), f"whsec_{retired}")


def test_rejects_a_replay_despite_a_valid_signature():
    # A signature over a fixed body never expires on its own, so without the timestamp
    # check a captured request stays replayable for as long as the secret lives.
    old = int(time.time()) - 3600
    with pytest.raises(RailhookError):
        verify_standard_webhook(PAYLOAD, headers(old, f"v1,{sign(old)}"), SHARED_SECRET)


def test_rejects_a_signature_from_another_message():
    ts = int(time.time())
    other = sign(ts, message_id="msg_somethingelse")
    with pytest.raises(RailhookError):
        verify_standard_webhook(PAYLOAD, headers(ts, f"v1,{other}"), SHARED_SECRET)


def test_rejects_a_tampered_body():
    ts = int(time.time())
    with pytest.raises(RailhookError):
        verify_standard_webhook('{"test": 1}', headers(ts, f"v1,{sign(ts)}"), SHARED_SECRET)


def test_missing_headers_are_reported_not_treated_as_unsigned():
    with pytest.raises(RailhookError):
        verify_standard_webhook(PAYLOAD, {"webhook-id": MESSAGE_ID}, SHARED_SECRET)


def test_unknown_signature_version_is_ignored():
    ts = int(time.time())
    with pytest.raises(RailhookError):
        verify_standard_webhook(PAYLOAD, headers(ts, f"v2,{sign(ts)}"), SHARED_SECRET)
