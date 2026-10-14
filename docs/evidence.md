# Evidence

What has been measured, how, and what went wrong along the way.

## How the verdicts are checked

Every number in this section and the next comes from an open-source project and can be re-run.
The full account, with what went wrong, is in [docs/experiment.md](experiment.md); the raw
data is in [experiments/2026-10](../experiments/2026-10).

### Against real runs

Each project's own test suite was run with the recording agent attached, test code included so
that a whole test counts as one flow. Every project used the same rule: any write through a
repository, mapper or DAO is a sink.

| Project | Seen during development | Tests | Sink calls | Methods in critical flows | Misses, 0.1.0 | Misses, 0.1.1 |
|---|---|---:|---:|---:|---:|---:|
| Spring PetClinic | yes | 79 | 22 | 76 | 0 | 0 |
| Spring Framework PetClinic | yes | 75 | 21 | 75 | 0 | 0 |
| JPetStore | yes | 98 | 49 | 82 | 0 | 0 |
| PetClinic REST | no | 237 | 93 | 178 | 0 | 0 |
| Apollo | no | 1,404 | 918 | 673 | 15 | 0 |
| JHipster sample app | no | 216 | 200 | 206 | 0 | 0 |

Apollo, the first large project to go through this check, showed 15 methods that 0.1.0 got wrong:
9 it had called isolated and 6 it had flagged but ranked too low. The causes were servlet filters
that hand on to the chain, a JPA converter, Spring's reflection helper, and an aspect's
constructor. Those gaps are closed in 0.1.1. The JHipster sample app was run only after the fixes,
and was clean.

This was the second round. Before 0.1.0, the same check on the three development projects found
twenty-three misses from four other gaps (methods Spring runs before a request handler, dispatch
by URL, components that code also constructs by hand, objects held by the data being saved), all
closed before release. Expect a framework skimit has not met to add to the list, and run
`skimit verify` on your own tests to find out.

### Against bug fixes and security fixes

- **Bug fixes.** `scripts/fix-check.py` takes the commits in the replay below that say they fix
  something (157 of 575) and asks of each method a fix changed in flagged code: when that method
  was last changed, how had the change been classified? Of 191 such methods with an earlier change
  in the replayed window, 96 had been "must review", 95 "should review" and none "isolated". This
  mostly shows the verdicts are stable; it would have caught a bad rule, it does not prove a good
  one.
- **Fixes in isolated code.** 30 of the 157 fix commits changed only isolated methods. Isolated
  means "cannot affect what the sinks are asked to do", not "cannot contain a bug".
- **Security fixes.** 21 real vulnerability fixes were picked; 3 no longer build. Of the 18: 12
  were must review, 1 should review, 4 isolated, and 1 (CVE-2025-7787) changed only a sample module
  outside the analysed code. All four isolated ones are in code that only reads, which a writes-only rule
  does not treat as critical: if reading something is sensitive, declare the read as a sink. One fix exposed
  a gap, now closed: a permission check called from a `@PreAuthorize` expression string.

## What the measurements say

### How much of a codebase is connected to its sinks

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="verdicts-dark.svg">
  <img src="verdicts-light.svg" alt="Share of methods by verdict in six open-source codebases" width="780">
</picture>

| Codebase | Sinks | Methods | On path | Influences | Unproven | Isolated |
|---|---|---:|---:|---:|---:|---:|
| [Spring PetClinic](https://github.com/spring-projects/spring-petclinic) | repository saves | 116 | 5.2% | 57.8% | 12.9% | 24.1% |
| [Spring Framework PetClinic](https://github.com/spring-petclinic/spring-framework-petclinic) | repository saves | 179 | 7.8% | 69.3% | 6.1% | 16.8% |
| [JPetStore](https://github.com/mybatis/jpetstore-6) | placing an order | 248 | 1.6% | 63.3% | 1.2% | 33.9% |
| [XXL-JOB](https://github.com/xuxueli/xxl-job), core and admin | running or killing a job on an executor | 928 | 2.2% | 66.3% | 2.4% | 29.2% |
| Broadleaf Commerce 3.1.15, framework and common | payment gateway transactions | 7,785 | 1.6% | 62.3% | 15.3% | 20.8% |
| Kill Bill 0.25.4, thirteen modules | payment plugin calls that move money | 10,002 | 2.7% | 68.4% | 18.6% | 10.3% |

The sinks behind this table are one reasonable choice per project, in
[examples/policies](../examples/policies). Shares are of methods with a body. The two largest were analysed from their published jars; the
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
  <source media="(prefers-color-scheme: dark)" srcset="replay-dark.svg">
  <img src="replay-light.svg" alt="What 575 real commits to nine open-source projects needed, under one fixed rule" width="780">
</picture>

`scripts/replay.sh` checks out each recent commit that touches main Java sources, builds it and
its parent, and runs `skimit diff` between the two. Run over nine projects' histories with one
rule for all of them (any write through a repository, mapper or DAO is a sink):

| Project | Commits analysed | No code change | Isolated only | Should review | Must review |
|---|---:|---:|---:|---:|---:|
| XXL-JOB | 195 | 32 | 11 | 55 | 97 |
| Spring PetClinic | 84 | 31 | 14 | 20 | 19 |
| PetClinic REST | 78 | 12 | 12 | 23 | 31 |
| Apollo | 68 | 1 | 14 | 25 | 28 |
| litemall | 45 | 4 | 18 | 4 | 19 |
| newbee-mall | 37 | 4 | 8 | 9 | 16 |
| Spring Framework PetClinic | 26 | 5 | 4 | 13 | 4 |
| JPetStore | 22 | 13 | 0 | 6 | 3 |
| vhr | 20 | 2 | 2 | 8 | 8 |
| **Total** | **575** | **104** | **83** | **163** | **225** |

- **33% of commits needed no body review** (187 of 575): either nothing in the bytecode changed
  (comments, formatting, renamed locals) or every changed method was isolated.
- **39% contained something on a critical path** (225 of 575), and the manifest says which methods
  and why. The remaining 28% touched code that takes part in a critical flow without reaching the
  sink.
- **The projects differ a lot**, from 20% needing no body review (vhr) to 59% (JPetStore).

Limits of this data: 183 of the 758 commits tried no longer build, mostly older ones whose
dependencies have disappeared, so the samples lean recent. "Every database write" is a blunt rule,
not the maintainers' own choice of what matters; narrower sinks leave more code isolated. The
replay on your own history, with your own sinks, is the number that matters:

```
scripts/replay.sh path/to/your/repo your.policy 100
scripts/fix-check.py path/to/your/repo path/to/your/repo-replay
```

It builds each commit with Maven or Gradle, whichever the commit uses. When a project has both,
`REPLAY_TOOL=gradle` picks Gradle.

---

[Back to the README](../README.md)
