# Contributing

Thanks for looking. skimit makes claims about code, so the most valuable contributions are the
ones that test them.

## Reporting a wrong verdict

Open an issue with:

- the smallest Java sources that reproduce it (the tests in `skimit-analyzer/src/test` show how
  little is needed),
- the sinks you declared,
- the verdict you got and the one you expected, with a sentence on why.

A method marked `ISOLATED` that can in fact affect a sink is the most serious kind of bug here.
The quickest way to find one is `skimit verify` on your own test suite (see
[docs/usage.md](docs/usage.md)); its output is a ready-made report. Check
[docs/limits.md](docs/limits.md) first; if it is one of the known gaps, a comment on the matching
issue helps prioritise.

## Building

JDK 17 or newer and Maven.

```
mvn package          # compiles and runs the tests
./examples/demo.sh   # end-to-end run on the example app
```

## Changing the rules

Every rule that decides a verdict lives in `Analysis.propagate` and `CallGraph`. A change to one
should come with:

- a test in `AnalysisTest` that fails without it,
- a clean `skimit verify` on at least one real test suite, before and after,
- the before and after numbers from `skimit analyze` on at least one real codebase, since a rule
  that looks right on six lines can flag or clear half a project,
- an update to the README if it changes what a verdict means or adds an assumption.

## Style

Plain Java, no new runtime dependencies beyond ASM without discussion. Output must be
deterministic: the same classes must always produce the same manifest, byte for byte.
