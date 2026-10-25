# Changelog

## 0.2.3

Found by injecting 25 known changes into the example shop and reading what skimit said
(`scripts/recall.py`; the table is in `experiments/2026-10/recall/shop.md`).

- **A check that is gone comes first among the lines about how.** "no longer checks
  Permissions.canRefund before it writes MONEY" is the most dangerous line skimit can print, and
  it sorted among the other changes to how, where seven lines would fold it away. It now comes
  before every other line about how, as kind `CHECK_GONE` in the JSON; the counts of what and
  how are unchanged.
- **A change inside the effect's own code is a line.** The walk stops at the effect, so a change
  in `Ledger.post` itself said "No change in behaviour." Now: "the code that writes MONEY via
  Ledger changed (in Ledger.post: no longer uses ":")".
- **A method renamed along with its callers is not a change**, as a moved class is not:
  `PaymentService.charge` to `pay`, with the implementation and every caller updated, says
  nothing. A supertype that declares the new name where it declared the old one is renamed with
  it.
- **A string or a box handed to another call first is not work done on the write.** A refund
  sent to the account that had just been checked read "writes MONEY via Ledger now using
  Permissions.canRefund"; it reads "Ledger.post now given user, not customer".
- **A lost effect says when the map went blind.** A write moved behind reflection reads "POST
  /refunds no longer writes MONEY via Ledger (2 → 1 ways in); RefundHandler.refund now calls
  Method.invoke, which skimit does not follow".

Found by reading 60 replayed commits against what skimit said about them (the table is in
`experiments/2026-10/recall/labels.md`):

- **Renamed fields are read by their old names**, as renamed methods and moved classes are. A
  commit that renamed every `xxlJobInfoDao` field to `xxlJobInfoMapper` said 39 lines; it says
  none. Several fields of one type renamed together, such as three enum constants with a typo
  fixed, pair by the names they are closest to.
- **An anonymous class renumbered by an insertion is the same class.** javac numbers them per
  top-level class, so a new one inserted earlier used to make the next one "new on the way to"
  an effect.
- **Only what is done to an object before the write is its data**, and reading from it is not
  doing anything to it: mapping the saved entity to a response, or a getter in a log line, no
  longer says the write has different data.
- **A check that fails through a helper that always throws is still a check.** Moving from
  `return` to `Fail.now(...)` used to read as every check before the write being gone.
- **A value put in a local first, or passed through a second one, is not a change**, and reading
  a parameter, a field or a constant earlier or later is not another order of steps, as long as
  every call is still given the same expressions in the same places: swapping two arguments is
  a change even when both are reads, as Apollo's permission-target fix was.
- **Spring Data fragments are wired by name.** `SpringDataPetRepositoryImpl implements
  PetRepositoryOverride` is reached through the repository, as Spring Data would reach it.
- **The headline says what it means.** "No change in behaviour." is now "No change to what any
  way in writes, deletes, sends or calls." Of 30 commits that got the old sentence, 6 had changed
  behaviour a reviewer would want to know about, 4 of them in checks that guard reads, which are
  outside what skimit sees.

Of the 25 changes injected into the example shop, all 15 that change behaviour are said (14 under
0.2.2) and 5 of the 10 that change the code alone are silent (4 of 9 under 0.2.2). Of 18 injected
into PetClinic REST, 10 of 11 that change behaviour are said and 4 of 6 refactorings are silent.
The replay over the 575 commits and the runtime check are re-run by the `measure` workflow; the
numbers are in [docs/evidence.md](docs/evidence.md).

## 0.2.2

Lines about how an effect is done now say what changed in nearly every case, and changes that do
nothing stop producing lines.

- **What a call is given.** A call that is still made but given something else says what, in the
  names the source gave its locals: "ClusterService.createCluster now also given operator",
  "OrderRepository.save now given parent, not id", "XxlJobRemotingUtil.postBody now returns
  Response, not ReturnT".
- **Flags and limits.** A field whose value is tested or used is named: "no longer checks stopped
  before it writes Order".
- **When nothing named changed, what did**: a call made a different number of times
  ("RabbitTemplate.convertAndSend now called once, not twice"), a library call or a field swapped
  for another ("List.size → List.isEmpty"), the same steps in another order, or what the method
  itself now takes or returns.
- **Fewer lines that say nothing.** A private method made visible to a proxy is not a change, nor
  is the message of an exception a helper throws, and adding a parameter does not make the others
  look different. A class that moved package and changed is compared with what it was, and a new
  helper whose caller's line names it has no line of its own.

Over the same 575 commits, 6% of the lines about how say only where, down from 33%, most of them
in the two commits that moved a project from Java 8 to 17. Lines about how fall from 1,211 to 977,
and 4 more commits, which only moved code or changed an exception's message, now say "No change in
behaviour." The same 42 commits change what the system can do. The data is in
`experiments/2026-10/feed-0.2.2`.

