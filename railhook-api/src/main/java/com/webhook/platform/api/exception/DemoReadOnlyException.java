package com.webhook.platform.api.exception;

/** Its own error code so the dashboard can say "this is the demo" rather than "you lack a role". */
public class DemoReadOnlyException extends ForbiddenException {

    public static final String CODE = "demo_read_only";

    public DemoReadOnlyException() {
        super("This is a read-only demo. Create a free account to make changes.");
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
