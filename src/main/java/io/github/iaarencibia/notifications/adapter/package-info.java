/**
 * Adapters: every piece of code that speaks a specific technology. {@code in} drives the
 * application (REST intake, dispatch scheduler); {@code out} is driven by it (JPA persistence,
 * notification channels). The only layer allowed to change when the runtime changes.
 */
package io.github.iaarencibia.notifications.adapter;
