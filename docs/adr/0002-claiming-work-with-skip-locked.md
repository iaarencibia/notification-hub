# 2. Claiming work with SKIP LOCKED

- Status: Accepted
- Date: 2026-10-02

## Context and problem

The work queue in [ADR 0001](0001-postgresql-table-as-work-queue.md) is a table that several
instances of the service poll at the same time. Each instance must take a batch of due rows, and
no row may be taken by two instances.

Without a broker there is no component that hands out messages. Something in the query has to do
what competing consumers do on a queue: let each worker take different work, without waiting for
the others.

## Considered options

1. **`SELECT ... FOR UPDATE SKIP LOCKED`.** Each instance locks the rows it selects and passes
   over rows another instance has already locked.
2. **`SELECT ... FOR UPDATE` without `SKIP LOCKED`.** Each instance locks the rows it selects and
   waits if another instance holds any of them.
3. **A single dispatcher chosen by leader election.** Only one instance polls. The others stand
   by and take over if it stops.

### 1. FOR UPDATE SKIP LOCKED

- Good: instances never wait for each other. The second one to arrive takes the next rows in
  line, so adding instances adds throughput.
- Good: it is one clause on a query that is needed anyway. There is no lock table and no
  coordinator.
- Good: the lock is released at commit, so a crashed instance cannot hold rows beyond its
  transaction.
- Bad: a poll can return fewer rows than the batch size, or none, while due rows exist but are
  locked by another instance. Ordering across instances is approximate.

### 2. FOR UPDATE without SKIP LOCKED

- Good: the strictest ordering. Every instance sees the same head of the queue.
- Bad: with several instances polling at once, all but one wait on the rows the first one holds.
  Extra instances add contention and not throughput.

### 3. A single dispatcher chosen by leader election

- Good: no two instances ever look at the same rows, so the claim needs no locking clause, and
  the order is strict.
- Bad: extra instances add availability and not throughput. All dispatching goes through one
  process.
- Bad: the election is a mechanism of its own. While a stopped leader is being replaced, nothing
  is dispatched.

## Decision

Option 1. Competing consumers are what several instances need, and `SKIP LOCKED` gives them with a
clause of SQL and no new component.

How it is applied:

- The claim query selects `status = 'PENDING' AND next_attempt_at <= :now`, ordered by
  `priority_rank, next_attempt_at, created_at`, with `LIMIT :batchSize FOR UPDATE SKIP LOCKED`.
- The ordering columns match the partial index on `PENDING` rows exactly, so rows come out sorted
  and the query needs no sort step.
- `:now` is read from PostgreSQL, not from the JVM. Inside the transaction that value is fixed,
  so the instant that decides what is due is the one written to every claimed row.
- Each selected row is moved to `DISPATCHING` by the domain, with `claimed_at` and `claimed_by`
  set. `claimed_by` is the instance id, so ownership of in-flight work can be traced to a
  process.
- The transaction commits right after the claim, so the row lock lives only that long. What keeps
  the row from being taken again afterwards is the `DISPATCHING` state, described in
  [ADR 0003](0003-dispatching-state-and-reaper.md).

## Consequences

- `SKIP LOCKED` protects the claim, not the delivery. It stops two instances from claiming the
  same row in the same moment. It says nothing about a claim that is abandoned later, which is
  the job of the reaper.
- A skipped row is not an error. It stays `PENDING` and is picked up by a later poll.
- Priority is respected within what one instance can see and lock. Between instances it is a
  best effort. A high-priority row locked by one instance is not waited for by another.
- `SKIP LOCKED` is not standard SQL, so the claim query is native SQL tied to PostgreSQL.
- The size of a poll is bounded by `notifications.dispatch.batch-size`, 25 by default, per
  instance and per interval.
- Priority comes first in the ordering, with no limit. While higher-priority rows keep arriving
  faster than they are dispatched, a lower-priority row waits, however old it is.

## Path to production

1. **Priority aging.** A row that has waited longer than a threshold is claimed as if it had a
   higher priority, so sustained high-priority load delays low-priority work but cannot hold it
   back forever.
