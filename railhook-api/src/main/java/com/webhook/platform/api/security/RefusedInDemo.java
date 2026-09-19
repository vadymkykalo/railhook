package com.webhook.platform.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A read the public demo may not make, although it is a GET.
 *
 * <p>The demo is for looking at the product on screen. A bulk export is a download of everything
 * at once, for anyone who asks — cheap to request, expensive to serve, and nothing a visitor
 * needs to see the product work.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RefusedInDemo {
}
