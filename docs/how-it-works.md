# How skimit works

skimit reads compiled classes, builds a call graph, and from it works out two things: the ways
into the program and the effects each can cause. A change is then described by what it did to
those, and to how each effect comes about. Nothing is learned or scored; the same classes always
give the same answer.

## Ways in

A way in is a method that something outside the program's own code calls:

- **Routes.** Spring MVC mappings, with the class's `@RequestMapping` path and mappings inherited
  from supertypes and interfaces, and JAX-RS resources. A route is named by its verb and path,
  `POST /api/orders`, so moving its handler to another class or method does not change it.
- **Jobs, schedules, listeners and events.** `@XxlJob` (named by its job name), `@Scheduled`,
  Kafka, RabbitMQ, JMS and SQS listeners, `@EventListener` and `@TransactionalEventListener`.
- **Startup and shutdown.** `@PostConstruct`, `main`, and the lifecycle hooks of library types
  (`afterPropertiesSet`, `contextInitialized`, `destroy` and the like).
- **Threads.** The `run()` or `call()` of a `Runnable`, `Thread`, `Callable` or `TimerTask`, named
  after the method that starts it (`thread JobScheduleHelper.start`). Work done at startup stops at
  the threads it starts, and each thread is a way in of its own; a request, job or listener owns
  what it hands to a pool.
- **Callbacks.** A method of a class that extends a library type, which nothing in the program
  calls but the library will.

Code that nothing calls and no known framework runs is not a way in: a service written before its
route says nothing until the route exists. A way in that causes no effect is not shown.

## Effects

An effect is a call that changes something outside the process, found where the call is made:

- **writes** and **deletes** a store: a write through a repository, mapper or DAO, or a common
  database client, or a sink you declared. A store is named after its class (`OrderRepository` is
  `Order`), and a write whose method name starts with `delete`, `remove`, `clear` (or, for a sink
  you declared, `truncate`, `drop`, `evict` or `purge`) deletes.
