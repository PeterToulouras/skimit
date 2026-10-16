# Changelog

## Unreleased

- **A report that says what to read.** The manifest and the HTML report open with one sentence
  ("Read 1 method closely and glance at 1."), name the buckets *Must read*, *Worth a look* and
  *Skim*, and give each method a reason in words with the path to what it reaches
  ("Reaches a data write: `ClinicServiceImpl.savePet` → `PetRepository.save`"). Skimmed methods
  and structure changes are folded away by class. The HTML report's Review view groups methods by
  class with the proof a click away; the code map moves to its own tab with plain labels. The JSON
  gains `headline`, and `says` and `path` for each change.
- **Each changed method's own lines.** With `--diff` (a `git diff` of the sources), the report
  shows the lines of the diff that fall within each changed method, found from the source file and
  line numbers in its bytecode. Verdicts never depend on it. The GitHub Action passes it.
- **Moved and renamed classes are compared with what they were.** A class that existed only after
  the change is paired with the one that existed only before it whose methods it repeats once the
  new name is read as the old one. Methods that did not change are no longer listed as removed and
  added; an edit inside a moved class is reported against the method it was (`was` in the JSON),
  and the moves are listed on their own (`moves`). On a PetClinic REST commit that moved eight
  controllers into a `v1` package, the manifest went from 134 changed methods with 42 to review
  closely to 48 changed methods with none.

- **A method that hands an object it was given to a library calls that object's callbacks.**
  0.1.7 linked a callback parameter to what callers pass in only for `java.util.function` types,
  `Runnable` and `Callable`. Now any parameter of a library type counts: a method that runs it or
  hands it on to a library (such as `parse(File f, ContentHandler handler)` calling
  `reader.setContentHandler(handler)`) calls, for each application class its callers construct
  and pass in, that class's methods implementing the parameter's type, read from the JDK when it
  has the type. Only parameters count, and wrappers that pass the object on again are followed.
  A class that extends a library type and that only a framework constructs is still `UNPROVEN`.
  On the replay of the 575 commits, no commit and no method moves bucket and no newly reachable
  list changes; the runtime check on the six test suites is unchanged, with no misses.

## 0.1.7

Three verdict fixes, each found by running 0.1.6 on a large Java service and reproduced in a
three-class project. Measured on the same 575 replayed commits as before; the runtime check on the
six test suites still finds no misses, with the same counts.

- **A wrapper that runs a callback it was handed is on that callback's path.** A method that
  receives a `java.util.function` type, `Runnable` or `Callable` as a parameter and runs it, or
  hands it on to a library, now calls the lambdas its callers pass in, also through wrappers that
  capture the callback in a lambda of their own. Before, `withMetrics(Supplier<T> s)` came out
  INFLUENCES though it is on the call stack when the write happens, and `skimit verify` reported
  it. On the replay: no commit and no method moves bucket.
- **Adding a lambda no longer marks other methods as changed.** javac numbers lambdas per class,
  so a new lambda in one method renumbered the lambdas of every method after it. A lambda is now
  known by its enclosing method and its place among that method's lambdas of the same type, and
  matched across a change by an identical body first. On the replay: 11 commits lose phantom
  entries (43 untouched enclosing methods and 144 lambda entries; 16 lambdas listed as added
  are now changed), and no commit moves bucket.
- **Unchanged methods that stop being isolated are reported.** A change can connect an untouched
  method to a critical flow. Such methods are now listed as newly reachable, with the reason they
  moved, in the manifest, the JSON (`reached`) and the HTML report; they are not counted as
  changed. On the replay, 27 of 575 commits have some: 132 methods (110 now in a critical flow,
  19 unproven, 3 on a path).

## 0.1.6

The built-in rule now recognises a write by the words in a method's name, not only its first word.
Before, a repository's `batchDelete`, a mapper's `logicalDeleteByPrimaryKey` or XXL-JOB's
`scheduleUpdate` did not count as a write, so a change that reached only them could come out as
isolated. A code review bot found the first of these on Apollo's pull request.

- A repository, mapper or DAO method is a write when its name has `save`, `insert`, `update`,
  `delete`, `remove`, `upsert` or `clear` at the start or as a later word (`batchDelete`,
  `scheduleBatchUpdate`, `markDeleted`), unless the name starts as a read (`findByUpdateTime`,
  `isDeleted`). In a recent build of each of the nine replayed projects, this adds 84 write
  methods (13 in Apollo, 64 in litemall, 5 in XXL-JOB, 2 in newbee-mall) and no reads.
- Policy files can use the same test: `sink DATA *Repository#@write`.
- On the 575 replayed commits, 6 move from should review to must review and none move out of must
  review. The bug-fix and security-fix checks still find no misses; the runtime check finds none
  on the six test suites.
- On a pull request, the Action compares GitHub's merge commit with its first parent, so the
  manifest covers exactly the pull request's change even when the base branch has moved on.
- `examples/policies/apollo.policy` covers Apollo's `batchDelete*` repository methods.
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
