# Code style

## Formatting

Java sources are formatted with [google-java-format](https://github.com/google/google-java-format)
in AOSP style (four-space indent, 100 columns), applied and enforced through Spotless:

```
docker compose --profile test run --rm test mvn spotless:apply   # format
docker compose --profile test run --rm test mvn verify           # tests + format check
```

`spotless:check` runs in the `verify` phase, so a badly formatted file fails the build.
Everything Spotless does not cover (YAML, JSON, SQL, scripts) follows `.editorconfig`.

IDE support: the official google-java-format plugin for IntelliJ IDEA and Eclipse, and the
"Google Java Format for VS Code" extension, each with the AOSP style selected. The build is
the reference; when the editor and the build disagree, the build wins.

## Comments and Javadoc

The code explains **what** it does. Comments explain **why**: context, constraints, business
rules and contracts that cannot be inferred from the code.

1. **Class-level Javadoc says what the type is, in one to three lines.** Design rationale,
   alternatives considered and trade-offs belong in `docs/adr/`, not in the code.
2. **Javadoc on public APIs and important public methods.** Document the purpose. Use
   `@param` only when the meaning is not obvious, `@return` only when the value needs
   clarification, `@throws` for relevant exceptions and their conditions.
3. **Ports (`application/port`) always carry a one-line Javadoc.** They are the public
   contract of the application layer. Each layer's `package-info.java` states the
   dependency rule of that layer and stays.
4. **Do not add Javadoc to every method.** Private methods, trivial getters, setters,
   constructors and self-explanatory methods need none.
5. **Use `//` comments only to explain** a decision, a non-obvious business rule, a technical
   constraint or workaround, or a performance, concurrency, security or integration concern.
   Keep them to three lines at most; anything longer is an ADR.
6. **Never describe the code.** Avoid `// find customer` above `repository.findById(id)`.
   Prefer `// Not retried: the external API is not idempotent.`
7. **Prefer expressive code over explanatory comments.** If a comment exists only because
   the code is hard to follow, improve the naming or structure first.
8. **`TODO` / `FIXME` sparingly**, with an issue reference when possible.
9. **Never add comments to raise documentation coverage.**
10. **When touching existing comments:** keep the useful ones, remove the obsolete or
    redundant, correct the inaccurate, and update them together with the code.

All code, comments and documentation are written in English.