- **calls** another service: an HTTP client call (`calls out over HTTP`), or a call through an
  interface a library turns into a client (`calls PaymentsApi (remote)`). Such an interface is
  declared as one (Feign, Spring's HTTP interfaces, MicroProfile REST client, Retrofit), or is
  handed to a library factory in the same build and the result used as it. At such a call, the
  interface's implementations in other builds are what the other service runs, so they are not
  followed.
- **sends** a message or an email (Kafka, RabbitMQ, JMS, SQS, SNS, JavaMail).

From each way in, the call graph is walked forward until an effect is reached on each path; the
effects found, with one chain of calls for each, are what the way in can cause. Code from a build
the way in's own build does not depend on never runs in its process, so it is not followed.

## The call graph

Each instance method is analysed once per concrete class it can run on. A call on `this` binds to
that class alone, so a base-class method that calls an abstract hook reaches only the hook of the
subclass it is running in. A call on any other object may land on any concrete subtype of its
static type; that is why interface-typed dependency injection needs no knowledge of the wiring.

A lambda typed as one of your own interfaces is treated like any other implementation of it: it is
called where the interface is called. A lambda or object handed to a library type is charged to
the method that creates it, since the library may call it back. A method that receives such a
callback as a parameter of a library type and runs it or hands it on to a library also calls it,
so a wrapper like `withMetrics(Supplier<T> s)` leads to what the lambda it is given does, and
`parse(File f, ContentHandler handler)`, which gives the handler to an XML reader, calls the
methods of the handler its callers construct that implement `ContentHandler`. This follows the
callback through wrappers that pass it on or wrap it again, but only through parameters.

A bean method named in an annotation expression (`@PreAuthorize("@rules.canEdit(#id)")`) is
called by the annotated method. A Spring Data repository's own methods are wired to the class
named after the repository or after the fragment interface, with `Impl`, as Spring Data wires
them, so a call through the repository can land there. `toString`, `hashCode` and `equals` called on a plain `Object`
are not followed into the application.

## What a change did

**Which methods changed.** A change counts only if a method's bytecode changed. Comments,
formatting, moved code and renamed locals are not changes. javac numbers lambda bodies across the
whole class, so a lambda is known instead by its enclosing method and its place among that
method's lambdas of the same type, and matched across the change by an identical body first.

A class that moved or was renamed is matched with what it was: a class that exists only after the
change is paired with one that existed only before it when at least half the methods of the larger
are identical once the new name is read as the old one, and either two of them are ordinary
methods or one name contains the other. Once paired, every method is compared with the method it
was, and code elsewhere that only switched to the new name is not a change.

A method renamed and nothing more is matched the same way: a method that exists only after the
change is paired with the one method of its class that existed only before it and has the same
signature and the same body; a supertype that declares the new name where it declared the old one
is renamed with it. A field renamed and nothing more is paired with the one field of its type and
access that went, or, when several were renamed together, with the old name it is closest to. An
anonymous class whose methods have exactly the bodies of one that went is that class under a new
number, since javac renumbers them when one is inserted. A method whose only edit is one of these
new names is not a change.

**Which ways in are the same.** A way in keeps its name across the change, or keeps its method
when the method moved with its class. A route renamed along with its method (`/jobinfo/remove` to
`/jobinfo/delete`) is still the same way in when it is the only one of its kind that does exactly
the same things; among several alike, those that kept their last word are paired. A handler that
only got a new name and path runs the same code, and says nothing more than that.

**What it can do.** For each way in, the effects after the change are compared with those before:
an effect gained or lost is a line. A way in that was there before and caused nothing, and now
causes something, gains it. A way in that lost an effect because a changed method on its way now
calls through reflection or a method handle says so on the line, since the map cannot see past
that call and the effect may well still happen.

**How it does what it already did.** For each changed method that a way in runs on the way to an
effect it already had, skimit slices the method twice, before and after, by data flow over its
bytecode:

- the **data slice**: the instructions that compute the values going into the calls toward the
  effect, and anything else done to those objects before the last of those calls
  (`pet.setOwner(owner)` before `save(pet)`; not `toPetDto(pet)` after it, and not a getter, which
  does nothing to the object). Numbers, flags, strings and boxed values have no identity: passing
  the same id or the same name to two calls does not join them, since nothing done to one can
  change it.
- the **check slice**: the branches with a call toward the effect down one side only, and what
  they test. A call to a method that never returns, a helper that always throws, ends its side.

Each slice is compared as normalised text: a parameter known by its type and its place among the
parameters of that type, other locals left out (a local is a name for a value, not a step), lambda
numbers dropped, classes known by their simple names, a call to a method the same whether it is
made directly or through its type, and reads of parameters, fields and constants allowed to sit
in another order. So a class that only moved package (`javax` to `jakarta`), a private method made
visible to a proxy, a value put in a local first, or a new parameter of another type that the
slice does not use is not a change. If neither slice
changed, the change made no difference to that effect and there is no line. If one did, the line
says which, and what changed in it as far as the bytecode can say:

- the calls, fields and values that are new or gone: "new check before it writes Pet, on
  ClinicService.findOwnerById", "no longer checks stopped before it writes Order", "changed the
  check before it writes Order: 100 → 1000";
- what a call that is still made is given differently, in the names the source gave its locals:
  "ClusterService.createCluster now also given operator", "OrderRepository.save now given
  parent, not id", "XxlJobRemotingUtil.postBody now returns Response, not ReturnT";
- when none of those changed, what did: a call made a different number of times ("now called
  once, not twice"), a library call or a field swapped for another ("List.size →
  List.isEmpty"), the same steps in another order, the kind of logic ("a comparison changed",
  "a calculation changed"), or what the method itself now takes or returns.

A changed method that does not itself lead to the effect, but whose result goes into the data or
the check of a method that does, is a line too, and says what changed in it in the same terms: the
calls and fields it uses, what its branches test and the values it uses, leaving out logging and
the exceptions it throws, with the messages they are built with ("writes MONEY via Ledger with
different data (in PriceCalculator.total: now checks Order.quantity against 10; now uses 90 and
100)"). A class that moved package and changed too is compared with what it was. A helper that is
new says so, and has no line of its own when the line about its caller names it. Methods longer
than 4,000 instructions are not sliced: a change in one, or in a method one of them calls on the
way to an effect, is reported without detail.

The effect's own code can change too, though the walk stops at it: a changed `Ledger.post` or
repository `save` is a line of its own, "the code that writes Order changed", with what changed in
it in the same terms.

Lines with the same words are merged across ways in and across methods, so thirty helpers behind
one write that changed alike are one line; when a few changed in different ways, each says how.
A check that is gone, on the way to an effect or in a helper that decided it, comes before every
other line about how.

**Rules.** The never and only rules are checked on the map before and after the change, and a
breach that is new is a line; a review rule flags a way in that gains a matching effect. See
[usage](usage.md#rules).

## Checking against a real run

The recording agent instruments the application's classes. On every call to a method the map
counts as an effect, it records the effect and each method on the call stack. Like the map, it
stops at the first effect: one caused inside another (the `EntityManager` a repository's save
uses) is not recorded again. Frames before a framework boundary, such as Spring's servlet dispatch,
are left out, since nothing in the code leads from a test that calls MockMvc to what the request
does. `skimit verify` then checks that each way in on a stack can cause the effect according to
the map, and that each other method on it is one the map has a path from to that effect.

---

[Back to the README](../README.md)
