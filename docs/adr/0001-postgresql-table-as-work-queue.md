# 1. A PostgreSQL table as the work queue

- Status: Accepted
- Date: 2026-10-02

## Context and problem

Notification Hub accepts a notification over HTTP and delivers it later. The caller gets an answer
as soon as the notification is safe, not when it is delivered. Two things must hold:

- A notification that was acknowledged must never be lost, even if the process stops a moment
  later.
- A delivery that is slow or broken must not stop the intake from accepting new notifications.

The service already needs PostgreSQL to keep the record of every notification and its attempts.
The question is where the waiting work lives, and how it gets there without a window in which the
record exists but the work does not, or the other way round.

## Considered options

1. **A PostgreSQL table as the queue.** The `notification` table is the record and the queue at
   once. Workers poll it.
2. **A message broker**, RabbitMQ. The intake publishes a message and consumers deliver it.
3. **An in-memory `BlockingQueue`** inside the process.

### 1. PostgreSQL table as the queue

- Good: persisting and queueing are one `INSERT` in one transaction. There is no second system to
  fall out of step with the first.
- Good: no new component to run. The database is already required.
- Good: the full history stays queryable in the same place. A failed notification and its
  attempts are answered with plain SQL.
- Bad: workers find work by polling, so dispatch latency has a floor of the poll interval.
- Bad: the table is a queue only because of the queries we write. Ordering, claiming and
  recovery are ours to build and to keep correct.

### 2. RabbitMQ

- Good: pushes messages to consumers, so latency is lower and there is no polling.
- Good: competing consumers, acknowledgements and redelivery come built in.
- Bad: writing the row and publishing the message are two writes to two systems. Doing that
  safely needs an outbox table anyway, so the broker would add its complexity on top of the
  outbox instead of replacing it.
- Bad: one more system to run, monitor and upgrade, for a single deployment that the database
  alone can serve.

### 3. In-memory `BlockingQueue`

- Good: the least code, and no latency.
- Bad: everything queued is lost on a restart or a crash, including notifications already
  acknowledged to the caller. That breaks the first requirement above.

## Decision

Option 1. The `notification` table is the queue. It was chosen because it removes the dual-write
problem instead of managing it, and because it adds nothing to the deployment.

This is not a transactional outbox, although it shares its reason. An outbox writes business data
and a message in one transaction, and a relay publishes the message to a broker. Here the row is
the only write and workers consume the table directly, so there is no message, no relay and no
broker. Step 3 of the path to production is what would turn it into a transactional outbox.

How it is applied:

- The intake writes one row with `status = 'PENDING'` and `next_attempt_at` set to the current
  time. That `INSERT` is the record and the enqueue. The request answers `202 Accepted`, because
  the row exists but nothing has been delivered yet.
- The REST side never calls the dispatcher. It only writes rows. A broken delivery therefore
  leaves intake working, and rows pile up as `PENDING` until the destination recovers.
- A scheduled worker polls for due rows every `notifications.dispatch.poll-interval`, one second
  by default. How rows are claimed is in [ADR 0002](0002-claiming-work-with-skip-locked.md), and
  how a claim is kept safe is in [ADR 0003](0003-dispatching-state-and-reaper.md).
- A partial index on `status = 'PENDING'` keeps the polled set proportional to the backlog and
  not to the history. Check constraints on `status` and on the claim columns keep an impossible
  state out of the table.
- Every attempt, successful or not, is written to `notification_attempt`.

## Consequences

- Dispatch latency has a floor of the poll interval. For notifications this is acceptable.
  `LISTEN/NOTIFY` could lower it, but it adds code, and it was left out for that reason.
- Delivery is at-least-once. A crash after delivering and before recording the result makes the
  row be delivered again. The receiver gets `X-Notification-Id` on every request so that it can
  drop a repeat. See [ADR 0003](0003-dispatching-state-and-reaper.md).
- Polling is load on the database. Each instance runs one query per interval whether or not there
  is work, and `notifications.dispatch.batch-size` bounds how much one poll takes.
- The queue and the record share one database. If PostgreSQL is down, nothing is accepted and
  nothing is dispatched. There is no separate queue to keep working during that time, and the
  API answers `500`, which does not tell a caller that trying again later will work.
- Ordering is by `priority_rank`, `next_attempt_at` and `created_at`. It is not a strict FIFO,
  and with several instances it is not a global order.

## Path to production

The steps are ordered by which limit arrives first, and each can ship on its own.

1. **History tables.** Nothing removes old rows today, so `notification` and
   `notification_attempt` grow without bound. The partial index keeps the claim fast, but every
   other index, vacuum and backups grow with the history. A periodic job moves `SENT` and
   `FAILED` rows past a retention period, with their attempts, into history tables of the same
   shape, in small batches so it never holds long locks. Partitioning by `created_at` and
   detaching old partitions reaches the same result without row-by-row copies. The retention
   period starts after the window in which a caller may still retry with the same idempotency
   key, so a repeat never meets a moved row. The read endpoint must also look in the history
   tables when a row is not found.
2. **`LISTEN/NOTIFY`.** The intake sends a notification after its commit and an idle worker wakes
   up at once, which removes the poll interval from the latency. Polling stays as the fallback
   for a missed signal, so correctness does not depend on it.
3. **A message broker**, only when several systems need the same events or the load outgrows one
   database. A relay publishes `PENDING` rows and the table stays the source of truth, which makes
   the table a transactional outbox. This is not free: claiming, retries with backoff and
   stale-claim recovery move to the broker or end up built twice.

One step is independent of load:

4. **A database that can be down.** While PostgreSQL is unreachable the API answers `503` with
   `Retry-After`, so a caller knows the failure will pass. A standby with automatic failover
   then shortens how long that lasts.
