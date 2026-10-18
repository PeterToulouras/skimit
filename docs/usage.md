# Using skimit

## Run it

Download `skimit.jar` from the [latest release](https://github.com/PeterToulouras/skimit/releases/latest)
(Java 17 or newer), or build it: `mvn package` puts it in `skimit-analyzer/target`, and
`./examples/demo.sh` then runs the whole thing on the example shop.

skimit reads compiled classes: class directories or jars, as many as you like, from any build
tool. Maven puts them in `target/classes`, Gradle in `build/classes/java/main`, one per module.

### What the program does

```
java -jar skimit.jar map target/classes
```

```
## skimit: what this program does

2 ways in (2 routes) can cause 1 effect.

- **writes MONEY via Ledger** from 2 ways in: `POST /checkout`, `POST /refunds`
```

A way in is where work enters the program: an HTTP route, a scheduled job, a message listener, an
event handler, something run at startup, a thread. An effect is what it can do to the world:
write or delete a store's data, call another service, send a message or an email. The map lists
the effects, most consequential first (deletes, then writes, then messages and calls), and who can
cause each. It shows seven, each with the first three ways in that cause it, and says how many more
there are. `--html map.html` writes the same as a page where each effect opens to show every way in
that causes it, and `--json` writes it for tools.

### What a change did

Build the code before and after the change, then compare the two:

```
java -jar skimit.jar diff --base base/target/classes --head head/target/classes
```

```
## skimit: 1 thing needs a person. 1 change to what the system can do. 2 changes to how it does what it already did.

- **Breaks a rule (never "GET *" writes *): GET /reports/sales writes MONEY via Ledger**
- GET /reports/sales now writes MONEY via Ledger (2 → 3 ways in)
- POST /checkout: writes MONEY via Ledger with different data (in PriceCalculator.total)
- POST /refunds: no longer checks Permissions.canRefund before it writes MONEY via Ledger (in RefundHandler.refund)
```

The first sentence is the answer. Then come the lines, in this order:

| Line | Says |
|---|---|
| Breaks a rule, needs a person | The change breaks a rule your team set, or does something a rule says a person must see. |
| now deletes, no longer deletes | A way in gains or loses the ability to delete data. |
| now writes, calls, sends | A way in gains an effect. |
| New way in, Removed | A route, job or listener that causes something appeared or went. |
| is now | A way in answers to a new name and does the same things. |
| no longer writes, calls, sends | A way in lost an effect. |
| how | Same effects, done differently: a new or removed check before an effect, different data going into it, or a new call on the way to it, with the method where that happened. |

Seven lines are shown; the rest fold away. Code that nothing calls is not a way in, so a service
written before its route says nothing until the route exists. A change that does none of these
things, such as a reformatted helper or a renamed class, says "No change in behaviour."

`--html page.html` writes one HTML file: the answer, what the change did, and the map
after it. Pass the source diff too and each line about a method can show that method's code, on
request:

```
git diff base-sha head-sha > changes.diff
java -jar skimit.jar diff --base ... --head ... --diff changes.diff --html page.html
```

skimit finds a method's lines from the source file and line numbers in its bytecode, so the
classes need their debug information (javac and Gradle keep it by default). The diff is only
shown, never analysed: what the lines say comes from the bytecode alone.

`--json` writes the result for tools: `headline`, `broken`, `what`, `how`, and each line's
`kind`, `text`, the `methods` it is about and their `code`. `--exit-code` exits 1 when the change
breaks a rule or needs a person under a review rule, so a CI job can turn that into a required
check.

### Check the map against a real run

The map is a claim. `skimit verify` tests it against what your program really does: run your
tests (or the application) with the recording agent, then compare.

```
java -jar skimit.jar map target/classes --agent-config agent.conf
mvn test "-DargLine=-javaagent:skimit-agent.jar=config=agent.conf,out=trace-%p.txt"
java -jar skimit.jar verify target/classes --trace trace-1234.txt
```

With Gradle, attach the agent with `skimit-agent.gradle` from the release (also in `scripts/`). It
is an init script, so the project's own build files stay as they are:

```
java -jar skimit.jar map build/classes/java/main --agent-config agent.conf
./gradlew test --init-script skimit-agent.gradle \
    -PskimitAgent=skimit-agent.jar -PskimitConfig=agent.conf -PskimitOut=trace-%p.txt
java -jar skimit.jar verify build/classes/java/main --trace trace-1234.txt
```

Whenever the run causes an effect, the agent notes which methods were on the call stack. `verify`
then checks two things: a way in on the stack is one the map says can cause that effect, and any
other method on the stack is one the map has a path from. Anything else is a miss, and `verify`
exits 1, as it does when the run caused no effect at all and so checked nothing. Something a test
does through reflection (calling a private method by name, say) is listed apart: the map does not
follow reflection, so it is not counted as a miss. A clean result covers what the run exercised
and no more, so the more of your effects your tests cause, the more it is worth. Pass the same `--policy` to all three commands if you use
one. A trace from an agent older than 0.2.0 is refused; record the run again.

## Rules and sinks

A policy file, passed with `--policy`, says what counts as an effect and what your team decided
about them:

```
# What counts as an effect, beyond the built-in rule.
sink MONEY com.acme.billing.Ledger#post*

# What people decided.
never "GET *" writes *              # a read route never writes
never *       deletes Account       # nothing deletes accounts
only  OrderService writes Order     # every way to write Order goes through OrderService
review calls *                      # a new way to call another service needs a person
```

### Sinks

With nothing declared, the effects are data writes and deletes through your own repositories,
mappers and DAOs or straight through a common client (JDBC, Spring's `JdbcTemplate`, JPA,
Hibernate, MyBatis, MongoDB, DynamoDB, S3); calls to other services over HTTP (`RestTemplate`,
`RestClient`, `WebClient`, the JDK's and Apache's HTTP clients, OkHttp, and interfaces declared as
clients with Feign, Spring's HTTP interfaces, MicroProfile REST client or Retrofit); and messages
and email sent (Kafka, RabbitMQ, JMS, SQS, SNS, JavaMail). For your own repositories, mappers and
DAOs, a method counts as a write when its name says so, wherever the word is (`save`,
`batchDelete`, `scheduleBatchUpdate`); reads and queries do not count. A store is named after its
class: a call to `OrderRepository.save` writes `Order`. A write whose method name starts with
`delete`, `remove` or `clear` deletes instead; for a sink you declare, so do names starting with
`truncate`, `drop`, `evict` or `purge`. When the built-in rule found every effect, the output says
so.

A sink you declare adds to the built-in rule; `--no-default-sinks` turns the built-in rule off.
Calls out and messages are always effects.

- `sink DOMAIN owner#method` makes calls to a method an effect: `writes MONEY via Ledger`.
- Patterns are `owner#method` globs, where `*` matches any run of characters. In place of the
  method glob, `@write` matches every method whose name says it writes: `save`, `insert`,
  `update`, `delete`, `remove`, `upsert` or `clear`, at the start of the name or as a later word
  (`batchDelete`, `markDeleted`), unless the name starts as a read (`findByUpdateTime`,
  `isDeleted`). For example, `sink DATA *Repository#@write`.
- For code you own, the `@Critical("MONEY")` annotation from the `skimit-annotations` jar (no
  dependencies) does the same as a sink line.

### Rules

Each rule is about ways in and effects, never about code. A way in is written as the map names
it (`POST /orders`, `job demoJobHandler`, `listener OrderEvents.onPaid`); an effect as a verb
(`writes`, `deletes`, `calls`, `sends`) and a target (`Order`, `MONEY via Ledger`, `out over
HTTP`). `*` matches anything, and quotes hold a pattern with spaces in it.

- `never WAY-IN VERB TARGET`: no matching way in may cause a matching effect.
- `only CLASS VERB TARGET`: every way in that causes a matching effect does it through the class
  (its simple name, or a glob of it). A way in that reaches the effect around it breaks the rule.
- `review VERB TARGET`: a way in that gains a matching effect needs a person to look at it.

A change breaks a rule when it makes the rule false where it was true before. Something that
already broke the rule before the change is not held against it, so a rule can be added to a
codebase that does not keep it yet. Broken rules come first in the output, in bold, and are the
`broken` count in the JSON.

Keep the policy file under code-owner review: whoever can edit it decides what counts.

Lines starting `opaque`, `store` or `participant` were read by skimit 0.1; they are ignored now,
with a warning.

## In CI

The GitHub Action builds the pull request and its base, compares them, and writes the result to
the job summary. The page is attached to the run as an artifact, `skimit-` and the job's name. It
reports only: it
fails nothing and posts nothing unless you ask it to.

```yaml
on: pull_request
permissions:
  contents: read
jobs:
  skimit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with: { persist-credentials: false }
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: 21 }
      - uses: PeterToulouras/skimit@v1
        with:
          policy: skimit.policy
```

The action fetches the base commit with git. A public repository needs no token for that, so
`persist-credentials: false` keeps the token out of the checkout. In a private repository, leave
it at its default.

| Input | Default | What it does |
|---|---|---|
| `build` | a quiet compile with Maven, or Gradle when there is no `pom.xml` | the command that compiles the project |
| `policy` | none (the built-in rule) | a policy file with sinks and rules, relative to the repository root |
| `version` | `latest` | the skimit release to run, `0.2.0` or later |
| `fail-on-broken-rule` | `false` | fail the job when the change breaks a rule, or needs a person under one |
| `comment` | `false` | post the result as one pull request comment, updated on each push; needs `pull-requests: write` |
| `base` | the base the pull request merges into | the commit to compare against |
| `artifact` | `skimit-` and the job's name | the name of the artifact the page is attached as; set one per job of a matrix |

The outputs `broken`, `what` and `how` hold the counts, for later steps. If the comparison cannot
run (the pull request or its base does not build, or no classes are found), the job says so in
the summary and as a warning, and still passes: the project's own build job is the one that
should fail.

`@v0` keeps running the review manifest of skimit 0.1.8 for workflows written for it.

On another CI system, `action/run.sh <base-commit>` does the same job from the root of a
checkout. skimit's own repository runs the action on every pull request: see
[.github/workflows/skimit.yml](../.github/workflows/skimit.yml) and its
[policy](../skimit.policy).

## Replay your own history

`scripts/replay.sh` checks out each recent commit that touches main Java sources, builds it and
its parent, and runs `skimit diff` between the two, then counts the commits by what the feed
said:

```
scripts/replay.sh path/to/your/repo your.policy 100     # or - for the built-in rule
```

It builds with Maven or Gradle, whichever the commit uses; `REPLAY_TOOL=gradle` picks Gradle when
a project has both.

---

[Back to the README](../README.md)
