# October 2026 experiment: raw data

The write-up is [docs/experiment.md](../../docs/experiment.md). All results here were computed with
skimit 0.1.1 unless a file name says otherwise.

| File | What it is |
|---|---|
| `data-writes.policy` | The one sink rule used for every project |
| `projects.txt` | The projects and the revision each was at |
| `replay/<project>.jsonl` | One line per commit tried: counts of must, should and isolated methods, or why it was skipped |
| `manifests.tar.gz` | The full `skimit diff --json` manifest of every analysed commit |
| `results.json` | Totals per project, as tabulated in the write-up |
| `fix-check.txt` | Output of `scripts/fix-check.py` for each project |
| `security-fixes.csv` | The 21 security fixes, their CVE or advisory number where the commit gives one, and how each was classified |
| `security/<project>/<sha>.json` | The manifest of each security fix |
| `verify/<project>.txt` | Output of `skimit verify` on each project's test suite |
| `verify-0.1.0/<project>.txt` | The same check with the released 0.1.0; `apollo.txt` lists the 15 misses |
| `replay-0.1.0/`, `results-0.1.0.json` | The replay as 0.1.0 classified it |
| `security-0.1.0/apollo-5c930f9b49.json` | The Apollo permission-bypass fix as 0.1.0 classified it |
| `security/logread-illustration.md` | The three log-reading fixes with one read sink added after the fact |
| `analyze/<project>.txt` | Share of each project's methods by verdict at its newest analysed commit |
| `apollo-build.sh` | Build wrapper for Apollo commits that depend on an unpublished snapshot |

A commit is "skipped: did not build" when it or its parent no longer compiles on JDK 21 with the
dependencies available today.
