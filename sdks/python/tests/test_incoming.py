"""Tests for incoming webhooks functionality."""

from railhook import (
    Railhook,
    IncomingSource,
    IncomingSourceCreateParams,
    IncomingSourceUpdateParams,
    IncomingDestination,
    IncomingDestinationCreateParams,
    IncomingEvent,
    IncomingEventListParams,
    IncomingForwardAttempt,
    ReplayEventResponse,
    PaginatedResponse,
)


class TestIncomingSourceTypes:
    """Tests for IncomingSource type classes."""

    def test_incoming_source_from_dict(self):
        data = {
            "id": "src-123",
            "projectId": "proj-456",
            "name": "Stripe Webhooks",
            "slug": "stripe",
            "providerType": "STRIPE",
            "status": "ACTIVE",
            "ingressPathToken": "tok_abc",
            "ingressUrl": "https://api.example.com/ingress/tok_abc",
            "verificationMode": "HMAC_GENERIC",
            "hmacSecretConfigured": True,
            "hmacHeaderName": "Stripe-Signature",
            "createdAt": "2024-01-01T00:00:00Z",
            "rateLimitPerSecond": 100,
        }
        source = IncomingSource.from_dict(data)
        assert source.id == "src-123"
        assert source.project_id == "proj-456"
        assert source.name == "Stripe Webhooks"
        assert source.slug == "stripe"
        assert source.provider_type == "STRIPE"
        assert source.status == "ACTIVE"
        assert source.ingress_path_token == "tok_abc"
        assert source.ingress_url == "https://api.example.com/ingress/tok_abc"
        assert source.verification_mode == "HMAC_GENERIC"
        assert source.hmac_secret_configured is True
        assert source.hmac_header_name == "Stripe-Signature"
        assert source.rate_limit_per_second == 100

    def test_incoming_source_from_dict_optional_fields(self):
        data = {
            "id": "src-123",
            "projectId": "proj-456",
            "name": "Generic",
            "slug": "generic",
            "providerType": "GENERIC",
            "status": "ACTIVE",
            "ingressPathToken": "tok_def",
            "ingressUrl": "https://api.example.com/ingress/tok_def",
            "verificationMode": "NONE",
            "createdAt": "2024-01-01T00:00:00Z",
        }
        source = IncomingSource.from_dict(data)
        assert source.hmac_header_name is None
        assert source.hmac_signature_prefix is None
        assert source.rate_limit_per_second is None
        assert source.updated_at is None
        assert source.hmac_secret_configured is False

    def test_incoming_source_create_params_to_dict(self):
        params = IncomingSourceCreateParams(
            name="Stripe Webhooks",
            slug="stripe",
            provider_type="STRIPE",
            verification_mode="HMAC_GENERIC",
            hmac_secret="whsec_test",
            hmac_header_name="Stripe-Signature",
            rate_limit_per_second=100,
        )
        data = params.to_dict()
        assert data["name"] == "Stripe Webhooks"
        assert data["slug"] == "stripe"
        assert data["providerType"] == "STRIPE"
        assert data["verificationMode"] == "HMAC_GENERIC"
        assert data["hmacSecret"] == "whsec_test"
        assert data["hmacHeaderName"] == "Stripe-Signature"
        assert data["rateLimitPerSecond"] == 100

    def test_incoming_source_create_params_minimal(self):
        params = IncomingSourceCreateParams(name="My Source")
        data = params.to_dict()
        assert data == {"name": "My Source"}

    def test_incoming_source_update_params_to_dict(self):
        params = IncomingSourceUpdateParams(name="Updated", status="DISABLED")
        data = params.to_dict()
        assert data == {"name": "Updated", "status": "DISABLED"}

    def test_incoming_source_update_params_empty(self):
        params = IncomingSourceUpdateParams()
        data = params.to_dict()
        assert data == {}


