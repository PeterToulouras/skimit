# Evidence

What has been measured, how, and what it shows. Every number comes from open-source projects and
can be re-run; the raw data is in [experiments/2026-10](../experiments/2026-10). The same commits
and test suites were used to measure skimit 0.1, which sorted methods into review buckets; that
write-up is [experiment.md](experiment.md).

## What real commits did

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="replay-dark.svg">
  <img src="replay-light.svg" alt="What 580 real commits to nine open-source projects did to behaviour" width="780">
</picture>

`scripts/replay.sh` checks out each recent commit that touches main Java sources, builds it and
its parent, and runs `skimit diff` between the two. The `measure` workflow does this on a GitHub
runner for every project, so the numbers can be re-made by anyone with a push. 804 commits were
tried; 224 no longer build (older commits whose dependencies have disappeared or that do not
compile on JDK 21), which leaves 580. Every project ran with the built-in rule and no policy: no
sinks declared, no rules. The October run on one machine, which 0.2.2 reported, analysed 575:
litemall builds five more commits on the runner, and XXL-JOB's executor samples are built there,
which that run left out, so its `httpJobHandler` job counts as a way in.

| Project | Commits | No change | Only how it does it | What it can do |
|---|---:|---:|---:|---:|
| XXL-JOB | 195 | 113 | 71 | 11 |
| Spring PetClinic | 84 | 69 | 13 | 2 |
| PetClinic REST | 78 | 48 | 24 | 6 |
| Apollo | 68 | 31 | 23 | 14 |
| litemall | 50 | 31 | 9 | 10 |
| newbee-mall | 37 | 26 | 10 | 1 |
| Spring Framework PetClinic | 26 | 24 | 2 | 0 |
| JPetStore | 22 | 21 | 0 | 1 |
| vhr | 20 | 12 | 3 | 5 |
| **Total** | **580** | **375 (65%)** | **155 (27%)** | **50 (9%)** |

- **65% of commits changed nothing a way in does.** Refactoring, renames, moved classes, logging,
  error messages, read-only endpoints, configuration. skimit says "No change to what any way in
  writes, deletes, sends or calls." and nothing else. [Sixty commits read by hand](#read-by-hand)
  say how far to trust that sentence.
- **9% changed what the system can do**: a route that gained or lost a write or a delete, a new or
  removed endpoint, a renamed one. These are the commits a person most needs to hear about, and
  they are one line each: `Removed: POST /api/pets (it writes Pet)`.
- **27% changed only how it does what it already did**, such as a new check before a write or
  different data going into one: `POST /api/owners/{ownerId}/pets: new check before it writes Pet,
  on ClinicService.findOwnerById; writes Pet now using ClinicService.findOwnerById, no longer new
  Owner`.
- **It fits on a screen.** Of the 205 commits that had something to say, the median said 2 lines
  and 179 (87%) said 7 or fewer, so nothing was folded away. The largest, Apollo's move of its
  portal to a new version of its API, said 99, broadest first.
- **Bug fixes** (measured under 0.2.2). 157 of the commits say they fix something. 64 of them
  changed behaviour by skimit's account (62 how, 2 what). The other 93 changed nothing a way in does: error messages,
  display, logging, configuration, read paths, typos. A fix in a read path is a real fix with no
  effect to report, since reads are not effects.

