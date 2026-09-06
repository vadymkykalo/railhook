"""Type definitions for Webhook Platform SDK."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from enum import Enum


class DeliveryStatus(str, Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    DLQ = "DLQ"


@dataclass
class Event:
    type: str
    data: Dict[str, Any]


@dataclass
class EventResponse:
    event_id: str
    type: str
    created_at: str
    deliveries_created: int
    #: Schema-validation errors this event was accepted despite, when the project has schema
    #: validation on with the WARN policy. Empty when the payload matched or validation is off.
    schema_warnings: List[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "EventResponse":
        return cls(
            event_id=data["eventId"],
            type=data["type"],
            created_at=data["createdAt"],
            deliveries_created=data["deliveriesCreated"],
            schema_warnings=data.get("schemaWarnings") or [],
        )


@dataclass
class Endpoint:
    id: str
    url: str
    secret: str
    enabled: bool
    created_at: str
    description: Optional[str] = None
    rate_limit_per_second: Optional[int] = None
    project_id: Optional[str] = None
    allowed_source_ips: Optional[str] = None
    mtls_enabled: bool = False
    verification_status: Optional[str] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Endpoint":
        return cls(
            id=data["id"],
            url=data["url"],
            secret=data.get("secret", ""),
            enabled=data["enabled"],
            created_at=data["createdAt"],
            description=data.get("description"),
            rate_limit_per_second=data.get("rateLimitPerSecond"),
            project_id=data.get("projectId"),
            allowed_source_ips=data.get("allowedSourceIps"),
            mtls_enabled=data.get("mtlsEnabled") or False,
            verification_status=data.get("verificationStatus"),
            updated_at=data.get("updatedAt"),
        )


@dataclass
class EndpointCreateParams:
    url: str
    description: Optional[str] = None
    enabled: bool = True
    rate_limit_per_second: Optional[int] = None
    secret: Optional[str] = None
    allowed_source_ips: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {"url": self.url, "enabled": self.enabled}
        if self.description:
            result["description"] = self.description
        if self.rate_limit_per_second:
            result["rateLimitPerSecond"] = self.rate_limit_per_second
        if self.secret is not None:
            result["secret"] = self.secret
        if self.allowed_source_ips is not None:
            result["allowedSourceIps"] = self.allowed_source_ips
        return result


@dataclass
class EndpointUpdateParams:
    url: Optional[str] = None
    description: Optional[str] = None
    enabled: Optional[bool] = None
    rate_limit_per_second: Optional[int] = None
    secret: Optional[str] = None
    allowed_source_ips: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        if self.url is not None:
            result["url"] = self.url
        if self.description is not None:
            result["description"] = self.description
        if self.enabled is not None:
            result["enabled"] = self.enabled
        if self.rate_limit_per_second is not None:
            result["rateLimitPerSecond"] = self.rate_limit_per_second
        if self.secret is not None:
            result["secret"] = self.secret
        if self.allowed_source_ips is not None:
            result["allowedSourceIps"] = self.allowed_source_ips
        return result


@dataclass
class Subscription:
    id: str
    endpoint_id: str
    event_type: str
    enabled: bool
    created_at: str
    project_id: Optional[str] = None
    ordering_enabled: bool = False
    max_attempts: int = 7
    timeout_seconds: int = 30
    retry_delays: Optional[str] = None
    payload_template: Optional[str] = None
    custom_headers: Optional[str] = None
    transformation_id: Optional[str] = None
    transformation_name: Optional[str] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Subscription":
        return cls(
            id=data["id"],
            endpoint_id=data["endpointId"],
            event_type=data["eventType"],
            enabled=data["enabled"],
            created_at=data["createdAt"],
            project_id=data.get("projectId"),
            ordering_enabled=data.get("orderingEnabled", False),
            max_attempts=data.get("maxAttempts", 7),
            timeout_seconds=data.get("timeoutSeconds", 30),
            retry_delays=data.get("retryDelays"),
            payload_template=data.get("payloadTemplate"),
            custom_headers=data.get("customHeaders"),
            transformation_id=data.get("transformationId"),
            transformation_name=data.get("transformationName"),
            updated_at=data.get("updatedAt"),
        )


@dataclass
class SubscriptionCreateParams:
    endpoint_id: str
    event_type: str
    enabled: bool = True
    ordering_enabled: Optional[bool] = None
    max_attempts: Optional[int] = None
    timeout_seconds: Optional[int] = None
    retry_delays: Optional[str] = None
    payload_template: Optional[str] = None
    custom_headers: Optional[str] = None
    transformation_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "endpointId": self.endpoint_id,
            "eventType": self.event_type,
            "enabled": self.enabled,
        }
        if self.ordering_enabled is not None:
            result["orderingEnabled"] = self.ordering_enabled
        if self.max_attempts is not None:
            result["maxAttempts"] = self.max_attempts
        if self.timeout_seconds is not None:
            result["timeoutSeconds"] = self.timeout_seconds
        if self.retry_delays is not None:
            result["retryDelays"] = self.retry_delays
        if self.payload_template is not None:
            result["payloadTemplate"] = self.payload_template
        if self.custom_headers is not None:
            result["customHeaders"] = self.custom_headers
        if self.transformation_id is not None:
            result["transformationId"] = self.transformation_id
        return result


@dataclass
class Delivery:
    id: str
    event_id: str
    endpoint_id: str
    status: DeliveryStatus
    attempt_count: int
    max_attempts: int
    created_at: str
    subscription_id: Optional[str] = None
    #: The API field is ``nextRetryAt``.
    next_retry_at: Optional[str] = None
    last_attempt_at: Optional[str] = None
    succeeded_at: Optional[str] = None
    failed_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Delivery":
        return cls(
            id=data["id"],
            event_id=data["eventId"],
            endpoint_id=data["endpointId"],
            status=DeliveryStatus(data["status"]),
            attempt_count=data["attemptCount"],
            max_attempts=data["maxAttempts"],
            created_at=data["createdAt"],
            subscription_id=data.get("subscriptionId"),
            next_retry_at=data.get("nextRetryAt"),
            last_attempt_at=data.get("lastAttemptAt"),
            succeeded_at=data.get("succeededAt"),
            failed_at=data.get("failedAt"),
        )


@dataclass
class DeliveryAttempt:
    id: str
    attempt_number: int
    #: When the attempt was recorded (API field ``createdAt``).
    created_at: str
    delivery_id: Optional[str] = None
    #: JSON object, serialized as a string, of the headers that were sent.
    request_headers: Optional[str] = None
    request_body: Optional[str] = None
    http_status_code: Optional[int] = None
    #: JSON object, serialized as a string, of the headers that came back.
    response_headers: Optional[str] = None
    response_body: Optional[str] = None
    error_message: Optional[str] = None
    #: Wall-clock duration of the HTTP request, in milliseconds.
    duration_ms: Optional[int] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DeliveryAttempt":
        return cls(
            id=data["id"],
            attempt_number=data["attemptNumber"],
            created_at=data["createdAt"],
            delivery_id=data.get("deliveryId"),
            request_headers=data.get("requestHeaders"),
            request_body=data.get("requestBody"),
            http_status_code=data.get("httpStatusCode"),
            response_headers=data.get("responseHeaders"),
            response_body=data.get("responseBody"),
            error_message=data.get("errorMessage"),
            duration_ms=data.get("durationMs"),
        )


@dataclass
class DeliveryListParams:
    status: Optional[DeliveryStatus] = None
    endpoint_id: Optional[str] = None
    from_date: Optional[str] = None
    to_date: Optional[str] = None
    page: int = 0
    size: int = 20

    def to_params(self) -> Dict[str, Any]:
        params: Dict[str, Any] = {"page": self.page, "size": self.size}
        if self.status:
            params["status"] = self.status.value
        if self.endpoint_id:
            params["endpointId"] = self.endpoint_id
        if self.from_date:
            params["fromDate"] = self.from_date
        if self.to_date:
            params["toDate"] = self.to_date
        return params


@dataclass
class PaginatedResponse:
    content: List[Any]
    total_elements: int
    total_pages: int
    #: Page size requested.
    size: int
    #: Zero-based index of this page.
    number: int
    number_of_elements: Optional[int] = None
    first: Optional[bool] = None
    last: Optional[bool] = None
    empty: Optional[bool] = None

    def __iter__(self):
        """Iterating a page walks its ``content``, not its envelope keys."""
        return iter(self.content)

    def __len__(self) -> int:
        return len(self.content)

    @classmethod
    def from_dict(cls, data: Dict[str, Any], item_cls: Any = None) -> "PaginatedResponse":
        if item_cls is None:
            item_cls = Delivery
        return cls(
            content=[item_cls.from_dict(d) for d in data["content"]],
            total_elements=data["totalElements"],
            total_pages=data["totalPages"],
            size=data["size"],
            number=data["number"],
            number_of_elements=data.get("numberOfElements"),
            first=data.get("first"),
            last=data.get("last"),
            empty=data.get("empty"),
        )


@dataclass
class EndpointTestResult:
    success: bool
    latency_ms: int
    http_status_code: Optional[int] = None
    response_body: Optional[str] = None
    error_message: Optional[str] = None
    #: Human-readable summary, e.g. "Endpoint returned non-2xx status".
    message: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "EndpointTestResult":
        return cls(
            success=data["success"],
            latency_ms=data["latencyMs"],
            http_status_code=data.get("httpStatusCode"),
            response_body=data.get("responseBody"),
            error_message=data.get("errorMessage"),
            message=data.get("message"),
        )


@dataclass
class RateLimitInfo:
    limit: int
    remaining: int
    #: Unix timestamp in **seconds** at which the window resets — the raw value
    #: of the ``X-RateLimit-Reset`` header, which the API sends in seconds.
    reset: int


@dataclass
class WebhookEvent:
    event_id: str
    delivery_id: str
    timestamp: int
    type: str
    data: Dict[str, Any]


# ── Incoming Webhooks ──


class ProviderType(str, Enum):
    GENERIC = "GENERIC"
    STRIPE = "STRIPE"
    GITHUB = "GITHUB"
    TWILIO = "TWILIO"
    SHOPIFY = "SHOPIFY"
    HUBSPOT = "HUBSPOT"
    SLACK = "SLACK"
    CUSTOM = "CUSTOM"


class VerificationMode(str, Enum):
    NONE = "NONE"
    HMAC_GENERIC = "HMAC_GENERIC"


class IncomingSourceStatus(str, Enum):
    ACTIVE = "ACTIVE"
    DISABLED = "DISABLED"


class IncomingAuthType(str, Enum):
    NONE = "NONE"
    BEARER = "BEARER"
    BASIC = "BASIC"
    CUSTOM_HEADER = "CUSTOM_HEADER"


@dataclass
class IncomingSource:
    id: str
    project_id: str
    name: str
    slug: str
    provider_type: str
    status: str
    ingress_path_token: str
    ingress_url: str
    verification_mode: str
    hmac_secret_configured: bool
    created_at: str
    hmac_header_name: Optional[str] = None
    hmac_signature_prefix: Optional[str] = None
    rate_limit_per_second: Optional[int] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "IncomingSource":
        return cls(
            id=data["id"],
            project_id=data["projectId"],
            name=data["name"],
            slug=data["slug"],
            provider_type=data["providerType"],
            status=data["status"],
            ingress_path_token=data["ingressPathToken"],
            ingress_url=data["ingressUrl"],
            verification_mode=data["verificationMode"],
            hmac_secret_configured=data.get("hmacSecretConfigured", False),
            created_at=data["createdAt"],
            hmac_header_name=data.get("hmacHeaderName"),
            hmac_signature_prefix=data.get("hmacSignaturePrefix"),
            rate_limit_per_second=data.get("rateLimitPerSecond"),
            updated_at=data.get("updatedAt"),
        )


@dataclass
class IncomingSourceCreateParams:
    name: str
    slug: Optional[str] = None
    provider_type: Optional[str] = None
    verification_mode: Optional[str] = None
    hmac_secret: Optional[str] = None
    hmac_header_name: Optional[str] = None
    hmac_signature_prefix: Optional[str] = None
    rate_limit_per_second: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {"name": self.name}
        if self.slug is not None:
            result["slug"] = self.slug
        if self.provider_type is not None:
            result["providerType"] = self.provider_type
        if self.verification_mode is not None:
            result["verificationMode"] = self.verification_mode
        if self.hmac_secret is not None:
            result["hmacSecret"] = self.hmac_secret
        if self.hmac_header_name is not None:
            result["hmacHeaderName"] = self.hmac_header_name
        if self.hmac_signature_prefix is not None:
            result["hmacSignaturePrefix"] = self.hmac_signature_prefix
        if self.rate_limit_per_second is not None:
            result["rateLimitPerSecond"] = self.rate_limit_per_second
        return result


@dataclass
class IncomingSourceUpdateParams:
    name: Optional[str] = None
    slug: Optional[str] = None
    provider_type: Optional[str] = None
    status: Optional[str] = None
    verification_mode: Optional[str] = None
    hmac_secret: Optional[str] = None
    hmac_header_name: Optional[str] = None
    hmac_signature_prefix: Optional[str] = None
    rate_limit_per_second: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        if self.name is not None:
            result["name"] = self.name
        if self.slug is not None:
            result["slug"] = self.slug
        if self.provider_type is not None:
            result["providerType"] = self.provider_type
        if self.status is not None:
            result["status"] = self.status
        if self.verification_mode is not None:
            result["verificationMode"] = self.verification_mode
        if self.hmac_secret is not None:
            result["hmacSecret"] = self.hmac_secret
        if self.hmac_header_name is not None:
            result["hmacHeaderName"] = self.hmac_header_name
        if self.hmac_signature_prefix is not None:
            result["hmacSignaturePrefix"] = self.hmac_signature_prefix
        if self.rate_limit_per_second is not None:
            result["rateLimitPerSecond"] = self.rate_limit_per_second
        return result


@dataclass
class IncomingDestination:
    id: str
    incoming_source_id: str
    url: str
    auth_type: str
    auth_configured: bool
    enabled: bool
    max_attempts: int
    timeout_seconds: int
    created_at: str
    custom_headers_json: Optional[str] = None
    retry_delays: Optional[str] = None
    payload_transform: Optional[str] = None
    transformation_id: Optional[str] = None
    transformation_name: Optional[str] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "IncomingDestination":
        return cls(
            id=data["id"],
            incoming_source_id=data["incomingSourceId"],
            url=data["url"],
            auth_type=data.get("authType", "NONE"),
            auth_configured=data.get("authConfigured", False),
            enabled=data.get("enabled", True),
            max_attempts=data.get("maxAttempts", 5),
            timeout_seconds=data.get("timeoutSeconds", 30),
            created_at=data["createdAt"],
            custom_headers_json=data.get("customHeadersJson"),
            retry_delays=data.get("retryDelays"),
            payload_transform=data.get("payloadTransform"),
            transformation_id=data.get("transformationId"),
            transformation_name=data.get("transformationName"),
            updated_at=data.get("updatedAt"),
        )


@dataclass
class IncomingDestinationCreateParams:
    url: str
    auth_type: Optional[str] = None
    auth_config: Optional[str] = None
    custom_headers_json: Optional[str] = None
    enabled: Optional[bool] = None
    max_attempts: Optional[int] = None
    timeout_seconds: Optional[int] = None
    retry_delays: Optional[str] = None
    payload_transform: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {"url": self.url}
        if self.auth_type is not None:
            result["authType"] = self.auth_type
        if self.auth_config is not None:
            result["authConfig"] = self.auth_config
        if self.custom_headers_json is not None:
            result["customHeadersJson"] = self.custom_headers_json
        if self.enabled is not None:
            result["enabled"] = self.enabled
        if self.max_attempts is not None:
            result["maxAttempts"] = self.max_attempts
        if self.timeout_seconds is not None:
            result["timeoutSeconds"] = self.timeout_seconds
        if self.retry_delays is not None:
            result["retryDelays"] = self.retry_delays
        if self.payload_transform is not None:
            result["payloadTransform"] = self.payload_transform
        return result


@dataclass
class IncomingEvent:
    id: str
    incoming_source_id: str
    source_name: str
    request_id: str
    method: str
    path: str
    received_at: str
    query_params: Optional[str] = None
    headers_json: Optional[str] = None
    body_raw: Optional[str] = None
    body_sha256: Optional[str] = None
    content_type: Optional[str] = None
    client_ip: Optional[str] = None
    user_agent: Optional[str] = None
    verified: Optional[bool] = None
    verification_error: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "IncomingEvent":
        return cls(
            id=data["id"],
            incoming_source_id=data["incomingSourceId"],
            source_name=data.get("sourceName", ""),
            request_id=data["requestId"],
            method=data["method"],
            path=data["path"],
            received_at=data["receivedAt"],
            query_params=data.get("queryParams"),
            headers_json=data.get("headersJson"),
            body_raw=data.get("bodyRaw"),
            body_sha256=data.get("bodySha256"),
            content_type=data.get("contentType"),
            client_ip=data.get("clientIp"),
            user_agent=data.get("userAgent"),
            verified=data.get("verified"),
            verification_error=data.get("verificationError"),
        )


@dataclass
class IncomingEventListParams:
    source_id: Optional[str] = None
    page: int = 0
    size: int = 20

    def to_params(self) -> Dict[str, Any]:
        params: Dict[str, Any] = {"page": self.page, "size": self.size}
        if self.source_id:
            params["sourceId"] = self.source_id
        return params


@dataclass
class IncomingForwardAttempt:
    id: str
    incoming_event_id: str
    destination_id: str
    #: Null on the wire when the destination row has since been removed.
    destination_url: Optional[str]
    attempt_number: int
    status: str
    created_at: str
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    response_code: Optional[int] = None
    response_headers_json: Optional[str] = None
    response_body_snippet: Optional[str] = None
    error_message: Optional[str] = None
    next_retry_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "IncomingForwardAttempt":
        return cls(
            id=data["id"],
            incoming_event_id=data["incomingEventId"],
            destination_id=data["destinationId"],
            destination_url=data.get("destinationUrl"),
            attempt_number=data["attemptNumber"],
            status=data["status"],
            created_at=data["createdAt"],
            started_at=data.get("startedAt"),
            finished_at=data.get("finishedAt"),
            response_code=data.get("responseCode"),
            response_headers_json=data.get("responseHeadersJson"),
            response_body_snippet=data.get("responseBodySnippet"),
            error_message=data.get("errorMessage"),
            next_retry_at=data.get("nextRetryAt"),
        )


@dataclass
class ReplayEventResponse:
    status: str
    event_id: str
    destinations_count: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ReplayEventResponse":
        return cls(
            status=data["status"],
            event_id=data["eventId"],
            destinations_count=data["destinationsCount"],
        )
