# Changelog

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
