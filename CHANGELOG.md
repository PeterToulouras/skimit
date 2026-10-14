# Changelog

## Unreleased

- The Action never fails a job unless `fail-on-must-review` is set. A pull request or base that
  does not build, a failed download, or a failed analysis is reported in the summary and as a
  warning; a pull request comment that cannot be posted (a fork's read-only token) is a warning.
- The manifest says "1 method changed", not "1 methods changed".
- The CI examples check out with `persist-credentials: false`, since the action needs no token to
  fetch the base of a public repository.

## 0.1.5

Precision fixes found by running skimit on PetClinic REST's recent pull requests. On its two
pagination pull requests, which only read, 47 of the 66 changed methods used to land in should
review; now none do. The runtime check still finds no misses on any of the six test suites.

- A MapStruct mapper (`@org.mapstruct.Mapper`) and its generated implementation are object
  converters, not a data store, whatever the class is called. They are never built-in sinks.
- Building a JPA query (`createQuery`, `setParameter`, `setMaxResults` and the like) is neither a
  read nor a write; running one with `executeUpdate` is a write, and is now a built-in sink.
- `toString`, `hashCode` and `equals` called on a plain `Object` no longer fan out to every class in
  the program, and reading a collection field inside them no longer counts as changing it. Generated
  DTOs stop dragging their setters into critical flows.
- The Action and the examples use the current major versions of GitHub's own actions
  (`checkout@v7`, `setup-java@v6`, `upload-artifact@v7`), so a first run shows no deprecation
  warnings.
- The Action's default Maven build skips the git-commit-id plugin and style plugins, which fail
  in the worktree it uses for the base commit (Apollo uses git-commit-id).
- `examples/policies/apollo.policy`: data writes plus Apollo's portal-to-admin HTTP writes.

## 0.1.4

- A GitHub Action: `uses: PeterToulouras/skimit@v0` builds a pull request and its base, compares
  them, writes the manifest to the job summary and attaches the report. It fails nothing and posts
  nothing unless asked (`fail-on-must-review`, `comment`). `action/run.sh` does the same job on
  other CI systems. skimit runs it on its own pull requests, with `skimit.policy`.
- Gradle projects are covered end to end. `scripts/skimit-agent.gradle` is an init script that
  attaches the recording agent to every test task without touching the project's build files, and
  `scripts/replay.sh` builds each commit with Gradle when it has no Maven build (or when
  `REPLAY_TOOL=gradle`). The analysis itself already read Gradle's class folders; the docs now say
  so. On Spring PetClinic, the Gradle-built classes get the same verdicts as the Maven-built ones,
  and the Gradle test run gives the same runtime check result.

## 0.1.3

From a first run on a production codebase:

- The built-in rule covers writes straight through common clients, not only repositories, mappers
  and DAOs: JDBC, Spring's `JdbcTemplate`, JPA, Hibernate, MyBatis, MongoDB, DynamoDB and S3.
  A codebase that writes through a raw `DynamoDbClient` used to get no sinks and an empty answer.
- With no sink at all, every method is isolated and the manifest says so first. Before, methods
  near a call the analysis cannot follow were still marked "cannot be proven clear", and the
  warning claiming everything was isolated printed after the manifest.
- A method that reaches several kinds of sink shows a proof for each, in the manifest, the JSON
  (`proofs`) and the report. Before, only the shortest chain was shown, which might be the AUTH
  one when the reviewer's question was about MONEY.
- A static initializer reads as `Prices (static initializer)` instead of `Prices.<clinit>()`.
- The HTML report stores each method id once: a large codebase's report is about a quarter of the
  size (10 MB to 2.7 MB on Apollo).

## 0.1.2

- A first run needs no setup. When nothing declares a sink (no `sink` line in a policy file and no
  `@Critical` in the code), every write through a repository, mapper or DAO is treated as critical,
  and the output says the built-in rule is in use. Any sink you declare replaces it.
  `--no-default-sinks` turns it off.
- The README is a short overview; the reference material moved to `docs/usage.md`,
  `docs/how-it-works.md`, `docs/evidence.md` and `docs/limits.md`. The animated demo is replaced by
  sharp screenshots of the report.
- `examples/policies/data-writes.policy` is the built-in rule as a file, to copy and narrow.

## 0.1.1

Fixes found by the [October 2026 experiment](docs/experiment.md), which ran the runtime checker on
projects the analysis had never seen and replayed known security fixes.

- A bean method named in an annotation expression, such as
  `@PreAuthorize("@permissionValidator.canEdit(#id)")`, is now called by the annotated method.
  Before, a permission check wired this way was isolated even when it guarded a critical write.
- Handing a request on to the rest of a chain (`FilterChain.doFilter`, `WebFilterChain.filter`,
  `ProceedingJoinPoint.proceed`, `MethodInvocation.proceed`, `ClientHttpRequestExecution.execute`)
  is an opaque call: the filter or interceptor is on the stack of whatever runs next.
- Spring's `ReflectionUtils.invokeMethod`, `ReflectionTestUtils.invoke*` and Commons Lang
  `MethodUtils.invoke*` are opaque calls.
- A JPA `@Converter` class is built and called by the framework even when code also constructs one.
- The constructors of a class with aspect advice or event listeners run when the framework builds it.
- `scripts/replay.sh` accepts `REPLAY_COMMITS` to replay an explicit list of commits, and skips the
  git-commit-id plugin, which fails in a detached worktree.
- The HTML report is rebuilt around the answer: a headline saying how many methods must be
  reviewed, the review list with the reason for each method, the proof for the selected method
  beside it, and the map of the code underneath. Selecting a removed method shows the code as it
  was before the change. An isolated method now reads "has no connection to any sink".

## 0.1.0

First release.

- `skimit analyze`: a verdict and a proof for every method, from bytecode.
- `skimit diff`: a review manifest for a change, as Markdown, JSON or an interactive HTML page.
- `skimit verify` and `skimit-agent.jar`: record a real run and check the verdicts against it.
- `scripts/replay.sh` and `scripts/fix-check.py`: replay a repository's history and compare the
  verdicts with its bug fixes.
- `@Critical` annotation and policy files for sinks, opaque calls, stores and framework
  participants.
