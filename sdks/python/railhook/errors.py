"""Error classes for Railhook SDK."""

from typing import Dict, Optional
from .types import RateLimitInfo


class RailhookError(Exception):
    """Base exception for Railhook SDK."""

    def __init__(
        self, message: str, status: int = 0, code: Optional[str] = None
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.code = code

    def __str__(self) -> str:
        return f"{self.__class__.__name__}: {self.message} (status={self.status})"


class AuthenticationError(RailhookError):
    """Raised when API key is invalid or missing."""

    def __init__(self, message: str = "Invalid API key") -> None:
        super().__init__(message, status=401, code="authentication_error")


class RateLimitError(RailhookError):
    """Raised when rate limit is exceeded."""

    def __init__(self, message: str, rate_limit_info: RateLimitInfo) -> None:
        super().__init__(message, status=429, code="rate_limit_exceeded")
        self.rate_limit_info = rate_limit_info

    @property
    def retry_after_ms(self) -> int:
        """Milliseconds to wait before retrying.

        ``rate_limit_info.reset`` is the raw ``X-RateLimit-Reset`` header, which
        the API sends as a Unix timestamp in **seconds**; subtracting a
        millisecond clock from it directly always yields 0.
        """
        import time
        now_ms = int(time.time() * 1000)
        return max(0, self.rate_limit_info.reset * 1000 - now_ms)


class ValidationError(RailhookError):
    """Raised when request validation fails."""

    def __init__(
        self, message: str, field_errors: Optional[Dict[str, str]] = None
    ) -> None:
        super().__init__(message, status=400, code="validation_error")
        self.field_errors = field_errors or {}


class NotFoundError(RailhookError):
    """Raised when resource is not found."""

    def __init__(self, message: str = "Resource not found") -> None:
        super().__init__(message, status=404, code="not_found")
