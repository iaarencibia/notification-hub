# 6. Development credentials as overridable defaults

- Status: Accepted
- Date: 2026-10-05

## Context and problem

The service needs four secrets to run: the name, user and password of its PostgreSQL database,
and the API key that callers present, described in
[ADR 0007](0007-shared-api-key-authentication.md).

Two things pull in opposite directions. Someone who clones the repository should have the whole
stack running with one command and no setup. A repository must also never hold a real secret,
and a real deployment must be able to supply its own values without editing a tracked file.

The question is where these values come from on a clean checkout, and how a deployment replaces
them.

## Considered options

1. **Development defaults in the tracked configuration**, each one replaceable through an
   environment variable.
2. **No defaults.** A `.env` file is required, copied by hand from a tracked `.env.example`, and
   the stack does not start without it.
3. **Credentials generated on first start**, by a script that writes random values to a local
   file.
4. **A secret manager**, such as Docker secrets or Vault, from the first day.

### 1. Development defaults, replaceable by environment variable

- Good: `docker compose up --build` works on a clean checkout. There is nothing to copy, edit or
  generate.
- Good: a deployment sets its own values through the environment and touches no tracked file.
- Bad: the defaults are public. A deployment that forgets to set a variable runs with
  credentials that anyone can read in the repository.

### 2. No defaults, a required `.env`

- Good: nothing that looks like a credential is tracked, and forgetting to configure fails loudly.
- Bad: one manual step before the first run, and the copied example values end up being the same
  public defaults for most people.

### 3. Generated on first start

- Good: every checkout gets its own random values, so no known credential exists anywhere.
- Bad: a script to write, to keep working on every platform and to explain. The examples in the
  documentation can no longer show a key that works as written.

### 4. A secret manager

- Good: what a production platform expects. Secrets are stored, rotated and audited outside the
  service.
- Bad: one more component to run before the service can start at all, for values that protect
  nothing on a developer's machine.

## Decision

Option 1. The defaults are development values that protect nothing, and they are named and
documented as such. A real deployment replaces every one of them through the environment.

How it is applied:

- Every credential in `application.yml` is a placeholder with a default, such as
  `${DB_PASSWORD:notifications}`. The service reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and
  `NOTIFICATIONS_API_KEY`.
- The default API key is `local-dev-api-key-change-me`. The name says what it is, so it is hard
  to mistake for a real key in a configuration or a log.
- `docker-compose.yml` defines `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` once, with
  defaults, and passes the same three variables to the database and to the application. The two
  sides cannot disagree.
- A value is replaced by exporting the variable or by writing it in a `.env` file, which Compose
  reads on its own. `.env` is ignored by Git, so a real value has no tracked file to land in.
- No real secret is ever committed. Everything tracked is a development value.

## Consequences

- Nothing stops a deployment that forgot to set its variables. The service starts with the
  public defaults and gives no warning.
- The Compose stack publishes PostgreSQL on port 5432 of the host, so the tables can be inspected
  during development. With the default password, anyone who can reach that port can read every
  notification.
- The defaults are written twice, in `application.yml` and in `docker-compose.yml`, and are kept
  equal by hand.
- There is no tracked list of the variables a deployment must set. They are found by reading the
  two configuration files.
- Secrets travel as environment variables. They are visible to anyone who can inspect the
  container or the process.
- One database user owns the schema and serves the requests. The migrations and the application
  share the same credential and the same rights.

## Path to production

Each step can ship on its own.

1. **Refuse the defaults outside development.** At startup the service fails if the API key or
   the database password still has its development value, unless a development profile is
   active. Forgetting a variable becomes a failed start and not a silent exposure.
2. **A tracked list of variables.** A `.env.example` with every variable, its purpose and no real
   value, so a deployment knows what it must set.
3. **A closed database port.** The published port moves to a development override, so the base
   Compose file exposes only the API.
4. **Secrets from files.** The service reads its secrets from mounted files, as Docker and
   Kubernetes secrets provide them, and no longer from environment variables. A secret manager
   can then rotate them without a change to the service.
5. **Separate database roles.** One role owns the schema and runs the migrations. The service
   connects with another that can only read and write rows.
