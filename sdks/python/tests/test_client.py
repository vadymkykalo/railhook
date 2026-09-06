"""Tests for Railhook client."""

import pytest

from railhook import (
    Railhook,
    RailhookError,
    AuthenticationError,
    RateLimitError,
    ValidationError,
    NotFoundError,
    Event,
    EventResponse,
    Endpoint,
    EndpointCreateParams,
    EndpointUpdateParams,
    Subscription,
    SubscriptionCreateParams,
    Delivery,
    DeliveryAttempt,
    DeliveryListParams,
    PaginatedResponse,
    RateLimitInfo,
)


class TestRailhookClient:
    """Tests for Railhook client initialization."""

    def test_creates_with_api_key(self):
        """Should create client with API key."""
        client = Railhook(api_key="test_api_key")
        assert client is not None
        assert client.api_key == "test_api_key"

    def test_raises_without_api_key(self):
        """Should raise error without API key."""
        with pytest.raises(ValueError) as exc:
            Railhook(api_key="")
        assert "API key is required" in str(exc.value)

    def test_uses_default_base_url(self):
        """Should use default base URL."""
        client = Railhook(api_key="test_api_key")
        assert client.base_url == "http://localhost:8080"

    def test_accepts_custom_base_url(self):
        """Should accept custom base URL."""
        client = Railhook(
            api_key="test_api_key",
            base_url="https://api.example.com/",
        )
        assert client.base_url == "https://api.example.com"

    def test_strips_trailing_slash(self):
        """Should strip trailing slash from base URL."""
        client = Railhook(
            api_key="test_api_key",
            base_url="https://api.example.com/",
        )
        assert not client.base_url.endswith("/")

    def test_accepts_custom_timeout(self):
        """Should accept custom timeout."""
        client = Railhook(api_key="test_api_key", timeout=60)
        assert client.timeout == 60

    def test_initializes_api_modules(self):
        """Should initialize all API modules."""
        client = Railhook(api_key="test_api_key")
        assert client.events is not None
        assert client.endpoints is not None
        assert client.subscriptions is not None
        assert client.deliveries is not None


class TestGenericRequestMethods:
    """Tests for generic request methods."""

    def test_exposes_get_method(self):
        """Should expose public get method."""
        client = Railhook(api_key="test_api_key")
        assert callable(client.get)

    def test_exposes_post_method(self):
        """Should expose public post method."""
        client = Railhook(api_key="test_api_key")
        assert callable(client.post)

    def test_exposes_put_method(self):
        """Should expose public put method."""
        client = Railhook(api_key="test_api_key")
        assert callable(client.put)

    def test_exposes_patch_method(self):
        """Should expose public patch method."""
        client = Railhook(api_key="test_api_key")
        assert callable(client.patch)

    def test_exposes_delete_method(self):
        """Should expose public delete method."""
        client = Railhook(api_key="test_api_key")
        assert callable(client.delete)


class TestErrorClasses:
    """Tests for error classes."""

    def test_railhook_error(self):
        """RailhookError should have correct properties."""
        error = RailhookError("Test error", 500, "test_code")
        assert error.message == "Test error"
        assert error.status == 500
        assert error.code == "test_code"
        assert "Test error" in str(error)

    def test_authentication_error_defaults(self):
        """AuthenticationError should have correct defaults."""
        error = AuthenticationError()
        assert error.message == "Invalid API key"
        assert error.status == 401
        assert error.code == "authentication_error"

    def test_authentication_error_custom_message(self):
        """AuthenticationError should accept custom message."""
        error = AuthenticationError("Custom auth error")
        assert error.message == "Custom auth error"

    def test_rate_limit_error(self):
        """RateLimitError should have rate limit info."""
        info = RateLimitInfo(limit=100, remaining=0, reset=1700000000000)
        error = RateLimitError("Rate limit exceeded", info)
        assert error.status == 429
        assert error.code == "rate_limit_exceeded"
        assert error.rate_limit_info.limit == 100

    def test_validation_error(self):
        """ValidationError should have field errors."""
        field_errors = {"email": "Invalid email", "url": "Invalid URL"}
        error = ValidationError("Validation failed", field_errors)
        assert error.status == 400
        assert error.code == "validation_error"
        assert error.field_errors == field_errors

    def test_validation_error_empty_fields(self):
        """ValidationError should default to empty field errors."""
        error = ValidationError("Validation failed")
        assert error.field_errors == {}

    def test_not_found_error(self):
        """NotFoundError should have correct defaults."""
        error = NotFoundError()
        assert error.message == "Resource not found"
        assert error.status == 404
        assert error.code == "not_found"


