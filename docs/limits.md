# Limits, roadmap and prior art

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
- **Flows that leave the process.** A call to another service is not a sink unless you declare
  it. Code that guards or prepares such a call is isolated until you do.
- **Expressions in strings.** A bean method named in an annotation expression
  (`@PreAuthorize("@rules.canEdit(#id)")`) is followed. Other expression languages, templates and
  configuration files that name methods are not.
- **`toString`, `hashCode` and `equals` on a plain `Object`.** These calls are not followed into
  the application, just as string concatenation reaches `toString` inside the library unseen.
  A `toString` that writes something critical would be missed.
- **Reflection by another name.** Opaque calls are matched by a list. A reflective path through a
  library that is not on the list is invisible.
- **Lambda order within one method.** A lambda is known by its enclosing method and its place
  among that method's lambdas of the same type, matched across a change by its body first. Adding a
  lambda to a method no longer touches other methods, but reordering same-typed lambdas inside one
  method that also changes them can pair the wrong two.
- **What a moved class carries besides its methods.** A class that moved or was renamed is
  matched with what it was by its methods' bytecode. An annotation on the class itself, such as a
  changed request path on a controller, is not compared; the move is still listed, so its new home
  can be checked.
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

---

[Back to the README](../README.md)
