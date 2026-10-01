package io.github.iaarencibia.notifications.application.service.release;

import io.github.iaarencibia.notifications.application.port.in.release.ReleaseAbandonedClaimsUseCase;
import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import java.time.Duration;
import java.util.Objects;

/** The reaper: releases claims whose owner never came back. */
public final class ReleaseAbandonedClaimsService implements ReleaseAbandonedClaimsUseCase {

    private final AbandonedClaims abandonedClaims;
    private final Duration staleClaimTimeout;

    /**
     * @param staleClaimTimeout too short, and work is taken from a merely slow instance, producing
     *     duplicate delivery
     */
    public ReleaseAbandonedClaimsService(
            AbandonedClaims abandonedClaims, Duration staleClaimTimeout) {
        this.abandonedClaims =
                Objects.requireNonNull(abandonedClaims, "abandonedClaims must not be null");
        Objects.requireNonNull(staleClaimTimeout, "staleClaimTimeout must not be null");
        if (staleClaimTimeout.isZero() || staleClaimTimeout.isNegative()) {
            throw new IllegalArgumentException("staleClaimTimeout must be positive");
        }
        this.staleClaimTimeout = staleClaimTimeout;
    }

    @Override
    public int releaseAbandonedClaims() {
        return abandonedClaims.release(staleClaimTimeout);
    }
}
