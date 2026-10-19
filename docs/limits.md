# Limits, roadmap and prior art

## Known gaps

What the analysis does not see yet. `skimit verify` on your own tests shows whether any of these
bite on your code.

- **Ways in from other frameworks.** Spring MVC, JAX-RS, `@Scheduled`, XXL-JOB, Kafka, RabbitMQ,
  JMS and SQS listeners, Spring events, startup hooks and threads are recognised. A gRPC service,
  a GraphQL resolver, or a Quarkus, Micronaut or Vert.x handler is not: nothing in the program
  calls it, so it is treated as code nothing calls and left out.
- **Reads are not effects.** Reading data is not on the map, however sensitive. Declare a read as
  a sink (`sink SECRETS com.acme.vault.Vault#get*`) if reading it should count.
- **SQL kept outside the bytecode.** A query in a MyBatis XML mapper, or in any file the classes
  read at run time, is not seen. A change to it alone says nothing; a change to the method that
  runs it does.
- **Tables behind a raw client.** A repository, mapper or DAO is one store, named after it. A raw
  database client (JDBC, an `EntityManager`, a DynamoDB client) is one store per kind of client,
  so writes to different tables through the same client are one effect.
- **Some lines about how say only where.** A line names the calls, fields and values that are new
  or gone, what a call is given differently, or failing those, what else changed. When the
  bytecode cannot say even that, it says "with different data" or "changed the check" and the
  method, and the code is a click away. That was 6% of the lines about how in the
  [replay](evidence.md#what-real-commits-did). Locals are named only when the classes keep their
  names, as Maven and Gradle builds do unless told otherwise.
- **Dispatch the call graph cannot follow.** Reflection, method handles, `ServiceLoader`, and
  calls named in strings other than annotation expressions are not followed. An effect reached
  only that way is missing from the way in that causes it; `skimit verify` lists what a run did
  through reflection apart from its misses. Spring events are the exception: an event listener is
  a way in of its own.
- **Clients built an unusual way.** An interface a library turns into a client is recognised when
  it is declared as one, or handed to a factory call whose result is cast to it. Other ways of
  building a client make its calls look like calls to an interface with no implementation, which
  cause nothing.
- **Every implementation is followed.** A call through an interface may land on any of its
  implementations, so a way in is credited with what all of them do, even when configuration
  picks one.
- **Callbacks are named after the library type.** A method a library calls back is shown as
  `UserDetailsService callback HrService.loadUserByUsername`, which says who calls it but not
  when.
- **`toString`, `hashCode` and `equals` on a plain `Object`.** These calls are not followed into
  the application, just as string concatenation reaches `toString` inside the library unseen.
- **Lambda order within one method.** A lambda is known by its enclosing method and its place
  among that method's lambdas of the same type, matched across a change by its body first.
  Reordering same-typed lambdas inside one method that also changes them can pair the wrong two.
- **Kotlin, Scala, Groovy.** They compile to bytecode that skimit reads. Kotlin was tried on one
  project and needs work on its own idioms; the others are untested.

## Roadmap

1. Tell tables apart behind a raw database client, from the SQL or the request it is given.
2. Ways in from gRPC, GraphQL, Quarkus and Micronaut.
3. A map by area of the system, for codebases with hundreds of ways in.
4. Kotlin.

## Prior art

- API diff tools (japicmp, Revapi) and OpenAPI diff tools: what changed in the surface a program
  offers. skimit adds what each part of that surface does, and how a change affects it.
- ArchUnit, CodeQL and Semgrep: rules about code, checked on every change. skimit's rules are
  about behaviour (which ways in may cause which effects), so they survive refactoring.
- Meta's Diff Risk Score and similar risk scores over a diff: the same aim, reaching a reader with
  less, through a learned score instead of a stated reason.
- WALA, Soot and OPAL (call graphs from bytecode), and program slicing: the machinery this builds
  on in spirit.
- Error Prone's `@RestrictedApi`, Rust's `unsafe`, Google's Safe Coding work: keep the risky
  operations few and named, and let tools cover the rest.

---

[Back to the README](../README.md)
