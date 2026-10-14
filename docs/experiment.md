# 575 commits, 21 security fixes, 6 test suites: where a deterministic review manifest was right and where it was wrong

*October 2026. Everything here can be re-run; the raw data is in
[experiments/2026-10](../experiments/2026-10).*

skimit reads compiled Java and sorts the methods a change touches into three buckets: **must
review** (the method can reach something you declared critical), **should review** (it takes part
in such a flow, or the analysis cannot prove it does not), and **isolated** (nothing connects it to
anything critical). No model scores anything. The same commit always gets the same answer, and each
answer comes with the call chain that proves it.

That is a claim, and this is the experiment that tests it. Three questions:

1. On real history, how much review does it say you can skip?
2. Would following it have meant skimming code that later needed a bug fix or a security fix?
3. When the program actually runs, does anything it called isolated turn up inside a critical flow?

The short version:

- **A third of commits needed no body review** (187 of 575), 39% contained something on a critical
  path, and the rest sat in between.
- **Of 18 real security fixes, 12 were flagged must review and 4 were called isolated.** All four
  fixed read-side leaks, which the rule used here does not treat as critical. One more exposed a
  real gap in the analysis.
- **Running six test suites under a recording agent found 15 methods the released version got
  wrong**, all in one project it had never seen: 9 it had called isolated and 6 it had ranked too
  low. They came from four gaps. Those are closed in 0.1.1, and a further unseen project then came
  back clean.

## Setup

**One rule for every project, written before any result was seen.** A sink is any call that writes
through a repository, mapper or DAO:

```
sink DATA_WRITE *Repository#save*      (and insert*, update*, delete*, remove*)
sink DATA_WRITE *Mapper#save*          (same five verbs)
sink DATA_WRITE *Dao#save*             (same five verbs; also *DAO)
```

This is deliberately blunt. A team would declare the handful of operations it really cares about
(charging a card, publishing a release), and the numbers would be better for it. Using one rule
everywhere removes the suspicion that the sinks were tuned per project to make the tool look good.

