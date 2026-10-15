# How skimit works

## How a method gets its verdict

Every method with a body gets exactly one, checked in this order.

| Verdict | Meaning | Rule |
|---|---|---|
| `ON_PATH` | It can reach a sink. | Backward over the call graph from each sink. |
| `INFLUENCES` | It takes part in a critical flow without reaching the sink. | Forward from on-path methods, by the six rules below. |
| `UNPROVEN` | No claim is made. | It can reach an opaque call, or a framework calls it at a time the analysis cannot see: a class that extends a library type and that only a framework constructs, a class with a stereotype annotation such as `@Component`, an aspect, an event listener. |
| `ISOLATED` | None of the above. | |

A method influences a critical flow when one of these holds, applied until nothing new is found:

1. **It is called from the flow.** Whatever a flagged method calls can hand it a value or throw
   into it. One exception: where a single call can land on several methods and one of them is on a
   path (a router calling one of many handlers), the others are alternatives to the critical
   branch and are left alone.
2. **It writes a field the flow reads.** Tracked per field and, for accesses on `this`, per object
   class. Reading a collection or array field counts as writing it, since a reader can change it.
3. **It writes a store the flow reads.** A repository, mapper, DAO, database client or declared
   store. This is how a settings screen that turns on automatic billing is tied to the billing
   job that reads the setting, though neither calls the other.
4. **It belongs to the data that reaches the sink.** The types handled by a sink and by its direct
   callers (in their signatures, field accesses, casts and allocations), and everything those
   objects hold in their fields, are flagged whole, because a framework may fill or read them by
   reflection. A class the caller only calls methods on is a collaborator, not data.
5. **The framework runs it alongside.** On the object of an on-path method: model attributes and
   binders before a request handler, lifecycle callbacks, injected setters.
6. **It wires the flow in.** A method that creates the lambda or method reference through which an
   on-path method is called.

Rules 3 to 5 exist because `skimit verify` showed real runs contradicting the analysis without
them.

The call graph analyses each instance method once per concrete class it can run on. A call on
`this` binds to that class alone, so a base-class method that calls an abstract hook reaches only
the hook of the subclass it is running in. A call on any other object may land on any concrete
subtype of its static type; that is why interface-typed dependency injection needs no knowledge
of the wiring. A lambda typed as one of your own interfaces is treated like any other
implementation of it: it is called where the interface is called. A lambda or object handed to a
library type is charged to the method that creates it, since the library may call it back.
A method that receives such a callback as a parameter of a library type and runs it or hands it
on to a library also calls it, so a wrapper like `withMetrics(Supplier<T> s)` is on the path of
the lambda it is given, and `parse(File f, ContentHandler handler)`, which gives the handler to an
XML reader, calls the handler its callers construct: the methods of that class which implement
`ContentHandler` (read from the JDK when it has the type; otherwise every method the class
overrides from a library). This follows the callback through wrappers that pass it on or wrap it
again in their own lambda, but only through parameters: a `Supplier.get` or `setContentHandler`
on a field or a local is not linked to anything. A class that extends a library type and that
only a framework constructs is still `UNPROVEN`: no parameter carries it, so this rule does not
apply to it.

Separately, every top-level class gets a position from the class dependency graph: `LEAF` when
nothing depends on it, `ROOT` when at least 10% of classes do (`--root-ratio`), `TRUNK` otherwise.
Position measures how far a bad abstraction would spread; the verdict measures what a bad body
could break. A diff reports position moves, new dependencies between classes and public API
changes in their own section, so a reviewer who cares about structure reads that list instead of
every body.

A diff also lists untouched methods whose verdict moved because of a change elsewhere. One that
was isolated and now takes part in a critical flow is reported as newly reachable, with the reason
it moved: a reviewer was told its body could be skimmed, so it must be read once. These are not
changes and do not count towards the changed methods.

A change counts only if the method's bytecode changed. Comments, formatting, moved code and
renamed locals are not changes. javac numbers lambda bodies across the whole class, so a lambda
is known instead by its enclosing method and its place among that method's lambdas of the same
type, and is matched across the change by an identical body first. Adding a lambda to one method
leaves the others, and their lambdas, unchanged.

---

[Back to the README](../README.md)
