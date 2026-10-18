# Contributing

Thanks for looking. skimit makes claims about what code does, so the most valuable contributions
are the ones that test them.

## Reporting something wrong

Open an issue with:

- the smallest Java sources that reproduce it (the tests in `skimit-analyzer/src/test` show how
  little is needed),
- the policy you used, if any,
- what skimit said and what you expected, with a sentence on why.

An effect missing from the map, or a change to behaviour the feed did not mention, is the most
serious kind of bug here. The quickest way to find one is `skimit verify` on your own test suite
(see [docs/usage.md](docs/usage.md)); its output is a ready-made report. A line that says nothing
a person needs is the next most useful thing to send. Check [docs/limits.md](docs/limits.md)
first; if it is one of the known gaps, a comment on the matching issue helps prioritise.

## Building

JDK 17 or newer and Maven.

```
mvn package          # compiles and runs the tests
./examples/demo.sh   # end-to-end run on the example shop
```

## Changing what skimit says

Ways in and effects are found in `Behaviour`, the call graph is `CallGraph`, what a change did is
`Feed`, how it changed an effect is `PathChange`, and rules are `Rules`. A change to one should
come with:

- a test in `BehaviourTest` (or `AnalysisTest` for the call graph) that fails without it,
- a clean `skimit verify` on at least one real test suite, before and after,
- the before and after counts from `scripts/replay.sh` on at least one real history, since a rule
  that looks right on six lines can add a line to half the commits of a project,
- an update to the docs if it changes what a line means or adds an assumption.

## Style

Plain Java, no new runtime dependencies beyond ASM without discussion. Output must be
deterministic: the same classes must always produce the same result, byte for byte. Whatever a
line says, a person who never opens the code must be able to act on it.
