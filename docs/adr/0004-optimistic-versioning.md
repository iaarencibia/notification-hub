# 4. Optimistic versioning

- Status: Accepted
- Date: 2026-10-02

## Context and problem

The reaper in [ADR 0003](0003-dispatching-state-and-reaper.md) cannot tell a dead instance from a
slow one. This sequence can happen:

1. Instance A claims a notification and sends the request. The destination takes six minutes.
2. At five minutes the reaper returns the row to `PENDING`.
3. Instance B claims it, delivers it and records `SENT`.
4. At six minutes A comes back with the copy it loaded long ago and writes its own result.

Without protection, a delivered notification can end up overwritten by a stale outcome. The
question is what stops a writer holding an old copy of a row from writing over a newer state.

## Considered options

1. **Rely on the unique key of attempts.** `notification_attempt` has
   `UNIQUE (notification_id, attempt_number)`. The attempt number comes from `attempts`, which is
   the field that would be stale, so the old writer collides with the one that went ahead.
2. **A `version` column with optimistic locking.** Every update states the version it read, and
   changes nothing if the row has moved on.
3. **Check the owner on write.** Every update requires the row to be `DISPATCHING` and
   `claimed_by` to be the instance that is writing.

### 1. The unique key of attempts

- Good: nothing to add. It already works for every write that goes through the attempt record,
  and the attempt and the notification are written in one transaction, so a collision rolls back
  both.
- Bad: the protection is accidental. It holds only while attempt numbers are derived from
  `attempts`. If someone assigns them from a sequence, it disappears and no test fails.
- Bad: the failure arrives as a constraint violation, which looks like a bug. Losing the race is
  an expected event and must be told apart from a defect.

### 2. A version column

- Good: the protection is declared. The rule is "write only if the row is as you read it", and it
  is visible in the statement itself.
- Good: losing is a distinct outcome, reported as a lost claim, which is logged and dropped.
- Bad: one more column and one more condition in each write. It also counts writes and means
  nothing else, so it can be mistaken for `attempts`.

### 3. Check the owner on write

- Good: no new column. The claim columns already say who owns the row.
- Good: it stops the sequence above, because after B claims the row A is no longer its owner.
- Bad: it fails when the same instance claims the row twice. A's claim is released, A claims the
  row again on a later poll, and the first delivery comes back late. The owner still matches, so
  the stale write goes through.
- Bad: closing that gap means comparing `claimed_at` as well. That is a version kept in a
  timestamp, with less clarity than a counter.

## Decision

Option 2. The unique key still holds, but it is a side effect. A `version` column makes the
guarantee explicit and gives the race its own outcome.

How it is applied:

- The column is `version BIGINT NOT NULL DEFAULT 0`. It counts writes to the row. It is not an
  attempt counter and carries no meaning of its own.
- A claim records the version the row has after the claim. That value travels with the claimed
  notification through the delivery.
- Recording a result is an `UPDATE` with `WHERE id = :id AND version = :version` that also sets
  `version = version + 1`. It uses the version from the claim, not the current one. Written
  against the current value, the check would always pass and protect nothing.
- If the update matches no row, the claim was lost. Nothing is written, not even the attempt. The
  dispatcher logs the lost claim at info level and discards the result.
- The reaper adds one to `version` when it releases a row. That is what makes the returning
  instance lose.
- `attempts` counts attempted deliveries and `version` counts writes. They are not related.

## Consequences

- The result of a delivery that lost its claim is discarded, but the delivery already happened.
  The destination may have received the notification twice. Versioning protects the state of the
  row, not the destination.
- The winner is whoever recorded first, not whoever delivered first. The row ends in a consistent
  state, and the attempt history shows the attempts that were recorded.
- Every update to the row must carry the version condition. A write path that skips it is not
  protected, and the `version` column does not warn about it.
- Retrying the discarded result is not attempted. The row has moved on, and the new owner decides
  what happens next.
