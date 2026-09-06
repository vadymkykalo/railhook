"""Official Python SDK for Hookflow."""

from .client import Hookflow
from .errors import (
    HookflowError,
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

__version__ = "2.11.0"

# Backward-compatible aliases
WebhookPlatform = Hookflow
WebhookPlatformError = HookflowError

__all__ = [
    "Hookflow",
    "HookflowError",
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