class TestIncomingDestinationTypes:
    """Tests for IncomingDestination type classes."""

    def test_incoming_destination_from_dict(self):
        data = {
            "id": "dest-789",
            "incomingSourceId": "src-123",
            "url": "https://api.example.com/webhooks/stripe",
            "authType": "BEARER",
            "authConfigured": True,
            "customHeadersJson": '{"X-Custom":"value"}',
            "enabled": True,
            "maxAttempts": 5,
            "timeoutSeconds": 30,
            "retryDelays": "60,300,900",
            "payloadTransform": "$.data",
            "createdAt": "2024-01-01T00:00:00Z",
            "updatedAt": "2024-01-02T00:00:00Z",
        }
        dest = IncomingDestination.from_dict(data)
        assert dest.id == "dest-789"
        assert dest.incoming_source_id == "src-123"
        assert dest.url == "https://api.example.com/webhooks/stripe"
        assert dest.auth_type == "BEARER"
        assert dest.auth_configured is True
        assert dest.enabled is True
        assert dest.max_attempts == 5
        assert dest.timeout_seconds == 30
        assert dest.retry_delays == "60,300,900"
        assert dest.payload_transform == "$.data"

    def test_incoming_destination_from_dict_defaults(self):
        data = {
            "id": "dest-789",
            "incomingSourceId": "src-123",
            "url": "https://example.com/hook",
            "createdAt": "2024-01-01T00:00:00Z",
        }
        dest = IncomingDestination.from_dict(data)
        assert dest.auth_type == "NONE"
        assert dest.auth_configured is False
        assert dest.enabled is True
        assert dest.max_attempts == 5
        assert dest.timeout_seconds == 30

    def test_incoming_destination_create_params_to_dict(self):
        params = IncomingDestinationCreateParams(
            url="https://example.com/hook",
            auth_type="BEARER",
            auth_config='{"token":"abc"}',
            enabled=True,
            max_attempts=3,
            timeout_seconds=15,
        )
        data = params.to_dict()
        assert data["url"] == "https://example.com/hook"
        assert data["authType"] == "BEARER"
        assert data["authConfig"] == '{"token":"abc"}'
        assert data["enabled"] is True
        assert data["maxAttempts"] == 3
        assert data["timeoutSeconds"] == 15

    def test_incoming_destination_create_params_minimal(self):
        params = IncomingDestinationCreateParams(url="https://example.com/hook")
        data = params.to_dict()
        assert data == {"url": "https://example.com/hook"}


class TestIncomingEventTypes:
    """Tests for IncomingEvent type classes."""

    def test_incoming_event_from_dict(self):
        data = {
            "id": "evt-abc",
            "incomingSourceId": "src-123",
            "sourceName": "Stripe Webhooks",
            "requestId": "req-xyz",
            "method": "POST",
            "path": "/ingress/tok_abc",
            "queryParams": "foo=bar",
            "headersJson": '{"Content-Type":"application/json"}',
            "bodyRaw": '{"type":"checkout.session.completed"}',
            "bodySha256": "abc123",
            "contentType": "application/json",
            "clientIp": "1.2.3.4",
            "userAgent": "Stripe/1.0",
            "verified": True,
            "receivedAt": "2024-01-01T00:00:00Z",
        }
        event = IncomingEvent.from_dict(data)
        assert event.id == "evt-abc"
        assert event.incoming_source_id == "src-123"
        assert event.source_name == "Stripe Webhooks"
        assert event.request_id == "req-xyz"
        assert event.method == "POST"
        assert event.verified is True
        assert event.body_raw == '{"type":"checkout.session.completed"}'

    def test_incoming_event_from_dict_optional_fields(self):
        data = {
            "id": "evt-abc",
            "incomingSourceId": "src-123",
            "requestId": "req-xyz",
            "method": "POST",
            "path": "/ingress/tok_abc",
            "receivedAt": "2024-01-01T00:00:00Z",
        }
        event = IncomingEvent.from_dict(data)
        assert event.source_name == ""
        assert event.query_params is None
        assert event.verified is None
        assert event.verification_error is None

    def test_incoming_event_list_params_to_params(self):
        params = IncomingEventListParams(source_id="src-123", page=2, size=10)
        query = params.to_params()
        assert query["sourceId"] == "src-123"
        assert query["page"] == 2
        assert query["size"] == 10

    def test_incoming_event_list_params_defaults(self):
        params = IncomingEventListParams()
        query = params.to_params()
        assert query == {"page": 0, "size": 20}
        assert "sourceId" not in query

    def test_incoming_forward_attempt_from_dict(self):
        data = {
            "id": "att-1",
            "incomingEventId": "evt-abc",
            "destinationId": "dest-789",
            "destinationUrl": "https://api.example.com/webhooks/stripe",
            "attemptNumber": 1,
            "status": "SUCCESS",
            "startedAt": "2024-01-01T00:00:00Z",
            "finishedAt": "2024-01-01T00:00:01Z",
            "responseCode": 200,
            "responseHeadersJson": '{"Content-Type":"application/json"}',
            "responseBodySnippet": "OK",
            "errorMessage": None,
            "nextRetryAt": None,
            "createdAt": "2024-01-01T00:00:00Z",
        }
        attempt = IncomingForwardAttempt.from_dict(data)
        assert attempt.id == "att-1"
        assert attempt.incoming_event_id == "evt-abc"
        assert attempt.destination_id == "dest-789"
        assert attempt.destination_url == "https://api.example.com/webhooks/stripe"
        assert attempt.attempt_number == 1
        assert attempt.status == "SUCCESS"
        assert attempt.response_code == 200
        assert attempt.response_body_snippet == "OK"
        assert attempt.created_at == "2024-01-01T00:00:00Z"

    def test_replay_event_response_from_dict(self):
        data = {
            "status": "replayed",
            "eventId": "evt-abc",
            "destinationsCount": 3,
        }
        resp = ReplayEventResponse.from_dict(data)
        assert resp.status == "replayed"
        assert resp.event_id == "evt-abc"
        assert resp.destinations_count == 3


