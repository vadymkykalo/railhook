"""Hookflow API client."""

from typing import Any, Dict, List, Optional
import requests

from .types import (
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
    EndpointTestResult,
    RateLimitInfo,
    IncomingSource,
    IncomingSourceCreateParams,
    IncomingSourceUpdateParams,
    IncomingDestination,
    IncomingDestinationCreateParams,
    IncomingEvent,
    IncomingEventListParams,
    IncomingForwardAttempt,
    ReplayEventResponse,
)
from .errors import (
    HookflowError,
    AuthenticationError,
    RateLimitError,
    ValidationError,
    NotFoundError,
)

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_TIMEOUT = 30
SDK_VERSION = "2.11.0"


class Hookflow:
    """Hookflow API client."""

    def __init__(
        self,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        timeout: int = DEFAULT_TIMEOUT,
    ) -> None:
        if not api_key:
            raise ValueError("API key is required")

        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

        self.events = Events(self)
        self.endpoints = Endpoints(self)
        self.subscriptions = Subscriptions(self)
        self.deliveries = Deliveries(self)
        self.incoming_sources = IncomingSources(self)
        self.incoming_events = IncomingEventsApi(self)

    def get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        """Generic GET request. Use for endpoints not yet covered by the SDK."""
        return self._request("GET", path, params=params)

    def post(
        self,
        path: str,
        body: Optional[Dict[str, Any]] = None,
        idempotency_key: Optional[str] = None,
    ) -> Any:
        """Generic POST request. Use for endpoints not yet covered by the SDK."""
        return self._request("POST", path, body=body, idempotency_key=idempotency_key)

    def put(self, path: str, body: Optional[Dict[str, Any]] = None) -> Any:
        """Generic PUT request. Use for endpoints not yet covered by the SDK."""
        return self._request("PUT", path, body=body)

    def patch(self, path: str, body: Optional[Dict[str, Any]] = None) -> Any:
        """Generic PATCH request. Use for endpoints not yet covered by the SDK."""
        return self._request("PATCH", path, body=body)

    def delete(self, path: str) -> Any:
        """Generic DELETE request. Use for endpoints not yet covered by the SDK."""
        return self._request("DELETE", path)

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        idempotency_key: Optional[str] = None,
    ) -> Any:
        url = f"{self.base_url}{path}"

        headers = {
            "X-API-Key": self.api_key,
            "Content-Type": "application/json",
            "User-Agent": f"hookflow-python/{SDK_VERSION}",
        }

        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key

        try:
            response = requests.request(
                method=method,
                url=url,
                json=body,
                params=params,
                headers=headers,
                timeout=self.timeout,
            )
        except requests.Timeout:
            raise HookflowError("Request timeout", 0, "timeout")
        except requests.RequestException as e:
            raise HookflowError(str(e), 0, "network_error")

        rate_limit_info = self._extract_rate_limit_info(response.headers)

        if response.status_code == 204:
            return None

        try:
            data = response.json() if response.text else {}
        except ValueError:
            data = {}

        if response.status_code >= 400:
            raise self._handle_error(response.status_code, data, rate_limit_info)

        return data

    def _extract_rate_limit_info(
        self, headers: requests.structures.CaseInsensitiveDict
    ) -> Optional[RateLimitInfo]:
        limit = headers.get("X-RateLimit-Limit")
        remaining = headers.get("X-RateLimit-Remaining")
        reset = headers.get("X-RateLimit-Reset")

        if limit and remaining and reset:
            return RateLimitInfo(
                limit=int(limit),
                remaining=int(remaining),
                reset=int(reset),
            )
        return None

    def _handle_error(
        self,
        status: int,
        body: Dict[str, Any],
        rate_limit_info: Optional[RateLimitInfo],
    ) -> HookflowError:
        message = body.get("message", "Unknown error")

        if status == 401:
            return AuthenticationError(message)
        elif status == 404:
            return NotFoundError(message)
        elif status == 429:
            import time
            # `reset` is a Unix timestamp in seconds (see RateLimitInfo), so the
            # fallback has to be in seconds too.
            info = rate_limit_info or RateLimitInfo(
                limit=0, remaining=0, reset=int(time.time()) + 60
            )
            return RateLimitError(message, info)
        elif status == 400:
            return ValidationError(message, body.get("fieldErrors", {}))
        else:
            return HookflowError(message, status, body.get("error"))