## 0.2.1

Lines about how an effect is done now say what changed, not only where.

- **Values.** "changed the check before it writes Order (in Limits.allowed: 100 → 1000)", "new
  check before it writes Order, on OrderRepository.count against 100".
- **Helpers say what changed in them.** A changed method whose result goes into an effect names
  what it now checks and uses: "writes MONEY via Ledger with different data (in
  PriceCalculator.total: now checks Order.quantity against 10; now uses 90 and 100)",
  "(in SpringSecurityUserInfoHolder.getUser: userService → userServiceSupplier)". Logging and the
  exceptions a method throws are left out.
- **The kind of logic, when nothing named changed**: "a comparison changed", "a calculation
  changed".
- **Moving packages is not a change.** A class is known by its simple name when slices are
  compared, so a library or helper that only moved package (`javax` to `jakarta`, a dependency
  that reorganised) says nothing.
- **Shorter lines.** A check and the data that both changed, with nothing more to say, name the
  effects once ("with a changed check and different data"); helpers that changed the same way are
  named together; a line shows one way in as an example when two would be long.

Over the same 575 commits, 33% of the lines about how say only where, down from 60%, and 5
commits that only moved packages or swapped annotations now say "No change in behaviour." The
same 42 commits change what the system can do. The data is in `experiments/2026-10/feed-0.2.1`.

## 0.2.0

skimit now says what a change did to what the program does, instead of which methods to read.
Code review by reading every line does not scale to the amount of code being written; what a
person needs to know is what the system can now do, and whether it still keeps the rules the team
set. This release is a different product on the same analysis.

- **`map`: what the program does.** The ways into the program (HTTP routes, scheduled jobs,
  message listeners, event handlers, startup hooks, threads) and the effects each can cause: data
  written or deleted, other services called, messages and email sent. Effects come most
  consequential first, seven at a time.
- **`diff`: what a change did to that.** One sentence, then a line for each way in that gained or
  lost an effect, each way in that appeared, went or was renamed, and each change to how an
  existing effect happens: a new or removed check before it, different data going into it, a new
  call on the way to it, with the method where it happened. Moved classes, renamed methods and
  reformatted code say nothing. Seven lines show; the rest fold away. `--html` writes one page
  with the code behind a line on request.
- **Rules.** A policy file can say `never "GET *" writes *`, `only OrderService writes Order` or
  `review deletes *`. A change that breaks one is the first line, and `--exit-code` fails on it
  (and on anything a review rule says needs a person). A rule that was already broken before
  the change is not held against it. A line in a policy file can end with a `# comment`.
- **A sink you declare adds to the built-in rule** (data writes through repositories, mappers,
  DAOs and database clients) instead of replacing it; `--no-default-sinks` still turns the rule off.
- **Calls out and messages are effects out of the box**, including calls through generated
  clients (Feign, Spring HTTP interfaces, MicroProfile REST client, Retrofit, and interfaces a
  library turns into a client).
- **`verify` checks the map** against a recorded run: every effect the run caused must be one the
  map says its way in can cause. Traces from older agents are refused; record again.
- **Action v1.** The feed goes in the job summary and, if asked, a pull request comment;
  `fail-on-broken-rule` replaces `fail-on-must-review`, and the outputs are `broken`, `what` and
  `how`. The page is attached as an artifact named after the job, or as the `artifact` input says.
  `@v0` stays on 0.1.8.
- **Removed:** the `analyze` command, the review buckets and verdicts, class positions, the
  manifest and its report, and the policy lines `opaque`, `store` and `participant` (now ignored
  with a warning).

Measured on the same 575 replayed commits and six test suites as before, with no configuration:
62% of commits changed nothing a way in does, 31% changed only how it does what it already did,
and 7% changed what the system can do (0.1.8 put 39% in must review). The median commit with
something to say needed 2 lines; 81% needed no more than 7. On the six test suites, no run did
anything the map says it cannot. Per-commit results are in `experiments/2026-10/feed-0.2.0`.

## 0.1.8

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
  and the moves are listed on their own (`moves`). A sink in a renamed class is the same sink, so
  renaming `OrderDao` to `OrderMapper` is no longer a policy change. On a PetClinic REST commit
  that moved eight controllers into a `v1` package, the manifest went from 134 changed methods with
  42 to read closely to 48 with none. On the replay of the 575 commits, 23 commits move or rename
  classes; across them, changed methods fall from 4,676 to 1,436 and must-read methods from 583 to
  256, and sink changes reported from 148 to 6. Seven commits leave must review: six to nothing to
  read and one to should review, for the real edits it also makes. In all seven, each method that
  left must review either sits in a moved class with an identical body or is code elsewhere whose
  only edit is the moved class's new name, as the source diffs confirm. No other commit moves
  bucket.

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