class TestPaginatedResponseGeneric:
    """Tests for PaginatedResponse with different item types."""

    def test_paginated_incoming_sources(self):
        data = {
            "content": [
                {
                    "id": "src-1",
                    "projectId": "proj-1",
                    "name": "Source 1",
                    "slug": "source-1",
                    "providerType": "GENERIC",
                    "status": "ACTIVE",
                    "ingressPathToken": "tok_1",
                    "ingressUrl": "http://example.com/ingress/tok_1",
                    "verificationMode": "NONE",
                    "createdAt": "2024-01-01T00:00:00Z",
                }
            ],
            "totalElements": 1,
            "totalPages": 1,
            "size": 20,
            "number": 0,
        }
        response = PaginatedResponse.from_dict(data, IncomingSource)
        assert len(response.content) == 1
        assert isinstance(response.content[0], IncomingSource)
        assert response.content[0].slug == "source-1"

    def test_paginated_incoming_events(self):
        data = {
            "content": [
                {
                    "id": "evt-1",
                    "incomingSourceId": "src-1",
                    "requestId": "req-1",
                    "method": "POST",
                    "path": "/ingress/tok_1",
                    "receivedAt": "2024-01-01T00:00:00Z",
                }
            ],
            "totalElements": 50,
            "totalPages": 3,
            "size": 20,
            "number": 0,
        }
        response = PaginatedResponse.from_dict(data, IncomingEvent)
        assert len(response.content) == 1
        assert isinstance(response.content[0], IncomingEvent)
        assert response.total_elements == 50


class TestClientIncomingModules:
    """Tests that the client initializes incoming API modules."""

    def test_has_incoming_sources(self):
        client = Railhook(api_key="test_key")
        assert client.incoming_sources is not None
        assert hasattr(client.incoming_sources, "create")
        assert hasattr(client.incoming_sources, "get")
        assert hasattr(client.incoming_sources, "list")
        assert hasattr(client.incoming_sources, "update")
        assert hasattr(client.incoming_sources, "delete")
        assert hasattr(client.incoming_sources, "create_destination")
        assert hasattr(client.incoming_sources, "get_destination")
        assert hasattr(client.incoming_sources, "list_destinations")
        assert hasattr(client.incoming_sources, "update_destination")
        assert hasattr(client.incoming_sources, "delete_destination")

    def test_has_incoming_events(self):
        client = Railhook(api_key="test_key")
        assert client.incoming_events is not None
        assert hasattr(client.incoming_events, "list")
        assert hasattr(client.incoming_events, "get")
        assert hasattr(client.incoming_events, "get_attempts")
        assert hasattr(client.incoming_events, "replay")