Of the 788 lines about how, 157 say that a check which stood before an effect is gone, and come
first. 91% say what changed: the calls, fields and values that are new or gone ("changed the check
before it deletes XxlJobLog (in XxlJobAdminConfig.getLogretentiondays: 7 → 3)"), what a call is
given differently ("ClusterService.createCluster now also given operator"), or failing those what
else did ("RabbitTemplate.convertAndSend now called once, not twice"). 2% say only the kind of
logic that changed ("a comparison changed"). The other 7% say only where ("changed the check
before it writes XxlJobInfo (in XxlJobServiceImpl.update)", where a validation moved under a new
condition), with the code a click away; most come from the two commits that moved a project from
Java 8 to 17, which changes the bytecode throughout without changing the code. Under 0.2.2 there
were 977 lines about how; 0.2.3 reads renamed fields, methods and anonymous classes by their old
names and leaves out what is done to an object after the write, which took 17 commits from "how"
to nothing and said nothing true less often (see [read by hand](#read-by-hand)).

The same commits under skimit 0.1 put 39% in "must review" and asked for a careful read of 225
commits; 0.2 reports a change to what the system can do in 50.

## Against real runs

Each project's own test suite was run with the recording agent attached, test code included, and
`skimit verify` checked every effect the run caused against the map: each way in on the stack
must be one the map says can cause it, and each other method one the map has a path from.

| Project | Tests | Effects caused | Different effects | Misses |
|---|---:|---:|---:|---:|
| Spring PetClinic | 79 | 26 | 2 | 0 |
| Spring Framework PetClinic | 75 | 21 | 3 | 0 |
| JPetStore | 98 | 49 | 5 | 0 |
| PetClinic REST | 237 | 93 | 13 | 0 |
| JHipster sample app | 216 | 240 | 12 | 0 |
| Apollo | 1,404 | 1,434 | 42 | 0 |

No run did anything the map says it cannot. Spring PetClinic and PetClinic REST were run again
under 0.2.3 by the `measure` workflow, with the same result (the outputs are in
`experiments/2026-10/verify-0.2.3`). In Apollo, 15 effects were caused by tests that call
a private method through reflection; `verify` lists them apart, since the map does not follow
reflection. Getting here took two changes to the agent, both found by this check: it records only
the first effect on a call, as the map does (a repository's save and the `EntityManager` call
inside it are one effect), and it leaves out frames before a framework boundary (a test that calls
an endpoint through MockMvc is not on the way to what the endpoint does).

A clean result covers what the tests exercised and no more. Expect a framework skimit has not met
to show up here first, and run `skimit verify` on your own tests to find out.

## Known changes, injected

The replay says how much skimit says; it cannot say whether what it says is right, since nobody
knows what each of those commits really did. `scripts/recall.py` asks the other question: given a
change whose meaning is known, does skimit say something, and does it stay silent when nothing
changed? It applies 25 edits to the example shop, written before any result was seen, compiles each
and compares it with the shop as it was. The table is in
[experiments/2026-10/recall/shop.md](../experiments/2026-10/recall/shop.md); with skimit 0.2.3:

| Edits | Said | Silent |
|---|---:|---:|
| The example shop: 15 that change what a way in does, or how | 15 | 0 |
| The example shop: 10 that change the code and nothing else | 5 | 5 |
| The example shop: 1 in a read path, left out by design | 0 | 1 |
| PetClinic REST: 11 that change what a way in does, or how | 11 | 0 |
| PetClinic REST: 6 that change the code and nothing else | 1 | 5 |
| PetClinic REST: 1 in a read path, left out by design | 0 | 1 |

The PetClinic REST edits (`experiments/2026-10/recall/petclinic-rest.json`) are applied by the
`measure` workflow to the project at the revision of the replay; the table is
[petclinic-rest.md](../experiments/2026-10/recall/petclinic-rest.md). They include a check removed
before a delete, a GET that starts deleting, a type lookup dropped from a save, an owner's pets
deleted with the owner, a change inside a JPA repository's `save`, and a change inside a Spring
Data fragment class wired by its name.

- **Every change to behaviour is said**, and in the words a reader needs: a removed permission
  check, a check inverted, a report route that starts moving money and the rule it breaks, a new
  route, a lost write, a doubled price, a refund that charges, a refund sent to the wrong account
  ("Ledger.post now given user, not customer"), a new check with its threshold, a widened
  permission in a helper, a call out, a second charge, and a change inside the ledger's own code.
  The write moved behind reflection reads as lost, which is what the map sees, and the line says
  why: "RefundHandler.refund now calls Method.invoke, which skimit does not follow".
- **Six refactorings produce a line about how.** Extracting the charge into a private helper,
  pulling the total into a local when that reorders two calls, adding an unused parameter,
  swapping the two factors of a product, replacing a field with a local, and swapping two
  independent setters each say the write is done differently. None of them is, so these are the
  lines a reader learns to skip; the 27% of commits that "changed only how" in the replay includes
  some of this kind. Renaming a method, a field or a local, moving a class, passing a value
  through a local, reformatting and adding a log line are silent.
- **The first run of this table, under 0.2.2, found the gaps that 0.2.3 closed**: the change
  inside the effect was missed, the wrong-account refund named the permission check instead of
  the account, the reflection line gave no reason, and the method rename produced a line. The
  changes are in the [changelog](../CHANGELOG.md).

## Read by hand

The replay counts what skimit said; it cannot say whether it was right. Sixty of the 575 commits
of the October run were drawn by a fixed shuffle, thirty that skimit 0.2.2 said nothing about and thirty it said only
how an effect changed, and each was read against its diff. The table, with a note on every
commit and what 0.2.3 says on the same one, is
[experiments/2026-10/recall/labels.md](../experiments/2026-10/recall/labels.md).

| Thirty commits that got… | right | wrong or missed | noise | out of scope |
|---|---:|---:|---:|---:|
| the "no change" sentence | 22 | 6 | 0 | 2 |
| lines about how | 17 | 7 | 6 | 0 |

- **Six silent commits had changed behaviour a reviewer would want.** Four were checks that guard
  reads: a login interceptor extended to the order routes, an interceptor comparing a different
  path, an access-key check in a filter, and a JPA relation made transient. One added a route
  that calls out through a client library skimit does not know. One changed the text a job
  writes to its log table through a builder. None is a write, delete, call or message that skimit
  recognises, so 0.2.3 says what it means: "No change to what any way in writes, deletes, sends
  or calls." The two out of scope were in a module the replay does not build.
- **Seven lines were not true**, and each was a bug fixed in 0.2.3: a mapping done after the
  write counted as its data (twice), a getter in a log line counted as work on the object, 39 lines
  for fields renamed from `Dao` to `Mapper`, three enum constants with a typo fixed, an anonymous
  class renumbered by an insertion, and checks that moved from `return` to a helper that throws.
- **Six lines were true and useless**: a project's helper swapped for a library's (`size() > 0`
  for `isEmpty()`, a private `copy` for `IOTool.copy`), and classes renamed while also being
  reworked. These remain; the [limits](limits.md#known-gaps) list them.
- **Seventeen were what a reviewer needed**, among them every security fix in the sample: four
  permission checks added, two XSS fixes, two IDOR fixes, and a validation moved into a generated
  API, which reads as the explicit check being gone.

## The security fixes, again

The [0.1 experiment](experiment.md#security-fixes) picked 21 commits from these histories that fix
a vulnerability. Fifteen of them build and fall inside the replay. Under 0.2.2, with no rules set,
11 produced only lines about how, and 4 said "No change in behaviour." Of those four, two fix
reads, which are not effects; one changed a sample module outside the code analysed; and one,
XXL-JOB's second commit tightening its job and log API, changed a scheduling helper that 0.1 had
put in "should review" and 0.2 does not mention.

The eleven are the reason a check that is gone now comes first. In the fix for CVE-2024-42681 the
line that mattered, "/user/remove: no longer checks HttpServletRequest.getAttribute before it
deletes XxlJobUser; new check before it deletes XxlJobUser, on PermissionInterceptor.getLoginUser",
sat among 13 lines about how, where seven lines fold the rest away. Without a rule, a security fix
reads like any other change to how an effect happens; with a `review` rule on the effects that
matter, or a `never` rule, it is the first line and the job can fail on it.

## Re-run it

```
git clone https://github.com/PeterToulouras/skimit && cd skimit && mvn -q package

# what each commit did, for a project's last 100 commits
scripts/replay.sh path/to/project - 100

# known changes injected into the example shop
scripts/recall.py

# the map against a real run
java -jar skimit-analyzer/target/skimit.jar map target/classes target/test-classes --agent-config agent.conf
mvn test "-DargLine=-javaagent:$PWD/skimit-agent/target/skimit-agent.jar=config=agent.conf,out=trace-%p.txt"
java -jar skimit-analyzer/target/skimit.jar verify target/classes target/test-classes --trace trace-1234.txt
```

The per-commit results, with every line skimit printed, are in
[experiments/2026-10/feed-0.2.2](../experiments/2026-10/feed-0.2.2); the runtime checks in
[experiments/2026-10/verify-0.2.0](../experiments/2026-10/verify-0.2.0). The replay on your own
history, with your own rules, is the number that matters.

---

[Back to the README](../README.md)
