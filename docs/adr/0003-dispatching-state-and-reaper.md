# 3. The DISPATCHING state and the reaper

- Status: Accepted
- Date: 2026-10-02

## Context and problem

Delivering a notification means a network call to a destination the service does not control. It
can take seconds, and a read timeout can stretch it further. The claim in
[ADR 0002](0002-claiming-work-with-skip-locked.md) is protected by a row lock, and a row lock
lives only as long as its transaction.

The question is what protects a claimed row while the delivery runs, and what happens to it if
the instance that claimed it disappears before the result is written.

## Considered options

1. **One long transaction.** Claim the row, deliver and record the result, all inside the same
   transaction. The row lock is the protection.
2. **A `DISPATCHING` state with a lease, and a reaper.** Claim in one short transaction, deliver
   outside any transaction, record the result in a second short transaction. The state is the
   protection, and a periodic job releases claims that are too old.
3. **A visibility timeout.** A claim does not change the state. It moves `next_attempt_at` into
   the future, so the row is not due while the delivery runs and becomes due again by itself if
   no result is written.

### 1. One long transaction

- Good: simple. The lock protects the row for the whole delivery, and a crashed instance
  releases it automatically when its connection drops.
- Bad: the transaction holds a connection from the pool for the whole network call. A slow
  destination can use up the pool, and then the intake endpoint cannot get a connection either.
  One slow external service stops the whole application.

### 2. DISPATCHING state, lease and reaper

- Good: no connection is held during delivery. The pool only ever serves short transactions.
- Good: ownership is visible. `claimed_by` and `claimed_at` say who has the row and since when.
- Bad: the state is a lock kept outside any transaction, so nothing releases it by itself if the
  owner dies. A second mechanism, the reaper, is needed.
- Bad: the reaper cannot tell a dead instance from a slow one, so it can cause duplicate
  delivery.

### 3. Visibility timeout

- Good: no connection is held during delivery, and no reaper is needed. A row whose owner died
  comes back when its timeout passes.
- Good: one state fewer. This is how managed queues such as Amazon SQS protect a message in
  flight.
- Bad: a row being delivered looks the same as a row waiting for a retry. Nothing says that it
  is in flight, or who has it.
- Bad: `next_attempt_at` would carry two meanings, the retry schedule and the lease.
- Bad: it has the same blind spot as the reaper. A slow instance is taken for a dead one, and the
  delivery is repeated.

## Decision

Option 2. Holding a connection across a network call would let one external service take the
application down. The extra mechanism and its costs are accepted instead.

How it is applied:

- A claim sets `status = 'DISPATCHING'`, `claimed_at` and `claimed_by` in one short transaction.
  The check constraint `ck_notification_claim_consistency` rejects a `DISPATCHING` row that lacks
  either column.
- The delivery runs outside any transaction, on a bounded pool of
  `notifications.dispatch.worker-pool-size` threads, 8 by default. A slow destination costs a
  fixed number of threads and not one per notification.
- The result is written in a second short transaction: the attempt row and the new state of the
  notification. The state is `SENT`, `FAILED`, or `PENDING` again with a later `next_attempt_at`.
- The lease is the claim's age. A row in `DISPATCHING` whose `claimed_at` is older than
  `notifications.reaper.stale-claim-timeout`, 5 minutes by default, is taken to have lost its
  owner.
- The reaper runs every `notifications.reaper.interval`, one minute by default. It is one `UPDATE`
  that sets such rows back to `PENDING`, clears `claimed_at` and `claimed_by`, and adds one to
  `version`. A partial index on `claimed_at` for `DISPATCHING` rows keeps the lookup small.
- The reaper has its own scheduler thread (`spring.task.scheduling.pool.size` is 2). On a single
  shared thread, a saturated dispatch pass would delay recovery at the moment claims are being
  abandoned.
- The reaper and the claim both read the clock from PostgreSQL, so a difference between instance
  clocks cannot make a claim look older or younger than it is.
- A reaper pass that releases rows logs a warning and increments the
  `notifications.claims.released` counter. On a healthy system it never fires.

## Consequences

- Delivery is at-least-once. If an instance dies after delivering and before recording the
  result, the reaper returns the row and it is delivered again. The destination receives
  `X-Notification-Id` so that it can discard a repeat, and that only helps if it does.
- The lease is a single value and is not renewed. A delivery that takes longer than
  `stale-claim-timeout` is treated as abandoned. Too short a value releases rows from instances
  that are only slow and causes duplicates. Too long a value delays recovery after a real crash.
- A released row can still come back. The slow instance finishes and tries to write its result
  over a row that has since been given to another instance.
  [ADR 0004](0004-optimistic-versioning.md) is what stops that write.
- `max_attempts` counts attempts that were recorded, not network calls. A delivery rescued by the
  reaper never recorded an attempt, so it is not counted.
- Between a crash and the reaper, the row waits: up to the timeout, plus up to one reaper
  interval, plus the next poll.
- A release has no limit. A row whose result can never be recorded, because of a defect or
  because it brings its instance down, is released and claimed again every few minutes without
  ever reaching `FAILED`.

## Path to production

Each step can ship on its own.

1. **A lease that is renewed.** The instance that holds a claim refreshes `claimed_at` while the
   delivery runs. The timeout can then be short, so recovery after a crash is fast, without
   releasing rows from an instance that is only slow.
2. **A limit on releases.** The reaper counts how many times a row was released. Past a limit the
   row goes to `FAILED` with a reason that says so, and stops going round.
3. **Release on shutdown.** An instance that stops in an orderly way waits 30 seconds for its
   deliveries to finish. It then returns the claims it could not finish, so they do not wait for
   the timeout.