class Events:
    """Events API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def send(
        self, event: Event, idempotency_key: Optional[str] = None
    ) -> EventResponse:
        """Send an event to be delivered to subscribed endpoints."""
        data = self._client._request(
            "POST",
            "/api/v1/events",
            body={"type": event.type, "data": event.data},
            idempotency_key=idempotency_key,
        )
        return EventResponse.from_dict(data)


class Endpoints:
    """Endpoints API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def create(self, project_id: str, params: EndpointCreateParams) -> Endpoint:
        """Create a new endpoint."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/endpoints",
            body=params.to_dict(),
        )
        return Endpoint.from_dict(data)

    def get(self, project_id: str, endpoint_id: str) -> Endpoint:
        """Get endpoint by ID."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/endpoints/{endpoint_id}",
        )
        return Endpoint.from_dict(data)

    def list(
        self, project_id: str, page: int = 0, size: int = 20
    ) -> PaginatedResponse:
        """List a project's endpoints.

        The API returns a Spring page envelope here, not a bare array — the
        endpoints themselves are in ``.content`` (iterating the page yields
        them directly).
        """
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/endpoints",
            params={"page": page, "size": size},
        )
        return PaginatedResponse.from_dict(data, Endpoint)

    def update(
        self, project_id: str, endpoint_id: str, params: EndpointUpdateParams
    ) -> Endpoint:
        """Update endpoint."""
        data = self._client._request(
            "PUT",
            f"/api/v1/projects/{project_id}/endpoints/{endpoint_id}",
            body=params.to_dict(),
        )
        return Endpoint.from_dict(data)

    def delete(self, project_id: str, endpoint_id: str) -> None:
        """Delete endpoint."""
        self._client._request(
            "DELETE",
            f"/api/v1/projects/{project_id}/endpoints/{endpoint_id}",
        )

    def rotate_secret(self, project_id: str, endpoint_id: str) -> Endpoint:
        """Rotate endpoint webhook secret."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/endpoints/{endpoint_id}/rotate-secret",
        )
        return Endpoint.from_dict(data)

    def test(self, project_id: str, endpoint_id: str) -> EndpointTestResult:
        """Test endpoint connectivity."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/endpoints/{endpoint_id}/test",
        )
        return EndpointTestResult.from_dict(data)


class Subscriptions:
    """Subscriptions API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def create(
        self, project_id: str, params: SubscriptionCreateParams
    ) -> Subscription:
        """Create a new subscription."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/subscriptions",
            body=params.to_dict(),
        )
        return Subscription.from_dict(data)

    def get(self, project_id: str, subscription_id: str) -> Subscription:
        """Get subscription by ID."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/subscriptions/{subscription_id}",
        )
        return Subscription.from_dict(data)

    def list(self, project_id: str) -> List[Subscription]:
        """List all subscriptions for a project."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/subscriptions",
        )
        return [Subscription.from_dict(s) for s in data]

    def update(
        self,
        project_id: str,
        subscription_id: str,
        event_type: Optional[str] = None,
        enabled: Optional[bool] = None,
        ordering_enabled: Optional[bool] = None,
        max_attempts: Optional[int] = None,
        timeout_seconds: Optional[int] = None,
        retry_delays: Optional[str] = None,
        payload_template: Optional[str] = None,
        custom_headers: Optional[str] = None,
        transformation_id: Optional[str] = None,
    ) -> Subscription:
        """Update subscription."""
        body: Dict[str, Any] = {}
        if event_type is not None:
            body["eventType"] = event_type
        if enabled is not None:
            body["enabled"] = enabled
        if ordering_enabled is not None:
            body["orderingEnabled"] = ordering_enabled
        if max_attempts is not None:
            body["maxAttempts"] = max_attempts
        if timeout_seconds is not None:
            body["timeoutSeconds"] = timeout_seconds
        if retry_delays is not None:
            body["retryDelays"] = retry_delays
        if payload_template is not None:
            body["payloadTemplate"] = payload_template
        if custom_headers is not None:
            body["customHeaders"] = custom_headers
        if transformation_id is not None:
            body["transformationId"] = transformation_id

        data = self._client._request(
            "PUT",
            f"/api/v1/projects/{project_id}/subscriptions/{subscription_id}",
            body=body,
        )
        return Subscription.from_dict(data)

    def delete(self, project_id: str, subscription_id: str) -> None:
        """Delete subscription."""
        self._client._request(
            "DELETE",
            f"/api/v1/projects/{project_id}/subscriptions/{subscription_id}",
        )


