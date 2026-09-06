"""Tests for webhook signature verification."""

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
    """Tests for generate_signature function."""

    def test_generates_valid_format(self):
        """Should generate signature in correct format."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        
        signature = generate_signature(payload, secret)
        
        assert signature.startswith("t=")
        assert ",v1=" in signature
        parts = signature.split(",")
        assert len(parts) == 2

    def test_uses_provided_timestamp(self):
        """Should use provided timestamp."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        signature = generate_signature(payload, secret, timestamp)
        
        assert f"t={timestamp}" in signature

    def test_consistent_signatures(self):
        """Should generate consistent signatures for same inputs."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        sig1 = generate_signature(payload, secret, timestamp)
        sig2 = generate_signature(payload, secret, timestamp)
        
        assert sig1 == sig2

    def test_different_payloads_different_signatures(self):
        """Different payloads should produce different signatures."""
        secret = "whsec_test_secret"
        timestamp = 1700000000000
        
        sig1 = generate_signature('{"a": 1}', secret, timestamp)
        sig2 = generate_signature('{"b": 2}', secret, timestamp)
        
        assert sig1 != sig2

    def test_different_secrets_different_signatures(self):
        """Different secrets should produce different signatures."""
        payload = '{"type": "test"}'
        timestamp = 1700000000000
        
        sig1 = generate_signature(payload, "secret1", timestamp)
        sig2 = generate_signature(payload, "secret2", timestamp)
        
        assert sig1 != sig2


class TestVerifySignature:
    """Tests for verify_signature function."""

    def test_verifies_valid_signature(self):
        """Should verify a valid signature."""
        payload = '{"type": "order.completed", "data": {"id": "123"}}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        assert verify_signature(payload, signature, secret) is True

    def test_verifies_either_signature_during_a_secret_rotation(self):
        """A header carrying two v1 values verifies with either secret.

        After a rotation Railhook signs each delivery with the new secret and the
        retired one for the grace window, so a receiver that has not deployed the new
        secret yet keeps working. Before this, the parser kept only the last v1 and
        rejected whichever half of the pair the receiver was holding.
        """
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

    def test_raises_on_missing_signature(self):
        """Should raise on missing signature."""
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "", "secret")
        
        assert "Missing signature header" in str(exc.value)
        assert exc.value.code == "invalid_signature"

    def test_raises_on_invalid_format(self):
        """Should raise on invalid signature format."""
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "invalid_format", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_missing_timestamp(self):
        """Should raise when timestamp is missing."""
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "v1=abc123", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_missing_v1(self):
        """Should raise when v1 signature is missing."""
        with pytest.raises(RailhookError) as exc:
            verify_signature("payload", "t=1700000000000", "secret")
        
        assert "Invalid signature format" in str(exc.value)

    def test_raises_on_expired_timestamp(self):
        """Should raise on expired timestamp."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        old_timestamp = int(time.time() * 1000) - 600000  # 10 min ago
        signature = generate_signature(payload, secret, old_timestamp)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, signature, secret)
        
        assert "outside tolerance window" in str(exc.value)
        assert exc.value.code == "timestamp_expired"

    def test_raises_on_future_timestamp(self):
        """Should raise on future timestamp outside tolerance."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        future_timestamp = int(time.time() * 1000) + 600000  # 10 min in future
        signature = generate_signature(payload, secret, future_timestamp)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, signature, secret)
        
        assert "outside tolerance window" in str(exc.value)

    def test_accepts_timestamp_within_tolerance(self):
        """Should accept timestamp within tolerance."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        recent_timestamp = int(time.time() * 1000) - 60000  # 1 min ago
        signature = generate_signature(payload, secret, recent_timestamp)
        
        assert verify_signature(payload, signature, secret) is True

    def test_raises_on_invalid_signature(self):
        """Should raise on invalid signature value."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(payload, f"t={timestamp},v1=invalid", secret)
        
        assert "Invalid signature" in str(exc.value)

    def test_raises_on_tampered_payload(self):
        """Should raise when payload is tampered."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        tampered = '{"type": "hacked"}'
        
        with pytest.raises(RailhookError) as exc:
            verify_signature(tampered, signature, secret)
        
        assert "Invalid signature" in str(exc.value)

    def test_respects_custom_tolerance(self):
        """Should respect custom tolerance setting."""
        payload = '{"type": "test"}'
        secret = "whsec_test_secret"
        old_timestamp = int(time.time() * 1000) - 60000  # 1 min ago
        signature = generate_signature(payload, secret, old_timestamp)
        
        # Should fail with 30s tolerance
        with pytest.raises(RailhookError):
            verify_signature(payload, signature, secret, tolerance_ms=30000)
        
        # Should pass with 2min tolerance
        assert verify_signature(payload, signature, secret, tolerance_ms=120000) is True


class TestConstructEvent:
    """Tests for construct_event function."""

    def test_constructs_event_from_valid_request(self):
        """Should construct event from valid request."""
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
        """Should handle uppercase headers."""
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
        """Should raise on missing signature header."""
        headers = {"x-timestamp": "1700000000000"}
        
        with pytest.raises(RailhookError) as exc:
            construct_event('{"type": "test"}', headers, "secret")
        
        assert "Missing X-Signature header" in str(exc.value)

    def test_raises_on_invalid_json(self):
        """Should raise on invalid JSON payload."""
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        invalid_payload = "not valid json"
        signature = generate_signature(invalid_payload, secret, timestamp)
        
        headers = {"x-signature": signature}
        
        with pytest.raises(RailhookError) as exc:
            construct_event(invalid_payload, headers, secret)
        
        assert "Invalid JSON payload" in str(exc.value)

    def test_handles_flat_payload(self):
        """Should handle payload without nested data field."""
        payload = '{"type": "test.event", "value": 123}'
        secret = "whsec_test_secret"
        timestamp = int(time.time() * 1000)
        signature = generate_signature(payload, secret, timestamp)
        
        headers = {"x-signature": signature}
        
        event = construct_event(payload, headers, secret)
        
        assert event.type == "test.event"
        assert event.data == {"type": "test.event", "value": 123}
