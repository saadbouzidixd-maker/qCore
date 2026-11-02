# Contributing to qCore

By qSa3ed | qSa3ed Studio

Thank you for helping build network infrastructure people can rely on.
Correctness, stability and honest reporting matter more than feature count.

## Ground rules

1. **Never claim what was not verified.** If a change compiles but was not run
   against a real server, say so in the pull request and update
   `DEVELOPMENT_STATUS.md`.
2. **No fake implementations.** A method that throws "not implemented" is not a
   feature. Leave it out, or implement it.
3. **No ignored exceptions.** Catch, log with context, and either recover or
   propagate. `catch (Exception ignored)` is rejected in review.
4. **No blocking calls on game threads.** Infrastructure work goes through an
   async scheduler; world, entity and inventory access goes through the region
   scheduler.
5. **No secrets in the repository.** Use `${ENV_VAR}` substitution in
   configuration and keep credentials out of tests and logs.

## Building

```bash
mvn clean verify
```

- Java 21 or newer JDK.
- The first build downloads Paper and Velocity APIs from `repo.papermc.io`.

## Tests

- Unit tests live in `qcore-tests` and run in the default build.
- Integration tests are tagged `integration` and are excluded unless requested:

```bash
QCORE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/qcore_it \
QCORE_TEST_DB_USER=qcore QCORE_TEST_DB_PASSWORD=secret \
QCORE_TEST_REDIS_URI=redis://127.0.0.1:6379/0 \
mvn -pl qcore-tests test -Dgroups=integration -DexcludedGroups=
```

Integration tests must skip (never silently pass) when the environment is
missing.

## Code conventions

- Package root `studio.qsa3ed.qcore`, organised by responsibility.
- Immutable models: `record` types with validation in the compact constructor.
- Constructor injection; no service locators and no mutable static state.
- Public API surface lives in `qcore-api` only. Implementation classes stay
  internal to their module.
- Asynchronous boundaries return `CompletableFuture`; callers decide where to
  join.
- Logging: SLF4J for central and storage, the platform logger inside plugins.
  Never log credentials, tokens or full request bodies.
- Prefer a named constant or a small value object over a magic number.

## Commit style

Short imperative subject, then why the change exists:

```
Reject unknown routing destinations on the proxy

Central can name a server this proxy has never seen; sending a player to an
unregistered destination fails at the proxy anyway, so validate first and fall
back explicitly.
```

## Pull request checklist

- [ ] `mvn clean verify` passes.
- [ ] New behaviour is covered by a unit test, or the PR explains why not.
- [ ] `DEVELOPMENT_STATUS.md` reflects any change in feature status.
- [ ] No new dependency unless the module genuinely needs it.
- [ ] No credential, token or private endpoint added to source or configuration.

## License

Contributions are accepted under the MIT license in [LICENSE](LICENSE).
