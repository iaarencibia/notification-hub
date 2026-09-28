package io.github.iaarencibia.notifications.adapter.out.persistence;

import io.github.iaarencibia.notifications.application.port.out.IdempotencyKeyAlreadyUsedException;
import io.github.iaarencibia.notifications.application.port.out.NotificationRepository;
import io.github.iaarencibia.notifications.domain.Notification;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The outbound side of persistence: the only class that knows both the aggregate and the table.
 *
 * <p>Sits outside {@link NotificationJpaStore} rather than doing the work itself: Spring turns the
 * provider's constraint violation into {@code DataIntegrityViolationException} in the interceptor
 * around a {@code @Repository} bean, on the way out of it, so code written inside that bean cannot
 * catch the translated exception.
 *
 * <p>What that buys is a port whose contract is the application's own: the layer above hears "that
 * key is taken," not "an integrity constraint failed."
 */
@Component
class NotificationPersistenceAdapter implements NotificationRepository {

    private final NotificationJpaStore store;

    NotificationPersistenceAdapter(NotificationJpaStore store) {
        this.store = store;
    }

    @Override
    public void save(Notification notification) {
        try {
            store.insert(NotificationMapper.toEntity(notification));
        } catch (DataIntegrityViolationException violation) {
            if (notification.idempotencyKey() == null) {
                // With no key the partial unique index does not cover this row at all, so
                // whatever was violated is not something the caller can recover from.
                throw violation;
            }
            // Narrowed by the key, not by the constraint's name -- a literal with nothing
            // keeping it honest. Safe here: every other constraint is satisfied by construction
            // for a row submit() has just built.
            throw new IdempotencyKeyAlreadyUsedException(notification.idempotencyKey(), violation);
        }
    }

    @Override
    public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
        // A null would compare as SQL null and quietly match nothing, reporting "no such
        // notification" for what is really a caller that forgot to pass the key.
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        return store.findByIdempotencyKey(idempotencyKey).map(NotificationMapper::toDomain);
    }
}
