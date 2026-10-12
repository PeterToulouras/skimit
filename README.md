<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/banner-dark.svg">
  <img src="docs/brand/banner-light.svg" alt="skimit: read what matters, skim the rest" width="720">
</picture>

# skimit

Deterministic review manifests for Java, computed from bytecode: which changes need a careful
read, which you can skim, and the proof for each.

You mark the few places where something important happens (the ledger write, the permission
check). skimit works out, for every change, whether it can touch one of those places, and prints
the chain of calls that proves it. No model scores the diff: the same commit always gets the same
answer, and every answer comes with its reason.

**Status: 0.1.0.** The verdicts are not taken on trust. `skimit verify` records real runs and
compares them with the analysis; on three open-source test suites it now finds nothing the
analysis missed, after finding and fixing twenty-three such misses on the way
([How the verdicts are checked](#how-the-verdicts-are-checked)). What is still outside the model
is listed under [Known gaps](#known-gaps).

![The HTML report for the example pull request: each method coloured by verdict, with an arrow from a report method to the ledger sink](docs/walkthrough.png)

## What you get

For the pull request in `examples/`, which touches four files:

```
# Review manifest

7 methods changed: **1 must review**, 3 should review, 3 isolated.

## Must review: on a critical path

- `SalesReport.render(List)` (changed) [MONEY] **NEW PATH into MONEY**
  - can reach a critical sink
  - `SalesReport.render(List)` calls `Ledger.post(String, long)`

## Should review: takes part in a critical flow, or cannot be proven clear

- `PriceCalculator.total(Order)` (changed) [MONEY]
  - is called within a critical flow that starts at CheckoutHandler.submit(Order)
  - `CheckoutHandler.submit(Order)` calls `PriceCalculator.total(Order)`
- `SalesReport.<init>(Ledger)` (added) [MONEY]
  - writes a field read by SalesReport.render(List), which is part of a critical flow
  ...

## Shape changes: review the structure, not the bodies

- new public type CsvExport
- new dependency SalesReport -> Ledger (ROOT, 4 dependents)

## Isolated: nothing connects these to a sink, body review can be skipped

- `CsvExport.export(List)` (added)
```

The first entry is the case this tool exists for. In the text diff it is one added line inside a
report class. In the graph it is a report that has started moving money.

## Try it

Needs JDK 17 or newer and Maven.

```
mvn package
./examples/demo.sh
```

On your own code:

```
# what is critical, and how much of the codebase is connected to it
java -jar skimit-analyzer/target/skimit.jar analyze path/to/classes --list

# what a change needs: build the base and the head, then compare them
java -jar skimit-analyzer/target/skimit.jar diff \
    --base base/target/classes --head head/target/classes --json manifest.json --exit-code
```

Add `--html report.html` to either command for the interactive map shown above: every method
coloured by verdict, the proof for whichever one you click, and for a diff the manifest beside it.
It is one self-contained file, so a CI job can attach it to the build.

Inputs are class directories or jars, as many as you like. `--exit-code` returns 1 when anything
lands in "must review" or a sink declaration changed, so a CI job can turn the result into a
required check.

### Check the verdicts on your own code

A static verdict is a claim. `skimit verify` tests it against what your program really does: run
your tests (or the application) with the recording agent, then compare.

```
java -jar skimit.jar analyze target/classes --policy your.policy --agent-config agent.conf
mvn test "-DargLine=-javaagent:skimit-agent.jar=config=agent.conf,out=trace-%p.txt"
java -jar skimit.jar verify target/classes --policy your.policy --trace trace-1234.txt
```

The agent notes every flow that reaches a sink: which methods were on the call stack at that
moment, and which had already run in the same flow (one request, when a servlet dispatch is
involved). `verify` fails if any of them is marked `ISOLATED`, or was on the stack without being
`ON_PATH`. A clean result covers what the run exercised and no more, so the better your tests
reach the critical code, the more it is worth.

## In CI

A sketch for GitHub Actions; adjust the build commands to your project. It builds the pull
request and its base, then writes the manifest to the job summary.

```yaml
- uses: actions/checkout@v4
  with: { path: head }
- uses: actions/checkout@v4
  with: { path: base, ref: ${{ github.event.pull_request.base.sha }} }
- run: mvn -q -f head/pom.xml -DskipTests package
- run: mvn -q -f base/pom.xml -DskipTests package
- run: |
    java -jar skimit.jar diff --base base/target/classes --head head/target/classes \
        --policy head/skimit.policy >> "$GITHUB_STEP_SUMMARY"
```

Keep the policy file under code-owner review: whoever can edit it decides what counts as
critical.

## Declaring what is critical

Either annotate the code (add the `skimit-annotations` jar, which has no dependencies):

```java
@Critical("MONEY")
public void post(String account, long cents) { ... }
```

or, for code you do not own, name it in a policy file passed with `--policy`:

```
sink   MONEY  com.acme.billing.Ledger#post*
sink   DATA   java.sql.*Statement#execute*
opaque com.acme.plugins.PluginBus#dispatch
store  settings write com.acme.settings.SettingsService#set*
store  settings read  com.acme.settings.SettingsService#get*
```

- `sink` adds a sink. Adding or removing one is reported as a policy change and always needs
  review.
- `opaque` names a call the analysis cannot follow, in addition to the built-in list (reflection,
  method handles, `ServiceLoader`, Spring event publishing and bean lookup, dispatch by URL).
- `store NAME write|read owner#method` declares state that outlives a request, such as a table or
  a cache that your own wrapper hides. Repositories, mappers, DAOs and the common database clients
  are recognised without it.
- `participant class|global annotation` names an annotation that makes a framework call a method
  inside flows it is not called from. The usual Spring, Jakarta and AspectJ ones are built in.

## How a method gets its verdict

Every method with a body gets exactly one, checked in this order.

| Verdict | Meaning | Rule |
|---|---|---|
| `ON_PATH` | It can reach a sink. | Backward over the call graph from each sink. |
| `INFLUENCES` | It takes part in a critical flow without reaching the sink. | Forward from on-path methods, by the six rules below. |
| `UNPROVEN` | No claim is made. | It can reach an opaque call, or a framework calls it at a time the analysis cannot see: a class that extends a library type and that only a framework constructs, a class with a stereotype annotation such as `@Component`, an aspect, an event listener. |
| `ISOLATED` | None of the above. | |

A method influences a critical flow when one of these holds, applied until nothing new is found:

1. **It is called from the flow.** Whatever a flagged method calls can hand it a value or throw
   into it. One exception: where a single call can land on several methods and one of them is on a
   path (a router calling one of many handlers), the others are alternatives to the critical
   branch and are left alone.
2. **It writes a field the flow reads.** Tracked per field and, for accesses on `this`, per object
   class. Reading a collection or array field counts as writing it, since a reader can change it.
3. **It writes a store the flow reads.** A repository, mapper, DAO, database client or declared
   store. This is how a settings screen that turns on automatic billing is tied to the billing
   job that reads the setting, though neither calls the other.
4. **It belongs to the data that reaches the sink.** The types handled by a sink and by its direct
   callers (in their signatures, field accesses, casts and allocations), and everything those
   objects hold in their fields, are flagged whole, because a framework may fill or read them by
   reflection. A class the caller only calls methods on is a collaborator, not data.
5. **The framework runs it alongside.** On the object of an on-path method: model attributes and
   binders before a request handler, lifecycle callbacks, injected setters.
6. **It wires the flow in.** A method that creates the lambda or method reference through which an
   on-path method is called.

Rules 3 to 5 exist because `skimit verify` showed real runs contradicting the analysis without
them.

The call graph analyses each instance method once per concrete class it can run on. A call on
`this` binds to that class alone, so a base-class method that calls an abstract hook reaches only
the hook of the subclass it is running in. A call on any other object may land on any concrete
subtype of its static type; that is why interface-typed dependency injection needs no knowledge
of the wiring. A lambda typed as one of your own interfaces is treated like any other
implementation of it: it is called where the interface is called. A lambda or object handed to a
library type is charged to the method that creates it, since the library may call it back.

Separately, every top-level class gets a position from the class dependency graph: `LEAF` when
nothing depends on it, `ROOT` when at least 10% of classes do (`--root-ratio`), `TRUNK` otherwise.
Position measures how far a bad abstraction would spread; the verdict measures what a bad body
could break. A diff reports position moves, new dependencies between classes and public API
changes in their own section, so a reviewer who cares about structure reads that list instead of
every body.

A change counts only if the method's bytecode changed. Comments, formatting, moved code and
renamed locals are not changes.

## How the verdicts are checked

Every number in this section and the next comes from an open-source project, and the sink
declarations used are in [examples/policies](examples/policies), so each one can be re-run.

### Against real runs

Each project's own test suite was run with the recording agent attached, test code included so
that a whole test counts as one flow.

| Project | Tests | Sink calls | Methods seen in critical flows | Misses at first | Misses now |
|---|---:|---:|---:|---:|---:|
| Spring PetClinic | 79 | 22 | 76 | 22 | 0 |
| Spring Framework PetClinic | 75 | 21 | 75 | 1 | 0 |
| JPetStore | 98 | 17 | 52 | 0 | 0 |

The first runs found twenty-three methods that took part in a critical flow without being
flagged for it. They came from four gaps, each now closed:

- **Methods Spring runs before a request handler** (`@ModelAttribute`, `@InitBinder`) and what
  they set up, such as a validator: 13.
- **Dispatch by URL.** Test methods that call the framework, which then picks the controller: 7.
  Such a call is now treated as one the analysis cannot follow.
- **A component that code also constructs by hand.** A formatter was treated as ordinary code
  because something built one with `new`; a stereotype annotation now settles it: 2.
- **Objects held by the data being saved.** An entity reached only through another entity's
  field: 1.

JPetStore was run after those fixes and had none, which is the fairer test: nothing was tuned on
it.

### Against bug fixes

`scripts/fix-check.py` takes the replayed history below, finds the commits that say they fix
something, and asks of each method a fix changed in flagged code: when that method was last
changed, how had the change been classified? Across the four projects, 43 fixes changed 42 such
methods; 23 had an earlier change inside the replayed window. Six of those earlier changes were
"must review", seventeen "should review", and none "isolated". No fix landed on code the manifest
had said could be skimmed.

That sample is small, and the last change to a method is only a stand-in for the change that
introduced the bug. It is a check that would have caught a bad rule, not a proof of a good one.

## What the measurements say

### How much of a codebase is connected to its sinks

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/verdicts-dark.svg">
  <img src="docs/verdicts-light.svg" alt="Share of methods by verdict in six open-source codebases" width="780">
</picture>

| Codebase | Sinks | Methods | On path | Influences | Unproven | Isolated |
|---|---|---:|---:|---:|---:|---:|
| [Spring PetClinic](https://github.com/spring-projects/spring-petclinic) | repository saves | 116 | 5.2% | 57.8% | 12.9% | 24.1% |
| [Spring Framework PetClinic](https://github.com/spring-petclinic/spring-framework-petclinic) | repository saves | 179 | 7.8% | 69.3% | 6.1% | 16.8% |
| [JPetStore](https://github.com/mybatis/jpetstore-6) | placing an order | 248 | 1.6% | 63.3% | 1.2% | 33.9% |
| [XXL-JOB](https://github.com/xuxueli/xxl-job), core and admin | running or killing a job on an executor | 928 | 2.2% | 66.3% | 2.4% | 29.2% |
| Broadleaf Commerce 3.1.15, framework and common | payment gateway transactions | 7,785 | 1.6% | 62.3% | 15.3% | 20.8% |
| Kill Bill 0.25.4, thirteen modules | payment plugin calls that move money | 10,002 | 2.7% | 68.4% | 18.6% | 10.3% |

Shares are of methods with a body. The two largest were analysed from their published jars; the
largest run takes under ten seconds.

- **The top tier is small and exact.** Between 1.6% and 7.8% of methods can reach a sink: 124 of
  7,785 in Broadleaf, 271 of 10,002 in Kill Bill. Each comes with a proof. No path-based or
  score-based tool produces that list.
- **A tenth to a third is isolated.** Before the verdicts were checked against real runs these
  shares were about fifteen points higher; the difference is code the earlier rules wrongly
  cleared. A smaller number that survives checking is worth more than a larger one that does not.
- **It depends on what the product is.** Kill Bill is a billing platform: most of it exists to
  decide what gets charged, and a tenth is isolated. That is the honest answer for such a
  codebase.

### What real commits needed

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/replay-dark.svg">
  <img src="docs/replay-light.svg" alt="What real commits to four open-source projects needed" width="780">
</picture>

`scripts/replay.sh` checks out each recent commit that touches main Java sources, builds it and
its parent, and runs `skimit diff` between the two. Run over four projects' histories:

| Project | Commits analysed | No bytecode change | Isolated only | Should review | Must review |
|---|---:|---:|---:|---:|---:|
| Spring PetClinic (2017 to 2026) | 72 | 23 | 14 | 18 | 17 |
| Spring Framework PetClinic (2015 to 2026) | 26 | 5 | 4 | 13 | 4 |
| JPetStore (2020 to 2026) | 18 | 9 | 2 | 6 | 1 |
| XXL-JOB (2024 to 2026) | 195 | 32 | 18 | 71 | 74 |
| **Total** | **311** | **69** | **38** | **108** | **96** |

- **34% of commits needed no body review** (107 of 311): either nothing in the bytecode changed
  (comments, formatting, renamed locals) or every changed method was isolated.
- **31% contained something on a critical path** (96 of 311), and the manifest says which methods
  and why. The remaining 35% touched code that takes part in a critical flow without reaching the
  sink.
- **The projects differ a lot.** The three small applications needed no body review for 35% to 61%
  of commits. XXL-JOB, a scheduler whose whole job is to trigger the sink, needed none for 26% and
  a careful read for 38%.

Limits of this data: commits that no longer build were left out (28 of 100 for Spring PetClinic,
74 of 100 for Spring Framework PetClinic, 25 of 43 for JPetStore, 5 of 200 for XXL-JOB), mostly
older ones whose dependencies have disappeared, so the samples lean recent. The sinks are one
reasonable choice per project, not the maintainers' own. The replay on your own history, with
your own sinks, is the number that matters:

```
scripts/replay.sh path/to/your/repo your.policy 100
scripts/fix-check.py path/to/your/repo path/to/your/repo-replay
```

## Known gaps

What the model still leaves out. `skimit verify` will show you whether any of these bite on your
code.

- **Stores are coarse.** A repository or mapper is one store. A raw database client (JDBC, an
  `EntityManager`, a DynamoDB client) is one store for the whole program, so every writer through
  it influences every critical reader. Declare `store` lines for the tables that matter if that
  flags too much. State kept somewhere unrecognised (a file, a message queue, another service) is
  not modelled unless you declare it.
- **A writer's callers.** The method that performs a write is flagged; a caller that only passes
  it a value is flagged when the flow reaches it by a call, not otherwise.
- **Guards.** A permission check is a sink like any other: its callers are on a path. Whether each
  caller uses the answer correctly is not checked.
- **Alternatives in a loop.** Where one call can land on several methods, the ones not on a path
  are treated as alternatives. A loop that calls several of them in turn (a listener list) lets an
  earlier one throw before a later one runs; that is missed.
- **Changes through a reference.** A method that mutates an object it got from a field is caught
  only when the field is a collection or array.
- **Frameworks beyond the built-in list.** Spring MVC, Jakarta lifecycle and AspectJ annotations
  are known. Another framework's equivalents need `participant` lines.
- **Library types behind library types.** The hierarchy is known only for the classes you pass in.
  A class that implements a library interface is not matched to calls made through that
  interface's own parents.
- **Reflection by another name.** Opaque calls are matched by a list. A reflective path through a
  library that is not on the list is invisible.
- **Lambda numbering.** Adding a lambda can renumber the ones after it in the same class, which
  shows up as extra changed methods.
- **Kotlin, Scala, Groovy.** They compile to bytecode and should load, but nothing has been tested.

## Roadmap

1. Tell tables apart behind a raw database client, from the SQL or the request it is given.
2. Replace the call rule with a backward data-flow slice from the sink's arguments, so that a
   helper called after the critical call stops counting.
3. Read Spring's ahead-of-time output and bean metadata to turn framework wiring into ordinary
   edges.
4. Accept a library classpath for hierarchy only.
5. A GitHub Action that builds base and head, posts the manifest as a pull request comment and
   sets a status.
6. Serve the same verdicts to editors through a language server.

## Prior art

- Error Prone's `@RestrictedApi`, Rust's `unsafe`, Kotlin's `@RequiresOptIn`: compiler-enforced
  markers at the call site. skimit derives the reach of a marker instead of requiring one per
  caller.
- Google's Safe Coding work: keep risky operations inside a few expert-reviewed abstractions and
  let the type checker cover the rest.
- Meta's Diff Risk Score and RADAR, and similar risk-tiering in other review tools: the same goal,
  reached with learned scores over the diff.
- WALA, Soot and OPAL (call graphs from bytecode), CodeQL and Doop (facts and queries), ArchUnit
  (architecture rules as tests): the machinery this builds on in spirit.
- DO-178C and ISO 26262: criticality levels assigned to components, with rigor scaled to match.

## Brand

Logo, wordmark and social preview are in [docs/brand](docs/brand). The mark is four lines of text
with one picked out: the line you have to read. The four verdict colours are checked for
colour-blind separation in both themes, and the report never relies on colour alone.

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). The most useful
thing you can send is a small Java example where a verdict is wrong, in either direction.

## License

MIT
