import json
import time
import pytest

from railhook import (
    verify_signature,
    construct_event,
    generate_signature,
    RailhookError,
)


class TestGenerateSignature:
    def test_generates_valid_format(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        
        signature = generate_signature(payload, secret)
        
        assert signature.startswith("t=")
        assert ",v1=" in signature
        parts = signature.split(",")
        assert len(parts) == 2

    def test_uses_provided_timestamp(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        signature = generate_signature(payload, secret, timestamp)
        
        assert f"t={timestamp}" in signature

    def test_consistent_signatures(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        sig1 = generate_signature(payload, secret, timestamp)
        sig2 = generate_signature(payload, secret, timestamp)
        
        assert sig1 == sig2

    def test_different_payloads_different_signatures(self):
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        sig1 = generate_signature('{"a": 1}', secret, timestamp)
        sig2 = generate_signature('{"b": 2}', secret, timestamp)
        
        assert sig1 != sig2

    def test_different_secrets_different_signatures(self):
        payload = '{"type": "test"}'
        timestamp = 1700000000000
        
        sig1 = generate_signature(payload, "secret1", timestamp)
        sig2 = generate_signature(payload, "secret2", timestamp)
        
        assert sig1 != sig2


class TestVerifySignature:
    def test_verifies_valid_signature(self):
        payload = '{"type": "order.completed", "data": {"id": "123"}}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        assert verify_signature(payload, signature, secret) is True

    def test_verifies_either_signature_during_a_secret_rotation(self):
        """The parser used to keep only the last v1 and reject whichever secret the receiver held."""
        payload = '{"type": "order.completed"}'
        new_secret, retired_secret = "whsec_new", "whsec_retired"
        timestamp = int(time.time() * 1000)
        header = "{},v1={}".format(
            generate_signature(payload, new_secret, timestamp),
            generate_signature(payload, retired_secret, timestamp).split("v1=")[1],
        )

        assert verify_signature(payload, header, new_secret) is True
        assert verify_signature(payload, header, retired_secret) is True

    def test_two_signatures_still_reject_an_unrelated_secret(self):
        """Accepting any v1 must not become accepting anything."""
        payload = '{"type": "order.completed"}'
        timestamp = int(time.time() * 1000)
        header = "{},v1={}".format(
            generate_signature(payload, "whsec_new", timestamp),
            generate_signature(payload, "whsec_retired", timestamp).split("v1=")[1],
        )

        with pytest.raises(RailhookError):
            verify_signature(payload, header, "whsec_someone_else")

    def test_two_signatures_still_reject_a_tampered_body(self):
        """Two signatures must not weaken body integrity."""
        payload = '{"type": "order.completed"}'
        timestamp = int(time.time() * 1000)
        header = "{},v1={}".format(
            generate_signature(payload, "whsec_new", timestamp),
            generate_signature(payload, "whsec_retired", timestamp).split("v1=")[1],
        )

        with pytest.raises(RailhookError):
            verify_signature(payload + " ", header, "whsec_new")

    def test_rotation_header_verifies_when_the_match_is_last_beside_an_unknown_version(self):
        payload = '{"type": "order.completed"}'
        timestamp = int(time.time() * 1000)

        def v1(secret):
            return generate_signature(payload, secret, timestamp).split("v1=")[1]

        header = f"t={timestamp},v0=deadbeef,v1={v1('whsec_retired')},v2={v1('whsec_new')},v1={v1('whsec_new')}"

        assert verify_signature(payload, header, "whsec_new") is True

    def test_rotation_header_still_enforces_the_tolerance(self):
        payload = '{"type": "order.completed"}'
        stale = int(time.time() * 1000) - 600000
        header = "{},v1={}".format(
            generate_signature(payload, "whsec_new", stale),
            generate_signature(payload, "whsec_retired", stale).split("v1=")[1],
        )

        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, header, "whsec_retired")

        assert exc.value.code == "timestamp_expired"

    def test_non_numeric_timestamp_raises_railhook_error(self):
        """A malformed header is a bad request, not an unhandled ValueError (a 500)."""
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "t=abc,v1=00", "secret")

        assert exc.value.code == "invalid_signature"

    def test_non_ascii_signature_is_rejected_not_crashed(self):
        """hmac.compare_digest raises TypeError on non-ASCII str; the header is attacker-controlled."""
        timestamp = int(time.time() * 1000)

        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", f"t={timestamp},v1=é", "secret")

        assert exc.value.code == "invalid_signature"

    def test_raises_on_missing_signature(self):
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "", "secret")
        
        assert "Missing signature header" in str(exc.value)
        assert exc.value.code == "invalid_signature"

    def test_raises_on_invalid_format(self):
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "invalid_format", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_missing_timestamp(self):
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "v1=abc123", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_missing_v1(self):
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "t=1700000000000", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_expired_timestamp(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        old_timestamp = int(time.time() * 1000) - 600000  # 10 min ago
        signature = generate_signature(payload, secret, old_timestamp)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, signature, secret)
        
        assert "outside tolerance window" in str(exc.value)
        assert exc.value.code == "timestamp_expired"

    def test_raises_on_future_timestamp(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        future_timestamp = int(time.time() * 1000) + 600000  # 10 min in future
        signature = generate_signature(payload, secret, future_timestamp)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, signature, secret)
        
        assert "outside tolerance window" in str(exc.value)

    def test_accepts_timestamp_within_tolerance(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        recent_timestamp = int(time.time() * 1000) - 60000  # 1 min ago
        signature = generate_signature(payload, secret, recent_timestamp)
        
        assert verify_signature(payload, signature, secret) is True

    def test_raises_on_invalid_signature(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, f"t={timestamp},v1=invalid", secret)
        
        assert "Invalid signature" in str(exc.value)

    def test_raises_on_tampered_payload(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        tampered = '{"type": "hacked"}'
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(tampered, signature, secret)
        
        assert "Invalid signature" in str(exc.value)

    def test_respects_custom_tolerance(self):
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        old_timestamp = int(time.time() * 1000) - 60000  # 1 min ago
        signature = generate_signature(payload, secret, old_timestamp)
        
        with pytest.raises(RailhookError):
            verify_signature(payload, signature, secret, tolerance_ms=30000)
        
        assert verify_signature(payload, signature, secret, tolerance_ms=120000) is True


class TestConstructEvent:
    def test_constructs_event_from_valid_request(self):
        payload = '{"type": "order.completed", "data": {"orderId": "123"}}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        headers = {
            "x-signature": signature,
            "x-timestamp": str(timestamp),
            "x-event-id": "evt_123",
            "x-delivery-id": "dlv_456",
        }
        
        event = construct_event(payload, headers, secret)
        
        assert event.event_id == "evt_123"
        assert event.delivery_id == "dlv_456"
        assert event.timestamp == timestamp
        assert event.type == "order.completed"
        assert event.data == {"orderId": "123"}

    def test_handles_uppercase_headers(self):
        payload = '{"type": "test", "data": {}}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        headers = {
            "X-Signature": signature,
            "X-Timestamp": str(timestamp),
            "X-Event-Id": "evt_123",
        }
        
        event = construct_event(payload, headers, secret)
        
        assert event.event_id == "evt_123"

    def test_raises_on_missing_signature(self):
        headers = {"x-timestamp": "1700000000000"}
        
        with pytest.raises(RailhookError) as exc:
            construct_event('{"type": "test"}', headers, "secret")
        
        assert "Missing X-Signature header" in str(exc.value)

    def test_raises_on_invalid_json(self):
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        invalid_payload = "not valid json"
        signature = generate_signature(invalid_payload, secret, timestamp)
        
        headers = {"x-signature": signature}
        
        with pytest.raises(RailhookError) as exc:
            construct_event(invalid_payload, headers, secret)
        
        assert "Invalid JSON payload" in str(exc.value)

    def test_handles_flat_payload(self):
        payload = '{"type": "test.event", "value": 123}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        headers = {"x-signature": signature}
        
        event = construct_event(payload, headers, secret)
        
        assert event.type == "test.event"
        assert event.data == {"type": "test.event", "value": 123}