class Deliveries:
    """Deliveries API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def get(self, delivery_id: str) -> Delivery:
        """Get delivery by ID."""
        data = self._client._request("GET", f"/api/v1/deliveries/{delivery_id}")
        return Delivery.from_dict(data)

    def list(
        self, project_id: str, params: Optional[DeliveryListParams] = None
    ) -> PaginatedResponse:
        """List deliveries for a project with optional filters."""
        query_params = (params or DeliveryListParams()).to_params()
        data = self._client._request(
            "GET",
            f"/api/v1/deliveries/projects/{project_id}",
            params=query_params,
        )
        return PaginatedResponse.from_dict(data)

    def get_attempts(self, delivery_id: str) -> List[DeliveryAttempt]:
        """Get all delivery attempts."""
        data = self._client._request(
            "GET",
            f"/api/v1/deliveries/{delivery_id}/attempts",
        )
        return [DeliveryAttempt.from_dict(a) for a in data]

    def replay(self, delivery_id: str) -> None:
        """Replay a failed delivery."""
        self._client._request("POST", f"/api/v1/deliveries/{delivery_id}/replay")


class IncomingSources:
    """Incoming Sources API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def create(
        self, project_id: str, params: IncomingSourceCreateParams
    ) -> IncomingSource:
        """Create a new incoming webhook source."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/incoming-sources",
            body=params.to_dict(),
        )
        return IncomingSource.from_dict(data)

    def get(self, project_id: str, source_id: str) -> IncomingSource:
        """Get incoming source by ID."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}",
        )
        return IncomingSource.from_dict(data)

    def list(self, project_id: str) -> PaginatedResponse:
        """List incoming sources for a project."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-sources",
        )
        return PaginatedResponse.from_dict(data, IncomingSource)

    def update(
        self, project_id: str, source_id: str, params: IncomingSourceUpdateParams
    ) -> IncomingSource:
        """Update incoming source."""
        data = self._client._request(
            "PUT",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}",
            body=params.to_dict(),
        )
        return IncomingSource.from_dict(data)

    def delete(self, project_id: str, source_id: str) -> None:
        """Delete (disable) incoming source."""
        self._client._request(
            "DELETE",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}",
        )

    # ── Destinations ──

    def create_destination(
        self,
        project_id: str,
        source_id: str,
        params: IncomingDestinationCreateParams,
    ) -> IncomingDestination:
        """Create a forwarding destination for an incoming source."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}/destinations",
            body=params.to_dict(),
        )
        return IncomingDestination.from_dict(data)

    def get_destination(
        self, project_id: str, source_id: str, destination_id: str
    ) -> IncomingDestination:
        """Get destination by ID."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}/destinations/{destination_id}",
        )
        return IncomingDestination.from_dict(data)

    def list_destinations(
        self, project_id: str, source_id: str
    ) -> PaginatedResponse:
        """List destinations for an incoming source."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}/destinations",
        )
        return PaginatedResponse.from_dict(data, IncomingDestination)

    def update_destination(
        self,
        project_id: str,
        source_id: str,
        destination_id: str,
        params: IncomingDestinationCreateParams,
    ) -> IncomingDestination:
        """Update a forwarding destination."""
        data = self._client._request(
            "PUT",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}/destinations/{destination_id}",
            body=params.to_dict(),
        )
        return IncomingDestination.from_dict(data)

    def delete_destination(
        self, project_id: str, source_id: str, destination_id: str
    ) -> None:
        """Delete a forwarding destination."""
        self._client._request(
            "DELETE",
            f"/api/v1/projects/{project_id}/incoming-sources/{source_id}/destinations/{destination_id}",
        )


class IncomingEventsApi:
    """Incoming Events API."""

    def __init__(self, client: Hookflow) -> None:
        self._client = client

    def list(
        self, project_id: str, params: Optional[IncomingEventListParams] = None
    ) -> PaginatedResponse:
        """List incoming events for a project."""
        query_params = (params or IncomingEventListParams()).to_params()
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-events",
            params=query_params,
        )
        return PaginatedResponse.from_dict(data, IncomingEvent)

    def get(self, project_id: str, event_id: str) -> IncomingEvent:
        """Get incoming event by ID."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-events/{event_id}",
        )
        return IncomingEvent.from_dict(data)

    def get_attempts(
        self, project_id: str, event_id: str
    ) -> List[IncomingForwardAttempt]:
        """Get forward attempts for an incoming event."""
        data = self._client._request(
            "GET",
            f"/api/v1/projects/{project_id}/incoming-events/{event_id}/attempts",
        )
        if isinstance(data, dict) and "content" in data:
            return [IncomingForwardAttempt.from_dict(a) for a in data["content"]]
        return [IncomingForwardAttempt.from_dict(a) for a in data]

    def replay(self, project_id: str, event_id: str) -> ReplayEventResponse:
        """Replay an incoming event to all enabled destinations."""
        data = self._client._request(
            "POST",
            f"/api/v1/projects/{project_id}/incoming-events/{event_id}/replay",
        )
        return ReplayEventResponse.from_dict(data)
