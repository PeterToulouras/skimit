---
name: skimit
description: Before reviewing a Java change line by line, ask what it did to behaviour. Use when reviewing a pull request, a branch or a diff in a Java project that builds with Maven or Gradle; it runs skimit diff between the base and head builds and leads the review with what changed for each route, job and listener.
---

# Review a Java change with skimit

skimit reads the compiled classes before and after a change and says, in a few lines, which
routes, jobs and listeners can now write, delete, call out or send something they could not
before, which changed how they do it, and which rules the team set the change breaks. It is
deterministic and reads bytecode, so it sees through renames, moved classes and reformatting.
Use its lines to decide what to read closely; read the rest only if something there surprises you.

## Run it

1. Get `skimit.jar`: the project's own copy if it has one, otherwise the latest release from
   `https://github.com/PeterToulouras/skimit/releases/latest/download/skimit.jar`. Java 17 or newer.
2. Build the head in place and the base in a worktree, tests off:
   ```
   git worktree add -q --detach /tmp/base <base-sha>
   mvn -q -B -DskipTests compile && (cd /tmp/base && mvn -q -B -DskipTests compile)   # or: gradle -q classes
   ```
3. Compare them, with the project's policy file if there is one (`skimit.policy` at the root):
   ```
   java -jar skimit.jar diff --base /tmp/base/target/classes --head target/classes [--policy skimit.policy] --json skimit.json
   ```
   Pass every module's class directory after `--base` and `--head` in a multi-module build.

## Read the result

`skimit.json` has `headline`, the counts `broken`, `what` and `how`, and `lines`, each with a
`kind`, its `text`, and the `methods` it is about. Kinds, most important first:

| kind | means |
|---|---|
| `BROKEN` | the change breaks a rule the team wrote, or does something a `review` rule says a person must see |
| `DELETES` | a way in gained or lost the ability to delete data |
| `GAINS`, `NEW_WAY_IN` | a way in gained an effect, or a new way in appeared |
| `REMOVED_WAY_IN`, `RENAMED_WAY_IN`, `LOSES` | a way in went, was renamed, or lost an effect |
| `CHECK_GONE` | a check that stood before an effect is gone: read this method first |
| `CHANGED_DELETE`, `CHANGED_HOW` | the same effects, done differently: a new check, different data, a new call on the way |

"No change to what any way in writes, deletes, sends or calls." means exactly that: the change
is in read paths, formatting, structure, logging or configuration. A check that guards a read, an
interceptor's path patterns or a JPA mapping annotation can still have changed; look there yourself
when the diff touches them.

## Use it in the review

- Lead with the headline, then the `BROKEN`, `DELETES`, `GAINS` and `CHECK_GONE` lines verbatim.
  They are written for a reader and name the route and the effect.
- For each line, open the `methods` it names and review those closely. A line about how says
  what changed in words ("now given user, not customer", "100 → 1000"); check that against the
  intent of the change.
- Say what skimit cannot see, so the human knows where to look themselves: reads are not effects,
  SQL in XML mappers and other files is not seen, reflection is not followed (a line says so when
  a change moves an effect behind it), and a call through an interface is credited with every
  implementation. The full list is in the project's `docs/limits.md`.
- Do not restate the diff. If skimit says nothing and the diff is large, say that the change
  touched no way to an effect, and review it for correctness in its own terms.
