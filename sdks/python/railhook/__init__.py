"""Official Python SDK for Railhook."""

from .client import Railhook
from .errors import (
    RailhookError,
    AuthenticationError,
    RateLimitError,
    ValidationError,
    NotFoundError,
)
from .webhooks import (
    verify_signature,
    verify_standard_webhook,
    construct_event,
    generate_signature,
)
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
    DeliveryStatus,
    PaginatedResponse,
    EndpointTestResult,
    RateLimitInfo,
    WebhookEvent,
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

__version__ = "2.14.0"

# Backward-compatible aliases
WebhookPlatform = Railhook
WebhookPlatformError = RailhookError

__all__ = [
    "Railhook",
    "RailhookError",
    "WebhookPlatform",
    "WebhookPlatformError",
    "AuthenticationError",
    "RateLimitError",
    "ValidationError",
    "NotFoundError",
    "verify_signature",
    "verify_standard_webhook",
    "construct_event",
    "generate_signature",
    "Event",
    "EventResponse",
    "Endpoint",
    "EndpointCreateParams",
    "EndpointUpdateParams",
    "Subscription",
    "SubscriptionCreateParams",
    "Delivery",
    "DeliveryAttempt",
    "DeliveryListParams",
    "DeliveryStatus",
    "PaginatedResponse",
    "EndpointTestResult",
    "RateLimitInfo",
    "WebhookEvent",
    "IncomingSource",
    "IncomingSourceCreateParams",
    "IncomingSourceUpdateParams",
    "IncomingDestination",
    "IncomingDestinationCreateParams",
    "IncomingEvent",
    "IncomingEventListParams",
    "IncomingForwardAttempt",
    "ReplayEventResponse",
]
