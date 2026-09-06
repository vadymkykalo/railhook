<?php

declare(strict_types=1);

namespace Railhook\Exception;

class RateLimitException extends RailhookException
{
    private array $rateLimitInfo;

    public function __construct(string $message, array $rateLimitInfo)
    {
        parent::__construct($message, 429, 'rate_limit_exceeded');
        $this->rateLimitInfo = $rateLimitInfo;
    }

    public function getRateLimitInfo(): array
    {
        return $this->rateLimitInfo;
    }

    /**
     * Milliseconds to wait before retrying.
     *
     * `reset` is the raw X-RateLimit-Reset header, which the API sends as a
     * Unix timestamp in **seconds**; subtracting a millisecond clock from it
     * directly always yields 0.
     */
    public function getRetryAfterMs(): int
    {
        $now = (int) (microtime(true) * 1000);
        return max(0, $this->rateLimitInfo['reset'] * 1000 - $now);
    }
}
