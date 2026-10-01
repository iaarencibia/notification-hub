package io.github.iaarencibia.notifications.application.service.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.iaarencibia.notifications.application.port.out.AbandonedClaims;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reaper's one decision: how long a claim may stand before its owner is presumed dead. Too
 * short, and work is taken from an instance that is merely slow, producing duplicate delivery.
 */
class ReleaseAbandonedClaimsServiceTest {

    private final RecordingClaims claims = new RecordingClaims();

    @Test
    @DisplayName("releases against the threshold it was built with, and not some other duration")
    void passesTheConfiguredThreshold() {
        // Whether the right duration reaches this constructor is wiring's concern, covered by
        // DispatchWiringTest.
        new ReleaseAbandonedClaimsService(claims, Duration.ofMinutes(5)).releaseAbandonedClaims();

        assertThat(claims.releasedOlderThan).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("reports how many claims were released")
    void reportsWhatWasReleased() {
        claims.willRelease(3);

        int released =
                new ReleaseAbandonedClaimsService(claims, Duration.ofMinutes(5))
                        .releaseAbandonedClaims();

        assertThat(released).isEqualTo(3);
    }

    @Test
    @DisplayName("refuses a threshold of zero, which would release every claim as it is taken")
    void refusesANonPositiveThreshold() {
        // Rows would return to PENDING mid-delivery, so every notification would be sent as many
        // times as it is claimed.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, Duration.ZERO))
                .withMessageContaining("staleClaimTimeout");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, Duration.ofSeconds(-1)))
                .withMessageContaining("staleClaimTimeout");
    }

    @Test
    @DisplayName("refuses to be built without a threshold at all")
    void refusesAMissingThreshold() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReleaseAbandonedClaimsService(claims, null));
    }

    /** Records the threshold it was asked to release against. */
    private static final class RecordingClaims implements AbandonedClaims {

        private Duration releasedOlderThan;
        private int released;

        void willRelease(int count) {
            this.released = count;
        }

        @Override
        public int release(Duration staleAfter) {
            this.releasedOlderThan = staleAfter;
            return released;
        }
    }
}
