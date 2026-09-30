package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.domain.Notification;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rows, and nothing else. Every statement this application issues against the notification
 * table starts here, and each call carries its own short transaction.
 *
 * <p>Per call, not per use case: the recovery from a taken idempotency key depends on the failed
 * insert being finished and rolled back before the lookup that follows it runs.
 */
@Repository
class NotificationJpaStore {

    /**
     * Ordered by the claimable index exactly, so the rows arrive sorted and no sort is needed.
     *
     * <p>{@code SKIP LOCKED} is what makes several instances useful, not merely safe: whoever
     * arrives second passes over what is already taken and claims different work, instead of
     * queueing behind it.
     */
    private static final String CLAIM_DUE =
            """
            SELECT * FROM notification
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY priority_rank, next_attempt_at, created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """;

    /**
     * One statement for the whole sweep. The version bump is what makes an instance returning from
     * a long delivery lose to the row it no longer owns, instead of writing over whoever took it
     * next.
     */
    private static final String RELEASE_STALE_CLAIMS =
            """
            UPDATE notification
               SET status = 'PENDING', claimed_at = NULL, claimed_by = NULL,
                   updated_at = now(), version = version + 1
             WHERE status = 'DISPATCHING'
               AND claimed_at < now() - make_interval(secs => :seconds)
            """;

    private static final String RECORD_LIFECYCLE =
            """
            UPDATE NotificationEntity n
               SET n.status = :status, n.attempts = :attempts,
                   n.nextAttemptAt = :nextAttemptAt, n.lastError = :lastError,
                   n.claimedAt = :claimedAt, n.claimedBy = :claimedBy, n.sentAt = :sentAt,
                   n.updatedAt = :updatedAt, n.version = n.version + 1
             WHERE n.id = :id AND n.version = :version
            """;

    private final EntityManager entityManager;

    NotificationJpaStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    void insert(NotificationEntity entity) {
        entityManager.persist(entity);
        // Flushed on purpose, not left to the commit: the unique index rejects a repeated key,
        // and the caller has to see that rejection while it can still do something about it.
        entityManager.flush();
    }

    /**
     * Takes ownership of up to {@code batchSize} due notifications, and returns the rows as they
     * now stand.
     *
     * <p>The transition itself is the aggregate's: each row is rehydrated, asked to claim, and
     * written back, rather than being decided by the UPDATE statement -- which would add a second
     * lifecycle move that skips the domain.
     */
    @Transactional
    List<NotificationEntity> claimDue(int batchSize, String claimedBy) {
        Instant now = databaseNow();
        List<NotificationEntity> due = dueFor(now, batchSize);

        for (NotificationEntity entity : due) {
            Notification notification = NotificationMapper.toDomain(entity);
            notification.claim(now, claimedBy);
            NotificationMapper.applyLifecycle(entity, notification);
        }

        // Returned still managed: the commit that ends this transaction flushes the claims and
        // the provider writes each new version onto the instance, so the caller reads its own
        // claim's version.
        return due;
    }

    /**
     * Writes the result of one delivery: the attempt, and the state it left the notification in.
     *
     * @return whether the claim still held; when it did not, nothing was written
     */
    @Transactional
    boolean recordAttempt(
            Notification notification, long claimedVersion, NotificationAttemptEntity attempt) {
        int updated =
                entityManager
                        .createQuery(RECORD_LIFECYCLE)
                        .setParameter("status", notification.status())
                        .setParameter("attempts", notification.attempts())
                        .setParameter("nextAttemptAt", notification.nextAttemptAt())
                        .setParameter("lastError", notification.lastError())
                        .setParameter("claimedAt", notification.claimedAt())
                        .setParameter("claimedBy", notification.claimedBy())
                        .setParameter("sentAt", notification.sentAt())
                        .setParameter("updatedAt", notification.updatedAt())
                        .setParameter("id", notification.id())
                        .setParameter("version", claimedVersion)
                        .executeUpdate();
        if (updated == 0) {
            return false;
        }

        entityManager.persist(attempt);
        entityManager.flush();
        return true;
    }

    /**
     * @param staleAfter how long a claim may stand before its owner is assumed dead
     * @return how many claims were released
     */
    @Transactional
    int releaseStaleClaims(Duration staleAfter) {
        return entityManager
                .createNativeQuery(RELEASE_STALE_CLAIMS)
                .setParameter("seconds", (double) staleAfter.toMillis() / 1000)
                .executeUpdate();
    }

    /**
     * The one clock. Called from {@link #claimDue} it runs inside that transaction, where
     * PostgreSQL holds {@code now()} fixed, so the instant deciding what is due is the same one
     * written onto every row claimed.
     */
    @Transactional(readOnly = true)
    Instant databaseNow() {
        return (Instant)
                entityManager.createNativeQuery("SELECT now()", Instant.class).getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private List<NotificationEntity> dueFor(Instant now, int batchSize) {
        return entityManager
                .createNativeQuery(CLAIM_DUE, NotificationEntity.class)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }

    @Transactional(readOnly = true)
    Optional<NotificationEntity> findByIdempotencyKey(String idempotencyKey) {
        return entityManager
                .createQuery(
                        "SELECT n FROM NotificationEntity n WHERE n.idempotencyKey = :key",
                        NotificationEntity.class)
                .setParameter("key", idempotencyKey)
                .getResultStream()
                .findFirst();
    }
}
