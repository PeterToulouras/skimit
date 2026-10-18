# Evidence

What has been measured, how, and what it shows. Every number comes from open-source projects and
can be re-run; the raw data is in [experiments/2026-10](../experiments/2026-10). The same commits
and test suites were used to measure skimit 0.1, which sorted methods into review buckets; that
write-up is [experiment.md](experiment.md).

## What real commits did

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="replay-dark.svg">
  <img src="replay-light.svg" alt="What 575 real commits to nine open-source projects did to behaviour" width="780">
</picture>

`scripts/replay.sh` checks out each recent commit that touches main Java sources, builds it and
its parent, and runs `skimit diff` between the two. 758 commits were tried; 183 no longer build
(older commits whose dependencies have disappeared or that do not compile on JDK 21), which leaves
575. Every project ran with the built-in rule and no policy: no sinks declared, no rules.

| Project | Commits | No change in behaviour | Only how it does it | What it can do |
|---|---:|---:|---:|---:|
| XXL-JOB | 195 | 110 | 78 | 7 |
| Spring PetClinic | 84 | 68 | 14 | 2 |
| PetClinic REST | 78 | 44 | 28 | 6 |
| Apollo | 68 | 26 | 28 | 14 |
| litemall | 45 | 26 | 13 | 6 |
| newbee-mall | 37 | 24 | 12 | 1 |
| Spring Framework PetClinic | 26 | 25 | 1 | 0 |
| JPetStore | 22 | 20 | 1 | 1 |
| vhr | 20 | 12 | 3 | 5 |
| **Total** | **575** | **355 (62%)** | **178 (31%)** | **42 (7%)** |

- **62% of commits changed nothing a way in does.** Refactoring, renames, moved classes, logging,
  error messages, read-only endpoints, configuration. skimit says "No change in behaviour." and
  nothing else.
- **7% changed what the system can do**: a route that gained or lost a write or a delete, a new or
  removed endpoint, a renamed one. These are the commits a person most needs to hear about, and
  they are one line each: `Removed: POST /api/pets (it writes Pet)`.
- **31% changed only how it does what it already did**, such as a new check before a write or
  different data going into one: `POST /api/owners/{ownerId}/pets: new check before it writes Pet,
  on ClinicService.findOwnerById; writes Pet now using ClinicService.findOwnerById, no longer new
  Owner`.
- **It fits on a screen.** Of the 220 commits that had something to say, the median said 2 lines
  and 178 (81%) said 7 or fewer, so nothing was folded away. The largest, Apollo's move to a new
  Spring Boot baseline, said 143, broadest first.
- **Bug fixes.** 157 of the commits say they fix something. 64 of them changed behaviour by
  skimit's account (62 how, 2 what). The other 93 changed nothing a way in does: error messages,
  display, logging, configuration, read paths, typos. A fix in a read path is a real fix with no
  effect to report, since reads are not effects.

Of the lines about how, 40% name the calls that are new or gone ("now using
ClinicService.findOwnerById"). The other 60% say where the change is and what kind it is ("with
different data (in PriceCalculator.total)", "changed the check before it deletes Owner"), with
the code a click away. Saying what changed in the data, not only where, is the first item on the
[roadmap](limits.md#roadmap).

The same commits under skimit 0.1 put 39% in "must review" and asked for a careful read of 225
commits; 0.2.0 reports a change to what the system can do in 42.

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

No run did anything the map says it cannot. In Apollo, 15 effects were caused by tests that call
a private method through reflection; `verify` lists them apart, since the map does not follow
reflection. Getting here took two changes to the agent, both found by this check: it records only
the first effect on a call, as the map does (a repository's save and the `EntityManager` call
inside it are one effect), and it leaves out frames before a framework boundary (a test that calls
an endpoint through MockMvc is not on the way to what the endpoint does).

A clean result covers what the tests exercised and no more. Expect a framework skimit has not met
to show up here first, and run `skimit verify` on your own tests to find out.

## Re-run it

```
git clone https://github.com/PeterToulouras/skimit && cd skimit && mvn -q package

# what each commit did, for a project's last 100 commits
scripts/replay.sh path/to/project - 100

# the map against a real run
java -jar skimit-analyzer/target/skimit.jar map target/classes target/test-classes --agent-config agent.conf
mvn test "-DargLine=-javaagent:$PWD/skimit-agent/target/skimit-agent.jar=config=agent.conf,out=trace-%p.txt"
java -jar skimit-analyzer/target/skimit.jar verify target/classes target/test-classes --trace trace-1234.txt
```

The per-commit results, with every line skimit printed, are in
[experiments/2026-10/feed-0.2.0](../experiments/2026-10/feed-0.2.0); the runtime checks in
[experiments/2026-10/verify-0.2.0](../experiments/2026-10/verify-0.2.0). The replay on your own
history, with your own rules, is the number that matters.

---

[Back to the README](../README.md)
