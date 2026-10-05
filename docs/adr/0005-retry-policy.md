# 5. Retry policy

- Status: Accepted
- Date: 2026-10-05

## Context and problem

A delivery can fail for reasons that pass on their own: the destination is restarting, the
network drops a connection, or the destination asks for fewer requests. It can also fail for
reasons that never pass: the destination rejects the notification, or the recipient is not an
address the service is allowed to call.

Trying again helps only in the first case. In the second it wastes work and keeps a notification
looking alive when its fate is already known. Trying again too fast or all at once also hurts a
destination that is trying to recover.

The question is which failures are tried again, how long the service waits between attempts, and
when it gives up.

## Considered options

1. **Retry inside the HTTP client.** The client repeats the request a few times before the
   attempt returns.
2. **A fixed delay between attempts**, with a limit on the number of attempts.
3. **Exponential backoff with jitter**, with a limit on the number of attempts and the schedule
   stored in the row.
4. **Retry until it succeeds**, with no limit.

### 1. Retry inside the HTTP client

- Good: no code. Apache HttpClient does it when automatic retries are left on.
- Bad: the wait happens in memory while a worker thread and a claim are held. A restart loses
  the schedule.
- Bad: the repeats are invisible. The attempt history would show one attempt where the
  destination received several requests.

### 2. Fixed delay

- Good: the simplest schedule to explain and to predict.
- Bad: a short delay hammers a destination that is down, and a long one makes a brief failure
  slow to recover. No single value fits both.
- Bad: rows that failed together retry together, again and again.

### 3. Exponential backoff with jitter

- Good: the first retry is quick, so a brief failure recovers fast, and later ones leave more
  and more room for a destination that stays down.
- Good: jitter spreads rows that failed together, so they do not return in one wave.
- Good: the schedule is a column in the row. It survives a restart and no thread waits on it.
- Bad: more values to configure, and the time until a notification gives up is less obvious than
  with a fixed delay.

### 4. Retry until it succeeds

- Good: a notification is never given up on.
- Bad: a destination that never recovers keeps its rows in the queue forever. The backlog grows
  and nobody is told that those notifications failed.

## Decision

Option 3. Failures are split into retryable and permanent, a retryable one is tried again after
an exponential backoff with jitter, and a limit on attempts ends it.

How it is applied:

- The channel adapter classifies each attempt as a success, a retryable failure or a permanent
  failure. The domain decides what follows from that. A permanent failure ends the notification
  as `FAILED` at once, without using the rest of its attempts.

  | Result of the attempt | Outcome |
  |---|---|
  | `2xx` | Success |
  | `5xx`, or a status that is not recognised | Retryable |
  | `429` | Retryable, and `Retry-After` is honoured |
  | Timeout, refused or dropped connection, name that does not resolve | Retryable |
  | Name that resolves to a private or local address | Retryable, because the next lookup may differ |
  | `3xx` | Permanent. Redirects are never followed |
  | Any other `4xx`, `408` included | Permanent |
  | Recipient that is malformed or refused before connecting | Permanent |
  | Channel with no adapter in this deployment | Permanent |

- A doubtful case is retryable. Discarding a notification that the next attempt would have
  delivered costs more than one extra request.
- The delay after attempt `n` is `initial-backoff × multiplier^(n-1)`, capped at `max-backoff`.
  Jitter then adds a random fraction between zero and `jitter` of that delay. With the defaults
  of 5 seconds, a multiplier of 3, a cap of 5 minutes and a jitter of 0.2, the delays are about
  5 and 15 seconds.
- `max-attempts` counts the first try, so the default of 3 means one delivery and two retries.
  The limit is stamped on the row when it is created. A later change of configuration does not
  touch rows already in flight.
- A retry is the row going back to `PENDING` with `next_attempt_at` set to the end of the failed
  attempt plus the delay. The same poll that finds new work finds it when it is due.
- On `429` the `Retry-After` value replaces the computed delay. It is still capped at
  `max-backoff` and still gets jitter, because it comes from outside. Only the form in seconds is
  read. The date form is ignored, since it would mean trusting the destination's clock.
- The HTTP client's own retries and redirects are turned off, so one attempt is exactly one
  request.
- Every attempt is written to `notification_attempt` with its outcome, status code, error and
  duration.

## Consequences

- With the defaults a notification gives up about 20 seconds after its first failure, plus the
  time the attempts themselves take. A destination that is down for longer than that loses its
  notifications to `FAILED`. The schedule is configuration, and a real deployment should set it
  for the outages it expects.
- `FAILED` is final. There is no dead-letter queue and no way to send a failed notification
  again other than submitting a new one.
- The policy looks at one notification at a time. It remembers nothing about a destination, so
  while a destination is down every notification for it spends its full budget against it. There
  is no circuit breaker.
- One policy covers every channel and every priority. Priority changes the order in which rows
  are claimed, not how they are retried.
- Jitter only lengthens a delay. The longest possible one is `max-backoff` plus the jitter
  fraction, 6 minutes by default. A `Retry-After` of zero stays zero, so those retries are not
  spread.
- `Retry-After` is read on `429` only. A `503` that carries it gets the normal backoff.
- `408` is permanent like any other `4xx`, although a destination may mean it as a passing
  condition.
- Running out of attempts is not an outcome of its own. The last attempt is recorded as a
  retryable failure and the notification ends as `FAILED` with `attempts` equal to
  `max_attempts`.
- A claim released by the reaper is not an attempt. It uses none of the budget and waits for no
  backoff. See [ADR 0003](0003-dispatching-state-and-reaper.md).

## Path to production

Each step can ship on its own.

1. **A way back from `FAILED`.** An operator endpoint that puts a failed notification back in
   the queue with a fresh budget. Without it, an outage longer than the budget needs the callers
   to submit everything again.
2. **A policy per channel.** A slow provider and a fast internal service do not deserve the same
   schedule. Each channel gets its own block of settings and falls back to the global one.
3. **A circuit breaker per destination.** After repeated failures the service stops calling that
   destination for a while and reschedules its rows without spending their attempts.
4. **A finer classification.** `Retry-After` is honoured on `503` under the same rule that `429`
   already follows, and `408` becomes retryable.
5. **Giving up as its own outcome.** The last attempt of an exhausted budget is recorded as
   such, with its own metric, so an alert can tell "gave up" from "rejected" without comparing
   `attempts` with `max_attempts`.
6. **A minimum delay for a hinted retry.** A `Retry-After` of zero gets a small floor before
   jitter, so rows that were told to come back at once are still spread.
