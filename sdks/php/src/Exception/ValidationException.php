<?php

declare(strict_types=1);

namespace Railhook\Exception;

class ValidationException extends RailhookException
{
    private array $fieldErrors;

    public function __construct(string $message, array $fieldErrors = [])
    {
        parent::__construct($message, 400, 'validation_error');
        $this->fieldErrors = $fieldErrors;
    }

    public function getFieldErrors(): array
    {
        return $this->fieldErrors;
    }
}
