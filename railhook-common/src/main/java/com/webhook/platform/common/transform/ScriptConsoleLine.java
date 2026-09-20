package com.webhook.platform.common.transform;

/**
 * One {@code console.*} call a script made, in the order it made it.
 *
 * @param level one of {@code log}, {@code info}, {@code warn}, {@code error}, {@code debug}
 * @param message the arguments, already formatted by the guest
 */
public record ScriptConsoleLine(String level, String message) {
}
