<?php

declare(strict_types=1);

namespace Railhook\Exception;

class NotFoundException extends RailhookException
{
    public function __construct(string $message = 'Resource not found')
    {
        parent::__construct($message, 404, 'not_found');
    }
}
