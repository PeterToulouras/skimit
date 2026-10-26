package dev.skimit.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BehaviourTest {
  // The few framework annotations the tests need, under their real names.
  static final String REQUEST_MAPPING = """
      package org.springframework.web.bind.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface RequestMapping { String[] value() default {}; }""";
  static final String POST_MAPPING = """
      package org.springframework.web.bind.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface PostMapping { String[] value() default {}; }""";
  static final String POST_CONSTRUCT = """
      package jakarta.annotation;
      @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
      public @interface PostConstruct { }""";
  static final String REPOSITORY = """
      public class OrderRepository {
        public void save(long id) { }
        public void deleteById(long id) { }
        public long count() { return 0; }
      }""";
  static final String SERVICE = "public class OrderService { OrderRepository repo; void place(long id) { repo.save(id); } }";
  static final String CONTROLLER = """
      import org.springframework.web.bind.annotation.*;
      @RequestMapping("/api")
      public class OrderController {
        OrderService service;
        @PostMapping("/orders") public void place(long id) { service.place(id); }
        @PostMapping("/orders/count") public long count() { return 0; }
      }""";
  static final String CLEANUP = """
      public class Cleanup {
        OrderRepository repo;
        @jakarta.annotation.PostConstruct
        public void start() {
          new Thread(new Runnable() { public void run() { repo.deleteById(1); } }).start();
        }
      }""";

  static String[] program(String... extra) {
    List<String> all = new java.util.ArrayList<>(List.of(REQUEST_MAPPING, POST_MAPPING, POST_CONSTRUCT, REPOSITORY));
    all.addAll(List.of(extra));
    return all.toArray(String[]::new);
  }

  static Behaviour behaviour(String... sources) throws IOException {
    return new Behaviour(Compiler.analyze(sources));
  }

  static Map<String, List<String>> effects(Behaviour b) {
    return b.effects.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
        e -> e.getValue().keySet().stream().map(Object::toString).toList()));
  }

  @Test
  void aRouteIsKnownByItsVerbAndPathAndCausesWhatItsCallsReach() throws IOException {
    Behaviour b = behaviour(program(SERVICE, CONTROLLER));
    // The count route causes nothing, so it is not a way in worth showing.
    assertEquals(Map.of("POST /api/orders", List.of("writes Order")), effects(b));
    assertEquals(List.of("OrderController.place(long)", "OrderService.place(long)", "OrderRepository.save(long)"),
        b.effects.get("POST /api/orders").values().iterator().next().stream().map(MethodKey::pretty).toList());
  }

  @Test
  void aThreadStartedAtStartupIsAWayInOfItsOwn() throws IOException {
    Behaviour b = behaviour(program(SERVICE, CONTROLLER, CLEANUP));
    assertEquals(List.of("deletes Order"), effects(b).get("thread Cleanup.start"));
    assertFalse(b.entries.keySet().stream().anyMatch(id -> id.contains("on startup")), b.entries.keySet().toString());
  }

  static final String ROUTER = """
      public class Router {
        public interface Handler { void handle(String body) throws Exception; }
        public enum Verb { GET, POST }
        java.util.Map<String, Handler> routes = new java.util.HashMap<>();
        public void add(Verb verb, String path, Handler h) { routes.put(verb + " " + path, h); }
        public void dispatch(String key, String body) throws Exception { routes.get(key).handle(body); }
      }""";
  /** A handler for a serverless runtime: a generic library interface, routes registered in its constructor. */
  static final String API = """
      public class Api implements java.util.function.Function<String, String> {
        OrderRepository repo;
        Router router = new Router();
        public Api() {
          router.add(Router.Verb.POST, "/orders", this::create);
          router.add(Router.Verb.GET, "/orders", body -> System.out.println(body));
        }
        void create(String body) { repo.save(body.length()); }
        public String apply(String request) {
          try { router.dispatch(request, request); } catch (Exception e) { return "error"; }
          return "ok";
        }
      }""";

  @Test
  void aRouteRegisteredInCodeIsAWayInNamedByItsVerbAndPath() throws IOException {
    // The router reaches every handler it registered, but that is dispatch: the write belongs to the route.
    Behaviour b = behaviour(program(ROUTER, API));
    assertEquals(Map.of("POST /orders", List.of("writes Order")), effects(b));
  }

  @Test
  void aHandlerForAGenericLibraryInterfaceIsAWayInThoughItsBridgeCallsIt() throws IOException {
    // javac makes apply(Object) call apply(String); that bridge is the same method, not a caller.
    String api = "public class Api implements java.util.function.Function<String, String> { OrderRepository repo;"
        + " public String apply(String request) { repo.save(request.length()); return \"ok\"; } }";
    Behaviour b = behaviour(program(api));
    assertEquals(Map.of("Function callback Api.apply", List.of("writes Order")), effects(b));
  }

  @Test
  void aClientCallWithAPathAndACallbackIsNotARoute() throws IOException {
    // A path literal and a lambda look like a route registration; with a library callback type and no verb, they are not.
    String client = "public class Client { public void send(String path, java.util.function.Consumer<String> onReply) { onReply.accept(path); } }";
    String service = "public class OrderService { OrderRepository repo; Client client;"
        + " void place(long id) { client.send(\"/orders\", reply -> repo.save(id)); } }";
    Behaviour b = behaviour(program(client, service, CONTROLLER));
    assertEquals(Map.of("POST /api/orders", List.of("writes Order")), effects(b));
  }

  @Test
  void anAwsLambdaHandlerIsNamedByTheEventItTakes() throws IOException {
    List<String> aws = List.of("package com.amazonaws.services.lambda.runtime; public interface RequestHandler<I, O> { O handleRequest(I input, Object context); }");
    String handler = "public class CvHandler implements com.amazonaws.services.lambda.runtime.RequestHandler<String, String> { OrderRepository repo;"
        + " public String handleRequest(String event, Object context) { repo.save(event.length()); return \"ok\"; } }";
    Behaviour b = new Behaviour(Compiler.analyzeAgainst(aws, program(handler)));
    assertEquals(Map.of("lambda CvHandler (String)", List.of("writes Order")), effects(b));
  }

  @Test
  void aFactoryADependencyInjectorMadeIsTheConstructionOfWhatItMakes() throws IOException {
    List<String> dagger = List.of("package dagger.internal; public interface Factory<T> { T get(); }");
    String audit = "public class Audit { OrderRepository repo; Audit(OrderRepository repo) { this.repo = repo; repo.save(0); } }";
    String factory = "public final class Audit_Factory implements dagger.internal.Factory<Audit> { OrderRepository repo;"
        + " public Audit get() { return new Audit(repo); } }";
    Behaviour b = new Behaviour(Compiler.analyzeAgainst(dagger, program(audit, factory)));
    assertEquals(Map.of("constructing Audit", List.of("writes Order")), effects(b));
  }

  @Test
  void filesInS3AndEmailThroughSesAreNamedForWhatTheyAre() throws IOException {
    List<String> aws = List.of("package software.amazon.awssdk.services.s3; public interface S3Client { Object deleteObject(Object r); }",
        "package software.amazon.awssdk.services.ses; public interface SesClient { Object sendEmail(Object r); }");
    String purge = """
        import org.springframework.web.bind.annotation.*;
        public class Files {
          software.amazon.awssdk.services.s3.S3Client s3;
          software.amazon.awssdk.services.ses.SesClient ses;
          @PostMapping("/files/purge") public void purge(Object r) { s3.deleteObject(r); ses.sendEmail(r); }
        }""";
    Behaviour b = new Behaviour(Compiler.analyzeAgainst(aws, program(purge)));
    assertEquals(Map.of("POST /files/purge", List.of("deletes files (S3Client)", "sends email (SesClient)")), effects(b));
  }

  @Test
  void verbsOnOneStoreAreSaidTogether() throws IOException {
    String both = "public class OrderService { OrderRepository repo; void place(long id) { repo.save(Ids.next(id)); repo.deleteById(Ids.next(id)); } }";
    Feed f = feed(program("public class Ids { static long next(long id) { return id + 1; } }", both, CONTROLLER),
        program("public class Ids { static long next(long id) { return id + 2; } }", both, CONTROLLER));
    assertEquals(List.of("POST /api/orders: deletes and writes Order with different data (in Ids.next: 1 → 2)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aChangeOnTheWayOfSomeOfTheWaysInJoinsTheLineAboutAllOfThem() throws IOException {
    // Ids.next is on the way of both routes, Ids.salt of one; with the same words and effect, that is one line.
    String ids = "public class Ids { static long next(long id) { return id + %s; } static long salt(long id) { return id * %s; } }";
    String service = "public class OrderService { OrderRepository repo; void place(long id) { repo.save(Ids.next(id)); }"
        + " void placeFor(long id) { repo.save(Ids.next(Ids.salt(id))); } }";
    String controller = CONTROLLER.replace("@PostMapping(\"/orders/count\") public long count() { return 0; }",
        "@PostMapping(\"/orders/for\") public void placeFor(long id) { service.placeFor(id); }");
    Feed f = feed(program(String.format(ids, "1", "3"), service, controller), program(String.format(ids, "2", "4"), service, controller));
    assertEquals(List.of("2 ways in (POST /api/orders, POST /api/orders/for): writes Order with different data (in Ids.next: 1 → 2; Ids.salt: 3 → 4)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void whenManyMethodsChangedInTheirOwnWaysTheTwoMostCommonAreSaid() throws IOException {
    String ids = "public class Ids { static long a(long i) { return i + %s; } static long b(long i) { return i * %s; }"
        + " static long c(long i) { return i - %s; } static long d(long i) { return i / %s; } }";
    String service = "public class OrderService { OrderRepository repo; void place(long id) { repo.save(Ids.a(Ids.b(Ids.c(Ids.d(id))))); } }";
    Feed f = feed(program(String.format(ids, "1", "3", "5", "7"), service, CONTROLLER), program(String.format(ids, "2", "4", "6", "8"), service, CONTROLLER));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.a: 1 → 2; Ids.b: 3 → 4; and 2 more)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void argumentsOfOneTypeAreCounted() {
    assertEquals("now also takes 2 Strings and an Optional",
        PathChange.signature("(J)V", "(JLjava/lang/String;Ljava/lang/String;Ljava/util/Optional;)V"));
    assertEquals("now also takes a String", PathChange.signature("(J)V", "(JLjava/lang/String;)V"));
  }

  @Test
  void aThreadThatCausesNothingDoesNotHideAChangeInItsTwinThatWrites() throws IOException {
    // Two threads started in one method share a name; the quiet one must not take over what the other runs.
    String twins = """
        public class Scheduler {
          OrderRepository repo;
          @jakarta.annotation.PostConstruct
          public void start() {
            new Thread(new Runnable() { public void run() { repo.save(%s); } }).start();
            new Thread(new Runnable() { public void run() { System.out.println("tick"); } }).start();
          }
        }""";
    Feed f = feed(program(String.format(twins, "20")), program(String.format(twins, "10")));
    assertEquals(List.of("thread Scheduler.start: writes Order with different data: 20 → 10 (in Scheduler$1.run)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void movingCodeAroundChangesNothingAReaderIsShown() throws IOException {
    String renamed = SERVICE.replace("class OrderService", "class OrderServiceImpl");
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(renamed, CONTROLLER.replace("OrderService service", "OrderServiceImpl service")));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head), List.of());
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void aRouteThatStartsDeletingDataIsTheFirstLine() throws IOException {
    String deleting = SERVICE.replace("repo.save(id);", "repo.save(id); repo.deleteById(id - 1);");
    String reworded = CONTROLLER.replace("service.place(id);", "service.place(id + 0L);");
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(deleting, reworded));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head), List.of());
    assertEquals(Feed.Kind.DELETES, f.lines.get(0).kind());
    assertEquals("POST /api/orders now deletes Order (nothing deleted that before)", f.lines.get(0).text());
    assertEquals(1, f.what());
  }

  static Feed feed(String[] base, String[] head) throws IOException {
    Analysis a = Compiler.analyze(base);
    Analysis b = Compiler.analyze(head);
    return new Feed(new Behaviour(a), new Behaviour(b), new Differ(a, b), List.of());
  }

  @Test
  void aChangeToWhatGoesIntoAWriteSaysWhereTheDataNowComesFrom() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } }";
    Feed f = feed(program(SERVICE, CONTROLLER, ids), program(SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));"), CONTROLLER, ids));
    assertEquals(List.of("POST /api/orders: writes Order now using Ids.next (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(Feed.Kind.CHANGED_HOW, f.lines.get(0).kind());
    assertEquals(0, f.what());
  }

  @Test
  void aNewCheckBeforeAWriteNamesWhatItAsks() throws IOException {
    Feed f = feed(program(SERVICE, CONTROLLER),
        program(SERVICE.replace("repo.save(id);", "if (repo.count() > 100) return; repo.save(id);"), CONTROLLER));
    assertEquals(List.of("POST /api/orders: new check before it writes Order, on OrderRepository.count against 100 (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aCheckThatIsGoneIsNamed() throws IOException {
    String guarded = SERVICE.replace("repo.save(id);", "if (repo.count() > 100) return; repo.save(id);");
    Feed f = feed(program(guarded, CONTROLLER), program(SERVICE, CONTROLLER));
    assertEquals(List.of("POST /api/orders: no longer checks OrderRepository.count before it writes Order (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aChangedHelperWhoseResultIsWrittenIsALine() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } static String tag(long id) { return \"o\" + id; } }";
    String using = SERVICE.replace("repo.save(id);", "System.out.println(Ids.tag(id)); repo.save(Ids.next(id));");
    Feed f = feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("id + 1", "id + 2")));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next: 1 → 2)"), f.lines.stream().map(Feed.Line::text).toList());
    // A helper whose result goes elsewhere is not about the write.
    assertTrue(feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("\"o\"", "\"order-\""))).isEmpty());
  }

  @Test
  void helpersThatChangedTheSameWriteAlikeAreOneLine() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } static long salt(long id) { return id * 3; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id) + Ids.salt(id));");
    Feed f = feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("id + 1", "id + 2").replace("id * 3", "id * 4")));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next: 1 → 2; Ids.salt: 3 → 4)"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(2, f.lines.get(0).methods().size());
  }

  @Test
  void aChangedHelperThatDecidesAWriteIsALine() throws IOException {
    String limits = "public class Limits { static boolean allowed(long id) { return id < 100; } }";
    String guarded = SERVICE.replace("repo.save(id);", "if (Limits.allowed(id)) repo.save(id);");
    Feed f = feed(program(guarded, CONTROLLER, limits), program(guarded, CONTROLLER, limits.replace("100", "1000")));
    assertEquals(List.of("POST /api/orders: changed the check before it writes Order (in Limits.allowed: 100 → 1000)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void whenNothingNamedChangedTheKindOfLogicThatDidIsSaid() throws IOException {
    String limits = "public class Limits { static boolean allowed(long id) { return id < 100; } }";
    String guarded = SERVICE.replace("repo.save(id);", "if (Limits.allowed(id)) repo.save(id);");
    Feed f = feed(program(guarded, CONTROLLER, limits), program(guarded, CONTROLLER, limits.replace("id < 100", "id <= 100")));
    assertEquals(List.of("POST /api/orders: changed the check before it writes Order (in Limits.allowed: a comparison changed)"),
        f.lines.stream().map(Feed.Line::text).toList());
    String ids = "public class Ids { static long next(long id) { return id + 1; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));");
    Feed g = feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("id + 1", "id - 1")));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next: a calculation changed)"),
        g.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aHelperThatFeedsBothTheCheckAndTheDataNamesTheEffectOnce() throws IOException {
    String limits = "public class Limits { static long cap(long id) { return id < 100 ? id : 100; } }";
    String using = SERVICE.replace("repo.save(id);", "long c = Limits.cap(id); if (c > 5) repo.save(c);");
    Feed f = feed(program(using, CONTROLLER, limits), program(using, CONTROLLER, limits.replace("id < 100", "id <= 100")));
    assertEquals(List.of("POST /api/orders: writes Order with a changed check and different data (in Limits.cap: a comparison changed)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aHelperThatMovedPackageButDoesTheSameIsNotALine() throws IOException {
    String ids = "package util; public class Ids { public static long next(long id) { return id + 1; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(util.Ids.next(id));");
    Feed f = feed(program(using, CONTROLLER, ids),
        program(using.replace("util.Ids", "common.Ids"), CONTROLLER, ids.replace("package util;", "package common;")));
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  static final String TWO_ARGS = """
      import org.springframework.web.bind.annotation.*;
      public class OrderController {
        OrderService service;
        @PostMapping("/orders") public void place(long id, long parent, String by) { service.place(id, parent, by); }
      }""";

  static String twoArgs(String body) {
    return "public class OrderService { OrderRepository repo; Store store; void place(long id, long parent, String by) { " + body + " } }";
  }

  @Test
  void aCallGivenSomethingElseSaysWhat() throws IOException {
    String store = "public class Store { OrderRepository repo; void put(long id) { repo.save(id); } void put(long id, String by) { repo.save(id); } }";
    Feed more = feed(program(TWO_ARGS, twoArgs("store.put(id);"), store), program(TWO_ARGS, twoArgs("store.put(id, by);"), store));
    assertEquals(List.of("POST /orders: writes Order with different data: Store.put now also given by (in OrderService.place)"),
        more.lines.stream().map(Feed.Line::text).toList());
    Feed other = feed(program(TWO_ARGS, twoArgs("repo.save(id);"), store), program(TWO_ARGS, twoArgs("repo.save(parent);"), store));
    assertEquals(List.of("POST /orders: writes Order with different data: OrderRepository.save now given parent, not id (in OrderService.place)"),
        other.lines.stream().map(Feed.Line::text).toList());
    // An argument inserted among others of its type is the one named.
    String three = "public class Store { OrderRepository repo; void put(long id, String by) { repo.save(id); } void put(long id, long parent, String by) { repo.save(id); } }";
    Feed inserted = feed(program(TWO_ARGS, twoArgs("store.put(id, by);"), three), program(TWO_ARGS, twoArgs("store.put(id, parent, by);"), three));
    assertEquals(List.of("POST /orders: writes Order with different data: Store.put now also given parent (in OrderService.place)"),
        inserted.lines.stream().map(Feed.Line::text).toList());
    // A call made again is not a call given something else.
    Feed again = feed(program(TWO_ARGS, twoArgs("repo.save(id);"), store), program(TWO_ARGS, twoArgs("repo.save(parent); repo.save(id);"), store));
    assertEquals(List.of("POST /orders: writes Order with different data: OrderRepository.save now called twice, not once (in OrderService.place)"),
        again.lines.stream().map(Feed.Line::text).toList());
    String returning = store.replace("void put(long id) { repo.save(id); }", "boolean put(long id) { repo.save(id); return true; }");
    Feed returns = feed(program(TWO_ARGS, twoArgs("store.put(id);"), store), program(TWO_ARGS, twoArgs("store.put(id);"), returning));
    assertEquals(List.of("POST /orders: writes Order with different data: Store.put now returns boolean, not void (in OrderService.place)"),
        returns.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aCheckAndDataGivenTheSameNewArgumentSayItOnce() throws IOException {
    String gate = "public class Gate { static long cap(long id) { return id; } static long cap(long id, String by) { return id; } }";
    String store = "public class Store { }";
    Feed f = feed(program(TWO_ARGS, twoArgs("long cap = Gate.cap(id); if (cap > 0) repo.save(cap);"), gate, store),
        program(TWO_ARGS, twoArgs("long cap = Gate.cap(id, by); if (cap > 0) repo.save(cap);"), gate, store));
    assertEquals(List.of("POST /orders: writes Order with a changed check and different data: Gate.cap now also given by (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aPrivateMethodMadeVisibleIsNotALine() throws IOException {
    String hidden = "public class OrderService { OrderRepository repo; void place(long id) { repo.save(tag(id)); } private long tag(long id) { return id; } }";
    Feed f = feed(program(hidden, CONTROLLER), program(hidden.replace("private long tag", "long tag"), CONTROLLER));
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void aFlagTestedBeforeAWriteIsNamed() throws IOException {
    String flagged = "public class OrderService { OrderRepository repo; boolean stopped; void place(long id) { if (stopped) return; repo.save(id); } }";
    Feed f = feed(program(flagged, CONTROLLER), program(flagged.replace("if (stopped) return; ", ""), CONTROLLER));
    assertEquals(List.of("POST /api/orders: no longer checks stopped before it writes Order (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aLibraryCallSwappedForAnotherIsNamedWhenNothingElseChanged() throws IOException {
    String listed = "public class OrderService { OrderRepository repo; void place(long id) { java.util.List<Long> l = java.util.List.of(id); if (l.size() > 0) repo.save(id); } }";
    Feed f = feed(program(listed, CONTROLLER), program(listed.replace("l.size() > 0", "!l.isEmpty()"), CONTROLLER));
    assertEquals(List.of("POST /api/orders: changed the check before it writes Order: List.size → List.isEmpty (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void theMessageOfAnExceptionAHelperThrowsIsNotALine() throws IOException {
    String ids = "public class Ids { static long next(long id) { if (id < 0) throw new IllegalArgumentException(\"bad id \" + id); return id + 1; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));");
    Feed f = feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("bad id ", "negative id ")));
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void aHelperThatMovedPackageAndChangedSaysWhatChanged() throws IOException {
    String ids = "package util; public class Ids { public static long next(long id) { return id + 1; } public static long salt(long id) { return id * 3; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(util.Ids.next(id) + util.Ids.salt(id));");
    Feed f = feed(program(using, CONTROLLER, ids), program(using.replace("util.Ids", "common.Ids"), CONTROLLER,
        ids.replace("package util;", "package common;").replace("id + 1", "id + 2").replace("id * 3", "id * 4")));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next: 1 → 2; Ids.salt: 3 → 4)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aNewHelperItsCallerNamesHasNoLineOfItsOwn() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } }";
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));"), CONTROLLER, ids));
    assertEquals(List.of("POST /api/orders: writes Order now using Ids.next (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aWriteMadeADifferentNumberOfTimesSaysSo() throws IOException {
    String twice = SERVICE.replace("repo.save(id);", "repo.save(id); repo.save(id);");
    Feed f = feed(program(twice, CONTROLLER), program(SERVICE, CONTROLLER));
    assertEquals(List.of("POST /api/orders: writes Order with different data: OrderRepository.save now called once, not twice (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void theSameStepsInAnotherOrderAreSaidToBeThat() throws IOException {
    String item = "public class Item { void setName(String n) { } void setPrice(long p) { } }";
    String items = "public class ItemRepository { public void save(Item i) { } }";
    String service = "public class OrderService { ItemRepository items; void place(long id) { Item i = new Item(); i.setName(\"x\"); i.setPrice(id); items.save(i); } }";
    Feed f = feed(program(service, CONTROLLER, item, items),
        program(service.replace("i.setName(\"x\"); i.setPrice(id);", "i.setPrice(id); i.setName(\"x\");"), CONTROLLER, item, items));
    assertEquals(List.of("POST /api/orders: writes Item with different data: the same steps in another order (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void whatIsDoneToTheObjectAfterTheWriteIsNotItsData() throws IOException {
    String item = "public class Item { void setName(String n) { } void setPrice(long p) { } }";
    String items = "public class ItemRepository { public void save(Item i) { } }";
    String service = "public class OrderService { ItemRepository items; void place(long id) { Item i = new Item(); i.setName(\"x\"); items.save(i); i.setPrice(id); } }";
    Feed f = feed(program(service, CONTROLLER, item, items), program(service.replace("i.setPrice(id);", "i.setPrice(id + 1);"), CONTROLLER, item, items));
    assertTrue(f.isEmpty(), f.lines.toString());
    // Reading from the object, to log it, is not work done on it either.
    String logged = service.replace("items.save(i);", "System.out.println(i.toString()); items.save(i);");
    Feed g = feed(program(service, CONTROLLER, item, items), program(logged, CONTROLLER, item, items));
    assertTrue(g.isEmpty(), g.lines.toString());
  }

  @Test
  void aCheckThatFailsThroughAHelperThatThrowsIsStillACheck() throws IOException {
    String fail = "public class Fail { static void now(String why) { throw new IllegalStateException(why); } }";
    String returning = SERVICE.replace("repo.save(id);", "if (repo.count() > 100) return; repo.save(id);");
    String throwing = SERVICE.replace("repo.save(id);", "if (repo.count() > 100) Fail.now(\"too many\"); repo.save(id);");
    Feed f = feed(program(returning, CONTROLLER, fail), program(throwing, CONTROLLER, fail));
    assertTrue(f.isEmpty(), f.lines.toString());
    // And taking the check away still says so.
    Feed g = feed(program(throwing, CONTROLLER, fail), program(SERVICE, CONTROLLER, fail));
    assertEquals(List.of("POST /api/orders: no longer checks OrderRepository.count before it writes Order (in OrderService.place)"),
        g.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void stepsThatMovedUnderANewGuardAreNotSaidToBeReordered() throws IOException {
    String item = "public class Item { void setName(String n) { } void setPrice(long p) { } }";
    String items = "public class ItemRepository { public void save(Item i) { } }";
    String service = "public class OrderService { ItemRepository items; void place(long id) { Item i = new Item(); i.setName(\"x\"); i.setPrice(id); items.save(i); } }";
    Feed f = feed(program(service, CONTROLLER, item, items),
        program(service.replace("i.setName(\"x\"); i.setPrice(id);", "i.setPrice(id); if (id > 0) i.setName(\"x\");"), CONTROLLER, item, items));
    assertFalse(f.lines.stream().anyMatch(l -> l.text().contains("another order")), f.lines.toString());
  }

  @Test
  void aMethodThatTakesSomethingElseSaysSoWhenNothingMoreChanged() throws IOException {
    String boxed = SERVICE.replace("void place(long id)", "void place(Long id)");
    Feed f = feed(program(SERVICE, CONTROLLER), program(boxed, CONTROLLER));
    assertEquals(List.of(
        "POST /api/orders: writes Order with different data (in OrderService.place: now takes a Long instead of a long)",
        "POST /api/orders: writes Order with different data: OrderService.place now given a Long instead of a long (in OrderController.place)"),
        f.lines.stream().map(Feed.Line::text).sorted().toList());
  }

  @Test
  void codeCompiledForANewerJavaSaysNothingThatDidNotChange() throws IOException {
    // Java 8 builds strings with StringBuilder and reaches an outer class's private fields through
    // accessor methods; later versions do neither.
    String msgs = """
        public class Msgs {
          private long base = 7;
          static String tag(long id) { return "order " + id + " placed"; }
          class Inner { long shifted(long id) { return id + base; } }
          long shift(long id) { return new Inner().shifted(id); }
        }""";
    String using = "public class OrderService { OrderRepository repo; Msgs msgs; void place(long id) { repo.save(Msgs.tag(id).length() + msgs.shift(id)); } }";
    Analysis a = Compiler.analyzeFor("8", program(using, CONTROLLER, msgs));
    Analysis b = Compiler.analyze(program(using, CONTROLLER, msgs));
    Feed f = new Feed(new Behaviour(a), new Behaviour(b), new Differ(a, b), List.of());
    assertTrue(f.lines.stream().noneMatch(l -> l.text().contains("uses") || l.text().contains("using")), f.lines.toString());
  }

  @Test
  void aChangeInsideTheEffectItselfIsALine() throws IOException {
    // The walk stops at the write, but what the write does can change too.
    String logging = REPOSITORY.replace("public void save(long id) { }",
        "java.util.List<Long> log = new java.util.ArrayList<>(); public void save(long id) { log.add(id); }");
    Feed f = feed(program(SERVICE, CONTROLLER), new String[] {REQUEST_MAPPING, POST_MAPPING, POST_CONSTRUCT, logging, SERVICE, CONTROLLER});
    assertEquals(List.of("POST /api/orders: the code that writes Order changed (in OrderRepository.save: now uses log)"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(0, f.what());
  }

  @Test
  void aMethodRenamedAlongWithItsCallersIsNotALine() throws IOException {
    Feed f = feed(program(SERVICE, CONTROLLER),
        program(SERVICE.replace("void place(long id)", "void create(long id)"), CONTROLLER.replace("service.place(id)", "service.create(id)")));
    assertTrue(f.isEmpty(), f.lines.toString());
    // Through an interface renamed with its implementation.
    String payments = "public interface Payments { void charge(long cents); }";
    String card = "public class CardPayments implements Payments { OrderRepository repo; public void charge(long cents) { repo.save(cents); } }";
    String route = CONTROLLER.replace("OrderService service;", "Payments payments;").replace("service.place(id);", "payments.charge(id);");
    Feed g = feed(program(payments, card, route),
        program(payments.replace("charge", "pay"), card.replace("void charge", "void pay"), route.replace("payments.charge", "payments.pay")));
    assertTrue(g.isEmpty(), g.lines.toString());
  }

  @Test
  void aCheckThatIsGoneComesFirstAmongLinesAboutHow() throws IOException {
    String guarded = SERVICE.replace("repo.save(id);", "if (repo.count() > 100) return; repo.save(id);");
    Feed f = feed(program(guarded, CONTROLLER), program(SERVICE, CONTROLLER.replace("service.place(id);", "service.place(id + 1);")));
    assertEquals(List.of(Feed.Kind.CHECK_GONE, Feed.Kind.CHANGED_HOW), f.lines.stream().map(Feed.Line::kind).toList());
    assertEquals("POST /api/orders: no longer checks OrderRepository.count before it writes Order (in OrderService.place)", f.lines.get(0).text());
    assertEquals(0, f.what());
    assertEquals("No new behaviour; 2 changes to how it does what it already did.", Output.headline(f));
  }

  @Test
  void aStringHandedToAnotherCallFirstIsNotWorkDoneOnTheWrite() throws IOException {
    String route = """
        import org.springframework.web.bind.annotation.*;
        public class OrderController {
          OrderService service;
          @PostMapping("/orders") public void place(long id, String who, String by) { service.place(id, who, by); }
        }""";
    String guard = "public class Guard { static boolean ok(String by) { return !by.isEmpty(); } }";
    String store = "public class Store { OrderRepository repo; void put(String who, long id) { repo.save(id); } }";
    String service = "public class OrderService { Store store; void place(long id, String who, String by) { if (!Guard.ok(by)) return; store.put(%s, id); } }";
    Feed f = feed(program(route, guard, store, String.format(service, "who")), program(route, guard, store, String.format(service, "by")));
    assertEquals(List.of("POST /orders: writes Order with different data: Store.put now given by, not who (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void twoArgumentsSwappedIsAChangeEvenWhenBothAreReads() throws IOException {
    String route = """
        import org.springframework.web.bind.annotation.*;
        public class OrderController {
          OrderService service;
          @PostMapping("/orders") public void place(long id, String who, String by) { service.place(id, who, by); }
        }""";
    String store = "public class Store { OrderRepository repo; void put(String a, String b, long id) { repo.save(id); } }";
    String service = "public class OrderService { Store store; void place(long id, String who, String by) { store.put(%s, id); } }";
    Feed f = feed(program(route, store, String.format(service, "who, by")), program(route, store, String.format(service, "by, who")));
    assertEquals(List.of("POST /orders: writes Order with different data: Store.put now given by and who, not who and by (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
    // A constant and a call swapped, as a real fix once did.
    String constant = "public class OrderService { Store store; static final String DEFAULT = \"default\"; void place(long id, String who, String by) { store.put(%s, id); } }";
    Feed g = feed(program(route, store, String.format(constant, "DEFAULT, by.trim()")), program(route, store, String.format(constant, "by.trim(), DEFAULT")));
    assertFalse(g.isEmpty());
  }

  @Test
  void aWriteNowMadeThroughReflectionSaysTheMapCannotFollowIt() throws IOException {
    String throwing = CONTROLLER.replace("public void place(long id) {", "public void place(long id) throws Exception {");
    String byName = "public class OrderService { OrderRepository repo; void place(long id) throws Exception {"
        + " OrderRepository.class.getMethod(\"save\", long.class).invoke(repo, id); } }";
    Feed f = feed(program(SERVICE, throwing), program(byName, throwing));
    assertEquals(List.of("POST /api/orders no longer writes Order (1 → 0 ways in); OrderService.place now calls Method.invoke, which skimit does not follow"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(Feed.Kind.LOSES, f.lines.get(0).kind());
  }

  @Test
  void aValuePutInALocalFirstIsNotALine() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } }";
    String direct = SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));");
    String viaLocal = SERVICE.replace("repo.save(id);", "long next = Ids.next(id); repo.save(next);");
    Feed f = feed(program(direct, CONTROLLER, ids), program(viaLocal, CONTROLLER, ids));
    assertTrue(f.isEmpty(), f.lines.toString());
    String aliased = SERVICE.replace("repo.save(id);", "long same = id; repo.save(same);");
    Feed g = feed(program(SERVICE, CONTROLLER), program(aliased, CONTROLLER));
    assertTrue(g.isEmpty(), g.lines.toString());
  }

  @Test
  void aChangeTheWriteDoesNotSeeIsNotALine() throws IOException {
    Feed f = feed(program(SERVICE, CONTROLLER),
        program(SERVICE.replace("repo.save(id);", "repo.save(id); System.out.println(\"saved \" + id);"), CONTROLLER));
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void aRouteThatOnlyChangedItsPathIsOneLine() throws IOException {
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(SERVICE,
        CONTROLLER.replace("@PostMapping(\"/orders\") public void place(", "@PostMapping(\"/orders/new\") public void create(")));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head), List.of());
    assertEquals(List.of("POST /api/orders is now POST /api/orders/new (it writes Order)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void alikeRoutesThatMovedArePairedByTheWordTheyKept() throws IOException {
    String two = CONTROLLER.replace("@PostMapping(\"/orders/count\") public long count() { return 0; }",
        "@PostMapping(\"/orders/again\") public void again(long id) { service.place(id); }");
    String moved = two.replace("\"/orders\")", "\"/order/create\")").replace("\"/orders/again\"", "\"/order/again\"");
    Analysis base = Compiler.analyze(program(SERVICE, two));
    Analysis head = Compiler.analyze(program(SERVICE, moved));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head), List.of());
    assertEquals(List.of("POST /api/orders is now POST /api/order/create (it writes Order)",
        "POST /api/orders/again is now POST /api/order/again (it writes Order)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void codeThatNothingCallsIsNotAWayIn() throws IOException {
    String unused = "public class Archive { OrderRepository repo; public void purge(long id) { repo.deleteById(id); } }";
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(SERVICE, CONTROLLER, unused));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head), List.of());
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void codeThatNothingCallsIsNotCountedInTheMap() throws IOException {
    String unused = "public class Archive { OrderRepository repo; public void keep(long id) { repo.save(id); } }";
    String map = Output.map(behaviour(program(SERVICE, CONTROLLER, unused)));
    assertTrue(map.contains("1 way in (1 route) can cause 1 effect."), map);
    assertTrue(map.contains("**writes Order** from 1 way in: `POST /api/orders`"), map);
    // Saying what was seen tells the reader whether a way in or an effect is missing from what skimit knows.
    assertEquals("## skimit: what this program does\n\nNo way in causes an effect skimit recognises.\n\n"
        + "Code nothing calls writes Order. A way in or an effect skimit does not know would look like this; see docs/limits.md.\n",
        Output.map(behaviour(program(SERVICE, unused))));
  }

  @Test
  void aPolicyLineCanEndWithAComment() throws IOException {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("skimit", ".policy");
    java.nio.file.Files.writeString(file, String.join("\n",
        "# what we decided",
        "sink MONEY com.acme.Ledger#post*      # the ledger",
        "never \"GET *\" writes *               # a read route never writes",
        "only OrderService writes Order       # one way to write orders",
        "review deletes *", ""));
    Policy p = Policy.load(file);
    assertEquals(1, p.sinks.size());
    assertTrue(p.sinks.get(0).matches("com/acme/Ledger", "postEntry"));
    assertEquals(List.of("GET *", "OrderService", "*"), p.rules.stream().map(Rules.Rule::subject).toList());
  }

  @Test
  void anInterfaceALibraryTurnsIntoHttpCallsIsACallOut() throws IOException {
    String get = """
        package retrofit2.http;
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface GET { String value(); }""";
    String api = "public interface PaymentsApi { @retrofit2.http.GET(\"/charge\") String charge(long cents); }";
    String local = "public class FakePayments implements PaymentsApi { public String charge(long cents) { return \"ok\"; } }";
    String route = CONTROLLER.replace("service.place(id);", "service.place(id); payments.charge(id);")
        .replace("OrderService service;", "OrderService service; PaymentsApi payments;");
    Behaviour b = behaviour(program(SERVICE, route, get, api, local));
    assertEquals(List.of("writes Order", "calls PaymentsApi (remote)"), effects(b).get("POST /api/orders"));
  }

  @Test
  void callingOutThroughAClientInsteadOfRawHttpIsOneLine() throws IOException {
    String get = """
        package retrofit2.http;
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface POST { String value(); }""";
    String api = "public interface PaymentsApi { @retrofit2.http.POST(\"/charge\") String charge(long cents); }";
    String raw = CONTROLLER.replace("service.place(id);", "service.place(id); try { http.send(null, null); } catch (Exception e) { }")
        .replace("OrderService service;", "OrderService service; java.net.http.HttpClient http;");
    String client = CONTROLLER.replace("service.place(id);", "service.place(id); payments.charge(id);")
        .replace("OrderService service;", "OrderService service; PaymentsApi payments;");
    Feed f = feed(program(SERVICE, raw, get, api), program(SERVICE, client, get, api));
    assertEquals(List.of("POST /api/orders: calls out a different way, PaymentsApi (remote) instead of over HTTP"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertTrue(Output.json(f, null).contains("calls out a different way"));
  }

  static List<Rules.Rule> rules(String... lines) {
    return java.util.Arrays.stream(lines).map(l -> Rules.parse(l.split(" ")[0], l.substring(l.indexOf(' ') + 1), l)).toList();
  }

  static Feed feed(String[] base, String[] head, List<Rules.Rule> rules) throws IOException {
    Analysis a = Compiler.analyze(base);
    Analysis b = Compiler.analyze(head);
    return new Feed(new Behaviour(a), new Behaviour(b), new Differ(a, b), rules);
  }

  @Test
  void aRouteThatWasThereAndStartsWritingGainsTheWrite() throws IOException {
    String writingCount = CONTROLLER.replace("public long count() { return 0; }", "public long count() { service.place(0); return 0; }");
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, writingCount));
    assertEquals(List.of("POST /api/orders/count now writes Order (1 → 2 ways in)"), f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(Feed.Kind.GAINS, f.lines.get(0).kind());
    // And the other way: it is still there, it just no longer writes.
    Feed back = feed(program(SERVICE, writingCount), program(SERVICE, CONTROLLER));
    assertEquals(List.of("POST /api/orders/count no longer writes Order (2 → 1 ways in)"), back.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void aRouteThatStartsWritingBreaksANeverRule() throws IOException {
    String writingCount = CONTROLLER.replace("public long count() { return 0; }", "public long count() { service.place(0); return 0; }");
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, writingCount), rules("never \"POST /api/orders/count\" writes *"));
    assertEquals("Breaks a rule (never \"POST /api/orders/count\" writes *): POST /api/orders/count writes Order", f.lines.get(0).text());
    assertEquals(1, f.broken());
  }

  @Test
  void writingAroundTheServiceBreaksAnOnlyRule() throws IOException {
    String direct = CONTROLLER.replace("OrderService service;", "OrderService service; OrderRepository repo;")
        .replace("service.place(id);", "repo.save(id);");
    List<Rules.Rule> only = rules("only OrderService writes Order");
    assertEquals(0, feed(program(SERVICE, CONTROLLER), program(SERVICE, CONTROLLER), only).broken());
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, direct), only);
    assertEquals(List.of("Breaks a rule (only OrderService writes Order): POST /api/orders writes Order"),
        f.lines.stream().filter(l -> l.kind() == Feed.Kind.BROKEN).map(Feed.Line::text).toList());
  }

  @Test
  void aNewWayToDeleteDataNeedsAPersonUnderAReviewRule() throws IOException {
    String deleting = SERVICE.replace("repo.save(id);", "repo.save(id); repo.deleteById(id - 1);");
    Feed f = feed(program(SERVICE, CONTROLLER), program(deleting, CONTROLLER), rules("review deletes *"));
    assertEquals("Needs a person (review deletes *): POST /api/orders now deletes Order", f.lines.get(0).text());
  }

  @Test
  void aRuleThatWasAlreadyBrokenIsNotHeldAgainstTheChange() throws IOException {
    String changed = SERVICE.replace("repo.save(id);", "repo.save(id + 1);");
    Feed f = feed(program(SERVICE, CONTROLLER), program(changed, CONTROLLER), rules("never * writes Order"));
    assertEquals(0, f.broken(), f.lines.toString());
  }

  @Test
  void aRouteThatStopsDeletingSaysSo() throws IOException {
    String deleting = SERVICE.replace("repo.save(id);", "repo.save(id); repo.deleteById(id - 1);");
    Feed f = feed(program(deleting, CONTROLLER), program(SERVICE, CONTROLLER));
    assertEquals(List.of("POST /api/orders no longer deletes Order (1 → 0 ways in)"), f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(Feed.Kind.DELETES, f.lines.get(0).kind());  // losing a delete matters as much as gaining one
  }

  @Test
  void aNewRouteThatWritesIsANewWayIn() throws IOException {
    String more = CONTROLLER.replace("@PostMapping(\"/orders/count\")",
        "@PostMapping(\"/orders/bulk\") public void bulk(long id) { service.place(id); }\n  @PostMapping(\"/orders/count\")");
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, more));
    assertEquals(List.of(Feed.Kind.NEW_WAY_IN), f.lines.stream().map(Feed.Line::kind).toList());
    assertTrue(f.lines.get(0).text().equals("New way in: POST /api/orders/bulk → writes Order (1 → 2 ways in)"), f.lines.get(0).text());
  }

  @Test
  void whatARunDoesThroughReflectionIsSaidButIsNotAMiss() throws IOException {
    String byName = """
        public class Purge {
          OrderService service;
          void run() throws Exception { OrderService.class.getDeclaredMethod("place", long.class).invoke(service, 1L); }
        }""";
    Behaviour b = behaviour(program(SERVICE, CONTROLLER, byName));
    java.nio.file.Path trace = java.nio.file.Files.createTempFile("skimit", ".trace");
    java.nio.file.Files.writeString(trace, String.join("\n", "skimit-trace 2", "hits 1", "flows 1", "critical-flows 1", "ran 2",
        "reaches Purge#run#()V OrderRepository#save#(J)V", ""));
    Verifier v = new Verifier(b, List.of(trace));
    assertTrue(v.misses.isEmpty(), v.report());
    assertEquals(1, v.byName.size(), v.report());
    assertTrue(v.report().contains("went through reflection"), v.report());
  }

  @Test
  void aRecordedRunIsCheckedAgainstTheMap() throws IOException {
    Behaviour b = behaviour(program(SERVICE, CONTROLLER));
    java.nio.file.Path trace = java.nio.file.Files.createTempFile("skimit", ".trace");
    java.nio.file.Files.writeString(trace, String.join("\n", "skimit-trace 2", "hits 2", "flows 2", "critical-flows 2", "ran 3",
        "reaches OrderController#place#(J)V OrderRepository#save#(J)V",
        "reaches OrderService#place#(J)V OrderRepository#save#(J)V",
        "reaches OrderController#place#(J)V OrderRepository#deleteById#(J)V", ""));
    Verifier v = new Verifier(b, List.of(trace));
    assertEquals(List.of("writes Order", "deletes Order").stream().sorted().toList(), List.copyOf(v.effectsSeen));
    assertEquals(1, v.misses.size(), v.report());
    assertEquals("POST /api/orders", v.misses.get(0).who());
    assertEquals("deletes Order", v.misses.get(0).effect().toString());
  }
}
