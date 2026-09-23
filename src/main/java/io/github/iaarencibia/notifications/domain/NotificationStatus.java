package io.github.iaarencibia.notifications.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lifecycle of a notification, and the only place the legal moves between its states are
 * declared. {@code DISPATCHING} is a persisted logical lock, replacing the row lock so no
 * transaction stays open across a network call; the reaper releases it if its owner dies.
 */
public enum NotificationStatus {
    PENDING,

    /**
     * Claimed by an instance, which owns it until it records a result or the reaper reclaims it.
     */
    DISPATCHING,

    SENT,

    /** Permanently rejected, or out of attempts. Terminal. */
    FAILED;

    /**
     * {@code DISPATCHING -> PENDING} covers two moves that look identical here: a scheduled retry
     * and a claim released by the reaper. The attempt history tells them apart: a retry leaves a
     * row in {@code notification_attempt}, a reaped claim does not.
     */
    private static final Map<NotificationStatus, Set<NotificationStatus>> TRANSITIONS =
            Map.of(
                    PENDING, Set.of(DISPATCHING),
                    DISPATCHING, Set.of(SENT, FAILED, PENDING),
                    SENT, Set.of(),
                    FAILED, Set.of());

    public boolean canTransitionTo(NotificationStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return TRANSITIONS.get(this).contains(target);
    }

    /**
     * Derived from the transition graph rather than declared separately, so a state cannot be
     * terminal and still have a way out.
     */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
