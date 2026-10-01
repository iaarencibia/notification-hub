package io.github.iaarencibia.notifications.application.port.in.release;

/**
 * The recovery pass: returns to PENDING the claims of instances that never came back, since {@code
 * DISPATCHING} is a lock held outside any transaction.
 */
public interface ReleaseAbandonedClaimsUseCase {

    /**
     * @return how many claims were released, zero on a healthy system
     */
    int releaseAbandonedClaims();
}
