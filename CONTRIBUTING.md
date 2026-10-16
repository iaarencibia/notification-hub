# Contributing

This is a personal project. Pull requests from outside are not merged, but issues are welcome:
bug reports, proposals and comments on the design all help.

## Opening an issue

Pick the form that fits on the [issues page](https://github.com/iaarencibia/notification-hub/issues/new/choose):

- **Bug report**: something does not work as the README, the API docs or an ADR say it should.
- **Proposal**: a change to the design or a new capability, with the problem it solves and what
  it costs.
- **Design feedback**: a comment on a decision, the code or the documentation.

Before proposing a change to a decision, read its record in [`docs/adr`](docs/adr). Each one
explains why the current approach was chosen for now, and its Path to production lists the
improvements already planned, several of them options the record considered. A proposal that
builds on that plan, or questions its order, is as welcome as a new idea.

## Running it locally

Everything runs in Docker, so a JDK is not needed:

```bash
docker compose up --build                      # the service and PostgreSQL
docker compose --profile test run --rm test    # the full suite and the format check
```

## Conventions

- Code style and comment rules: [`docs/CODE_STYLE.md`](docs/CODE_STYLE.md).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).
- A decision with real alternatives gets a record in `docs/adr`, in MADR format.
