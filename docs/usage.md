# Using skimit

## Run it

Download `skimit.jar` from the [latest release](https://github.com/PeterToulouras/skimit/releases/latest)
(Java 17 or newer), or build it: `mvn package` puts it in `skimit-analyzer/target`, and
`./examples/demo.sh` then runs the whole thing on the example app.

On your own code:

```
# what is critical, and how much of the codebase is connected to it
java -jar skimit.jar analyze path/to/classes --list

# what a change needs: build the base and the head, then compare them
java -jar skimit.jar diff --base base/target/classes --head head/target/classes \
    --json manifest.json --exit-code
```

Add `--html report.html` to either command for the interactive report: what to review and why, the proof
for whichever method you select, and a map of every class coloured by verdict.
It is one self-contained file, so a CI job can attach it to the build.

Inputs are class directories or jars, as many as you like, from any build tool: Maven puts classes
in `target/classes`, Gradle in `build/classes/java/main`, one per module. `--exit-code` returns 1 when anything
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

With Gradle, attach the agent with `skimit-agent.gradle` from the release (also in `scripts/`). It is
an init script, so the project's own build files stay as they are:

```
java -jar skimit.jar analyze build/classes/java/main --policy your.policy --agent-config agent.conf
./gradlew test --init-script skimit-agent.gradle \
    -PskimitAgent=skimit-agent.jar -PskimitConfig=agent.conf -PskimitOut=trace-%p.txt
java -jar skimit.jar verify build/classes/java/main --policy your.policy --trace trace-1234.txt
```

The agent notes every flow that reaches a sink: which methods were on the call stack at that
moment, and which had already run in the same flow (one request, when a servlet dispatch is
involved). `verify` fails if any of them is marked `ISOLATED`, or was on the stack without being
`ON_PATH`. A clean result covers what the run exercised and no more, so the better your tests
reach the critical code, the more it is worth.

## In CI

The GitHub Action builds the pull request and its base, compares them, and writes the manifest to
the job summary. The interactive report is attached to the run. It reports only: it fails nothing
and posts nothing unless you ask it to.

```yaml
on: pull_request
permissions:
  contents: read
jobs:
  skimit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: 21 }
      - uses: PeterToulouras/skimit@v0
```

| Input | Default | What it does |
|---|---|---|
| `build` | a quiet compile with Maven, or Gradle when there is no `pom.xml` | the command that compiles the project |
| `policy` | none (every data write is critical) | a policy file, relative to the repository root |
| `version` | `latest` | the skimit release to run, such as `0.1.5` |
| `fail-on-must-review` | `false` | fail the job when anything lands in must review |
| `comment` | `false` | post the manifest as one pull request comment, updated on each push; needs `pull-requests: write` |
| `base` | the pull request's base | the commit to compare against |

The `must-review` output holds the count, for later steps. skimit's own repository runs the action
on every pull request: see [.github/workflows/skimit.yml](../.github/workflows/skimit.yml).

On another CI system, `action/run.sh <base-commit>` does the same job from the root of a checkout.

Keep the policy file under code-owner review: whoever can edit it decides what counts as
critical.

## Declaring what is critical

With nothing declared, skimit uses a built-in rule: every data write is critical, whether it goes
through your own repositories, mappers and DAOs or straight through a common client (JDBC, Spring's
`JdbcTemplate`, JPA, Hibernate, MyBatis, MongoDB, DynamoDB, S3). The full list is
[data-writes.policy](../examples/policies/data-writes.policy). Reads, queries and plain JDBC
`execute()` are not included.
The output says when it is in use. Declaring any sink of your own replaces it, and
`--no-default-sinks` turns it off.


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

---

[Back to the README](../README.md)
