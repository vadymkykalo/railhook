<?php

declare(strict_types=1);

namespace Railhook\Exception;

class AuthenticationException extends RailhookException
{
    public function __construct(string $message = 'Invalid API key')
    {
        parent::__construct($message, 401, 'authentication_error');
    }
}