class TestTypeClasses:
    """Tests for type/model classes."""

    def test_event_creation(self):
        """Event should be created correctly."""
        event = Event(type="order.completed", data={"orderId": "123"})
        assert event.type == "order.completed"
        assert event.data == {"orderId": "123"}

    def test_event_response_from_dict(self):
        """EventResponse should parse from dict correctly."""
        data = {
            "eventId": "evt_123",
            "type": "order.completed",
            "createdAt": "2024-01-01T00:00:00Z",
            "deliveriesCreated": 3,
        }
        response = EventResponse.from_dict(data)
        assert response.event_id == "evt_123"
        assert response.type == "order.completed"
        assert response.deliveries_created == 3

    def test_endpoint_from_dict(self):
        """Endpoint should parse from dict correctly."""
        data = {
            "id": "ep_123",
            "url": "https://example.com/webhook",
            "secret": "whsec_abc",
            "enabled": True,
            "createdAt": "2024-01-01T00:00:00Z",
            "description": "Test endpoint",
            "rateLimitPerSecond": 10,
        }
        endpoint = Endpoint.from_dict(data)
        assert endpoint.id == "ep_123"
        assert endpoint.url == "https://example.com/webhook"
        assert endpoint.secret == "whsec_abc"
        assert endpoint.enabled is True
        assert endpoint.description == "Test endpoint"
        assert endpoint.rate_limit_per_second == 10

    def test_endpoint_create_params_to_dict(self):
        """EndpointCreateParams should convert to dict correctly."""
        params = EndpointCreateParams(
            url="https://example.com/webhook",
            description="Test",
            enabled=True,
            rate_limit_per_second=10,
        )
        data = params.to_dict()
        assert data["url"] == "https://example.com/webhook"
        assert data["description"] == "Test"
        assert data["enabled"] is True
        assert data["rateLimitPerSecond"] == 10

    def test_endpoint_update_params_to_dict(self):
        """EndpointUpdateParams should only include set fields."""
        params = EndpointUpdateParams(url="https://new-url.com")
        data = params.to_dict()
        assert data == {"url": "https://new-url.com"}

    def test_subscription_from_dict(self):
        """Subscription should parse from dict correctly."""
        data = {
            "id": "sub_123",
            "endpointId": "ep_456",
            "eventType": "order.completed",
            "enabled": True,
            "createdAt": "2024-01-01T00:00:00Z",
        }
        subscription = Subscription.from_dict(data)
        assert subscription.id == "sub_123"
        assert subscription.endpoint_id == "ep_456"
        assert subscription.event_type == "order.completed"

    def test_subscription_create_params_to_dict(self):
        """SubscriptionCreateParams should convert to dict correctly."""
        params = SubscriptionCreateParams(
            endpoint_id="ep_123",
            event_type="order.completed",
            enabled=True,
        )
        data = params.to_dict()
        assert data["endpointId"] == "ep_123"
        assert data["eventType"] == "order.completed"
        assert data["enabled"] is True

    def test_delivery_from_dict(self):
        """Delivery should parse from dict correctly."""
        data = {
            "id": "dlv_123",
            "eventId": "evt_456",
            "endpointId": "ep_789",
            "status": "SUCCESS",
            "attemptCount": 1,
            "maxAttempts": 7,
            "createdAt": "2024-01-01T00:00:00Z",
            "succeededAt": "2024-01-01T00:00:01Z",
        }
        delivery = Delivery.from_dict(data)
        assert delivery.id == "dlv_123"
        assert delivery.event_id == "evt_456"
        assert delivery.status.value == "SUCCESS"
        assert delivery.attempt_count == 1

    def test_delivery_attempt_from_dict(self):
        """DeliveryAttempt should parse a real DeliveryAttemptResponse.

        The fixture is deliberately a verbatim copy of what
        GET /api/v1/deliveries/{id}/attempts actually returns. It used to say
        httpStatus / latencyMs / attemptedAt — none of which the API has ever
        sent — so from_dict raised KeyError against a live server while this
        test stayed green.
        """
        data = {
            "id": "att_123",
            "deliveryId": "dlv_123",
            "attemptNumber": 1,
            "requestHeaders": '{"X-Signature": "t=1,v1=abc"}',
            "requestBody": '{"orderId": "ord_1"}',
            "httpStatusCode": 200,
            "responseHeaders": '{"Server": "nginx"}',
            "responseBody": "OK",
            "errorMessage": None,
            "durationMs": 150,
            "createdAt": "2024-01-01T00:00:00Z",
        }
        attempt = DeliveryAttempt.from_dict(data)
        assert attempt.id == "att_123"
        assert attempt.delivery_id == "dlv_123"
        assert attempt.attempt_number == 1
        assert attempt.http_status_code == 200
        assert attempt.duration_ms == 150
        assert attempt.created_at == "2024-01-01T00:00:00Z"
        assert attempt.response_body == "OK"

    def test_delivery_list_params_to_params(self):
        """DeliveryListParams should convert to query params correctly."""
        from railhook.types import DeliveryStatus
        
        params = DeliveryListParams(
            status=DeliveryStatus.FAILED,
            endpoint_id="ep_123",
            page=1,
            size=50,
        )
        query_params = params.to_params()
        assert query_params["status"] == "FAILED"
        assert query_params["endpointId"] == "ep_123"
        assert query_params["page"] == 1
        assert query_params["size"] == 50

    def test_paginated_response_from_dict(self):
        """PaginatedResponse should parse from dict correctly."""
        data = {
            "content": [
                {
                    "id": "dlv_1",
                    "eventId": "evt_1",
                    "endpointId": "ep_1",
                    "status": "SUCCESS",
                    "attemptCount": 1,
                    "maxAttempts": 7,
                    "createdAt": "2024-01-01T00:00:00Z",
                }
            ],
            "totalElements": 100,
            "totalPages": 5,
            "size": 20,
            "number": 0,
        }
        response = PaginatedResponse.from_dict(data)
        assert len(response.content) == 1
        assert response.total_elements == 100
        assert response.total_pages == 5