**Projects.** Nine open-source Java applications for the replay, chosen for having real history and
a conventional persistence layer: [Apollo](https://github.com/apolloconfig/apollo),
[XXL-JOB](https://github.com/xuxueli/xxl-job), [litemall](https://github.com/linlinjava/litemall),
[vhr](https://github.com/lenve/vhr), [newbee-mall](https://github.com/newbee-ltd/newbee-mall),
[Spring PetClinic](https://github.com/spring-projects/spring-petclinic),
[PetClinic REST](https://github.com/spring-petclinic/spring-petclinic-rest),
[Spring Framework PetClinic](https://github.com/spring-petclinic/spring-framework-petclinic) and
[JPetStore](https://github.com/mybatis/jpetstore-6). Two more,
[JHipster sample app](https://github.com/jhipster/jhipster-sample-app) and
[PetClinic microservices](https://github.com/spring-petclinic/spring-petclinic-microservices), were
used only for the runtime check. Three of the nine (Spring PetClinic, Spring Framework PetClinic,
JPetStore) were used while skimit was being developed, and its rules were shaped by their runtime
checks. XXL-JOB had been measured and replayed before, with a different sink, but never
runtime-checked. The other seven projects it had never been run on. Exact revisions are in
[projects.txt](../experiments/2026-10/projects.txt).

**Versions.** The experiment started on the released 0.1.0. It found gaps, they were fixed, and
every number below was then recomputed with 0.1.1 from the same cached builds. The 0.1.0 results
are kept beside them in the data folder, and the differences are stated where they matter.

## 1. How much review can be skipped?

`scripts/replay.sh` takes each recent commit that touches main Java sources, builds it and its
parent, and runs `skimit diff` between the two. 758 commits were tried. 183 no longer build (older
commits whose dependencies have disappeared or that do not compile on JDK 21), which leaves 575.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="replay-dark.svg">
  <img src="replay-light.svg" alt="What 575 real commits to nine open-source projects needed, under one fixed rule" width="780">
</picture>

| Project | Tried | Analysed | No code change | Isolated only | Should review | Must review |
|---|---:|---:|---:|---:|---:|---:|
| XXL-JOB | 200 | 195 | 32 | 11 | 55 | 97 |
| Spring PetClinic | 100 | 84 | 31 | 14 | 20 | 19 |
| PetClinic REST | 100 | 78 | 12 | 12 | 23 | 31 |
| Apollo | 77 | 68 | 1 | 14 | 25 | 28 |
| litemall | 54 | 45 | 4 | 18 | 4 | 19 |
| newbee-mall | 38 | 37 | 4 | 8 | 9 | 16 |
| Spring Framework PetClinic | 100 | 26 | 5 | 4 | 13 | 4 |
| JPetStore | 43 | 22 | 13 | 0 | 6 | 3 |
| vhr | 46 | 20 | 2 | 2 | 8 | 8 |
| **Total** | **758** | **575** | **104** | **83** | **163** | **225** |

A commit is counted by the most serious thing in it. "No code change" means the bytecode of every
analysed class is identical: comments, formatting, renamed locals, or files outside the modules
that were built.

- **33% of commits needed no body review** (187 of 575): 104 changed no code and 83 changed only
  isolated methods.
- **39% contained something on a critical path** (225). The manifest names the methods and shows
  the call chain for each.
- **28% touched code that takes part in a critical flow without reaching the sink** (163).
- **By method rather than by commit**: of 13,006 changed methods, 12% were must review, 59% should
  review and 29% isolated.
- **Projects differ.** The share needing no body review runs from 20% (vhr) to 59% (JPetStore).

The honest reading: with a rule as broad as "every database write is critical", most of a
data-driven application is connected to a sink, and skimit says so. It does not make two thirds of
review disappear. What it does is take a third off the table with a proof, and point at the 39%
that deserve the careful read.

## 2. Would it have hidden a bug?

### Bug fixes

157 of the 575 commits say in their message that they fix something. Sorted the same way:

| | Fix commits | Share |
|---|---:|---:|
| No code change in analysed classes | 21 | 13% |
| Isolated only | 30 | 19% |
| Should review | 48 | 31% |
| Must review | 58 | 37% |

So **19% of fixes landed entirely in code skimit calls isolated.** That is not a contradiction, but
it has to be said plainly: isolated means "cannot affect what the sinks are asked to do", not
"cannot contain a bug". A wrong label on a page, a broken search filter, a null check in a read
endpoint: these are real bugs with no path to a database write. If your reviewers care about them,
either declare more sinks or keep reading that code.

The sharper test is the other direction. `scripts/fix-check.py` takes each method that a fix
changed in flagged code and looks up how the *previous* change to that method was classified. If
the earlier change had been "isolated", a reviewer following the manifest would have skimmed code
that later needed a fix in a critical flow. Fixes changed 345 flagged methods (the script counts
methods a fix modified, not ones it added or removed); 191 had an earlier change inside the
replayed window. Of those, 96 had been must review, 95 should review, and **none
isolated**.

This is a weak test and should be read as one. A method's verdict rarely changes between commits,
so it mostly shows the verdicts are stable. It would have caught a rule that flips methods in and
out of "isolated"; it does not prove the rule is right.

### Security fixes

Each project's history was searched for commits whose message mentions a CVE, an advisory, or
words such as XSS, SSRF, unauthorized or privilege, and the genuine vulnerability fixes were picked
by hand: 21 commits, seven of them with a CVE or GitHub advisory number. Three no longer build. The
other 18, under the same writes-only rule:

| Result | Fixes |
|---|---:|
| Must review | 12 |
| Should review | 1 |
| Isolated only | 4 |
| Changed only a sample module outside the analysed code | 1 |

The full list is in [security-fixes.csv](../experiments/2026-10/security-fixes.csv).

**The twelve** are the fixes to operations that change data, each flagged with its path. In
XXL-JOB: CVE-2022-36157, the authorisation half of CVE-2022-43183, CVE-2024-42681, the 2025 IDOR
fix, the glue-code privilege fix, one of two commits tightening the job and log API, the CSRF fix
on password change, and the 2026 XSS hardening. In Apollo: both advisories merged in October 2026
(GHSA-8j5g-8m4g-frrh, GHSA-6gfh-7m5g-c78c) and the permission-bypass fix discussed below. In
newbee-mall: the XSS fix.

**The one "should review"** is the second commit tightening XXL-JOB's job and log API. Its only
change in analysed code is a scheduling helper called inside a critical flow.

**The one not tested** is CVE-2025-7787 in XXL-JOB: the fix is in a sample executor module that
was not among the modules built.

**The four isolated ones are all in code that only reads**, and three of them are the same method.
`JobLogController.logDetailCat` in XXL-JOB reads a job's log from an executor. It was fixed for
SSRF in 2023 (the other half of CVE-2022-43183), for XSS in 2024, and for a missing permission check in 2026. It writes nothing, so
under a writes-only rule it is isolated all three times. The fourth is an Apollo endpoint that
listed other users' applications. A reviewer following the manifest would have been told to skim
all four. This is the rule working as written, and it is the most useful
thing the experiment shows about choosing sinks: **if reading something is sensitive, the read is a
sink.** Adding one line after the fact,

```
sink LOG_READ *ExecutorBiz#log
```

turns all three `logDetailCat` fixes into must review
([output](../experiments/2026-10/security/logread-illustration.md)). That line was written knowing
the answer, so it is an illustration, not a result.

**The one that found a bug in skimit.** Apollo's February 2026 fix for a permission bypass changed
three methods of a permission validator. Version 0.1.0 called all three isolated. At least one is
not: the validator is invoked from annotation expressions,

```java
@PreAuthorize(
    value = "@unifiedPermissionValidator.hasModifyNamespacePermission(#appId, #env, #clusterName, #namespaceName)")
```

on endpoints that write. The call exists only inside a string, and the analysis did not read
annotation strings. The commit as a whole was still flagged must review because it also changed
the controllers, but a change to the validator alone would have been waved through. 0.1.1 treats
a bean method named in an annotation expression as called by the annotated method, which moves
`hasModifyNamespacePermission` to should review. The other two stay isolated, and one of them
should not: `hasReleaseNamespacePermission` guards publishing a release, which Apollo's portal
does by calling another service over HTTP. No local write, so no sink under this rule. That gap is
listed under Limits.

## 3. Does it hold when the program runs?

`skimit verify` does not trust the analysis. A Java agent records, for every flow that reaches a
sink, which methods were on the call stack and which had already run in the same flow. If any of
them is marked isolated, or was on the stack without being on a path, that is a miss. Each
project's own test suite was run this way, with the same writes-only rule.

| Project | Seen during development | Tests | Sink calls | Methods in critical flows | Misses, 0.1.0 | Misses, 0.1.1 |
|---|---|---:|---:|---:|---:|---:|
| Spring PetClinic | yes | 79 | 22 | 76 | 0 | 0 |
| Spring Framework PetClinic | yes | 75 | 21 | 75 | 0 | 0 |
| JPetStore | yes | 98 | 49 | 82 | 0 | 0 |
| PetClinic REST | no | 237 | 93 | 178 | 0 | 0 |
| Apollo | no | 1,404 | 918 | 673 | **15** | 0 |
| JHipster sample app | no | 216 | 200 | 206 | 0 | 0 |

Six of JHipster's 216 tests, all about time zones, fail in the environment used here with or
without the agent attached; they are included. PetClinic microservices was also run, but its nine tests mock the repositories and never reach a
sink, so there was nothing to check. litemall, XXL-JOB, vhr and newbee-mall need a running MySQL
for their tests, or have none.

Apollo is the largest project here (5,887 methods in its main code, 1,404 tests) and the first
large one to go through the runtime check. It produced 15 misses among 673 methods. Nine had been
called isolated. Six had been flagged should review but were in fact on the call stack when a sink
was called, so they were ranked too low. They came from four gaps:

- **Servlet filters (3: 2 isolated, 1 ranked too low).** A filter that code constructs by hand and registers was treated as
  ordinary code. But a filter calls `chain.doFilter`, and everything that handles the request runs
  inside that call. Handing on to a chain is now an opaque call, as is `proceed` on an aspect or
  interceptor.
- **A JPA attribute converter (4, all isolated).** Hibernate builds and calls an `@Converter` class when it
  saves an entity. A unit test also constructed one with `new`, which made the analysis treat it as
  ordinary code. The annotation now settles it.
- **Spring's reflection helper (7: 2 isolated, 5 ranked too low).** Test methods reached a sink through
  `ReflectionUtils.invokeMethod`, which was not on the list of reflective calls.
- **An aspect's constructor (1, isolated).** The advice was flagged, the constructor the framework runs to
  build the aspect was not.

All four are closed in 0.1.1, and Apollo then shows no misses. "Closed" here means the analysis
stops claiming what it cannot prove: the nine isolated methods become unproven, and the six are
recognised as reaching a call it cannot follow. None of them became on-path. That is not evidence
the fixes generalise, because they were written to fit this run. The JHipster sample app is: it
was fetched and run only after the fixes were in, and it came back clean on both versions.

Counting every project, 1,290 methods were observed taking part in a critical flow. The released
version got 15 of them wrong (1.2%), and had told a reviewer to skim 9 (0.7%), all in one project.

What this check covers is what the tests execute, and no more. A method no test reaches is not
checked.

## What changed in skimit

Five fixes, released as 0.1.1 (details in the [changelog](../CHANGELOG.md)):

1. Bean methods named in annotation expressions are called by the annotated method.
2. Handing on to a filter, interceptor or aspect chain is an opaque call.
3. Spring and Commons Lang reflection helpers are opaque calls.
4. `@Converter` classes are framework-managed.
5. The constructors of advice and listener classes run when the framework builds them.

On the replay these moved 2 of the 575 commits from "isolated only" to "should review", and 82 of
the 13,006 changed methods out of isolated. The headline numbers are the same to the nearest
percent on either version.

Later, running skimit on PetClinic REST's recent pull requests showed three sources of noise:
MapStruct mappers treated as data stores, JPA query building counted as a write, and `toString`
on a plain `Object` fanning out to every class. 0.1.5 fixes them. Replaying the 575 commits again
with the built-in rule, must review is unchanged (226 commits, 1,634 methods); 1,602 methods move
from should review to isolated and nothing else moves. Five commits, all in PetClinic REST, go from
should review to isolated only. The runtime check still finds no misses on any of the six suites,
and none of the methods a later bug fix touched is isolated.

## Limits

- **The sinks are mine, not the maintainers'.** One blunt rule for everything. A project's own
  sinks would give different numbers, probably with more isolated code and fewer false alarms, and
  possibly with misses this rule hides.
- **The sample leans recent.** A quarter of the commits tried no longer build. Spring Framework
  PetClinic lost 74 of 100.
- **"Fix" is a keyword match on the commit message**, in English and Chinese. Some fixes are
  missed and some matches are not fixes.
- **The security fixes were picked by hand** from a keyword search, and whether a fix concerns a
  read or a write is my reading of the commit. 21 were picked and 18 could be analysed. The split
  is suggestive, not a rate.
- **Three projects were development projects.** Their clean results say the least. The numbers to
  weigh are PetClinic REST, Apollo and JHipster.
- **The rule was fixed in advance, but you have only my word for it.** The policy file is in the
  data folder; its history is not.
- **Tests were counted as flows.** The runtime check includes test code, which is why reflection
  inside a test shows up as a miss.
- **Apollo's largest gap is still open.** Its portal publishes a release by calling another
  service over HTTP. Under a rule about local database writes, the permission checks guarding that
  call are isolated. A flow that leaves the process is invisible unless the outgoing call is
  declared a sink.

## What to take from it

- The top tier is the part to trust most: when skimit says a change can reach a sink, it shows the
  chain. Of the 18 security fixes, the 12 in that tier were the ones to operations that change
  data.
- "Isolated" is a statement about the sinks you declared. Declare reads that matter, and calls
  that leave the process.
- The analysis had real holes on a codebase it had not seen, and the runtime check found them in
  one run. Run `skimit verify` on your own test suite before you rely on the manifest, and after
  you add a framework.

## Reproduce it

```
git clone https://github.com/PeterToulouras/skimit && cd skimit && mvn -q package

# 1. replay a project's history under the fixed rule
scripts/replay.sh path/to/project experiments/2026-10/data-writes.policy 100
scripts/fix-check.py path/to/project path/to/project-replay

# 2. replay chosen commits, for example security fixes
REPLAY_COMMITS="738d7721 a2dc9011" scripts/replay.sh path/to/xxl-job experiments/2026-10/data-writes.policy

# 3. check the verdicts against a real run
java -jar skimit-analyzer/target/skimit.jar analyze target/classes target/test-classes \
    --policy experiments/2026-10/data-writes.policy --agent-config agent.conf
mvn test "-DargLine=-javaagent:$PWD/skimit-agent/target/skimit-agent.jar=config=agent.conf,out=trace-%p.txt"
java -jar skimit-analyzer/target/skimit.jar verify target/classes target/test-classes \
    --policy experiments/2026-10/data-writes.policy --trace trace-1234.txt
```

Per-commit results, the manifests for every analysed commit, the security list and the runtime
check outputs are in [experiments/2026-10](../experiments/2026-10).
