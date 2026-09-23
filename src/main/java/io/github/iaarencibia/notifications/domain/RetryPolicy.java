package io.github.iaarencibia.notifications.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Decides how long a notification waits before its next attempt. The random source is a constructor
 * argument, not {@code Math.random()}, so a fixed source produces deterministic delays for tests.
 */
public final class RetryPolicy {

    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;
    private final double jitter;
    private final DoubleSupplier randomSource;

    public RetryPolicy(
            Duration initialBackoff, double multiplier, Duration maxBackoff, double jitter) {
        this(
                initialBackoff,
                multiplier,
                maxBackoff,
                jitter,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /**
     * @param maxBackoff ceiling every delay is capped at, including a destination's hint
     * @param randomSource must yield values in {@code [0, 1)}
     */
    public RetryPolicy(
            Duration initialBackoff,
            double multiplier,
            Duration maxBackoff,
            double jitter,
            DoubleSupplier randomSource) {

        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff must not be null");
        this.randomSource = Objects.requireNonNull(randomSource, "randomSource must not be null");

        // Milliseconds, not merely "positive": below one would truncate to zero, letting the
        // policy validate cleanly while producing no backoff at all.
        if (initialBackoff.toMillis() < 1) {
            throw new IllegalArgumentException("initialBackoff must be at least 1 millisecond");
        }
        // Below 1 the delay would shrink with every failure, hammering an already-failing
        // destination.
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be below initialBackoff");
        }
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
        }

        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
        this.jitter = jitter;
    }

    /**
     * {@code retryAfterHint} when the destination supplied one, otherwise the geometric schedule;
     * capped at {@code maxBackoff} since the hint is untrusted input, then spread by jitter. {@code
     * Duration.ZERO} means retry immediately, unlike {@code null}.
     */
    public Duration delayFor(int attemptNumber, Duration retryAfterHint) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        if (retryAfterHint != null && retryAfterHint.isNegative()) {
            throw new IllegalArgumentException("retryAfterHint must not be negative");
        }

        Duration base = retryAfterHint != null ? retryAfterHint : scheduledBackoff(attemptNumber);
        if (base.compareTo(maxBackoff) > 0) {
            base = maxBackoff;
        }
        return applyJitter(base);
    }

    /**
     * Capped before the conversion back to a {@code Duration}, so that a high attempt number cannot
     * overflow the multiplication.
     */
    private Duration scheduledBackoff(int attemptNumber) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, attemptNumber - 1);
        if (millis >= maxBackoff.toMillis()) {
            return maxBackoff;
        }
        return Duration.ofMillis((long) millis);
    }

    /** Rounded rather than truncated, so that 5000 x 1.12 is 5600 and not 5599. */
    private Duration applyJitter(Duration base) {
        double factor = 1.0 + randomSource.getAsDouble() * jitter;
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
