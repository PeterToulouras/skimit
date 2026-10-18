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
    assertEquals(List.of("POST /api/orders: new check before it writes Order, on OrderRepository.count (in OrderService.place)"),
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
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next)"), f.lines.stream().map(Feed.Line::text).toList());
    // A helper whose result goes elsewhere is not about the write.
    assertTrue(feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("\"o\"", "\"order-\""))).isEmpty());
  }

  @Test
  void helpersThatChangedTheSameWriteAlikeAreOneLine() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } static long salt(long id) { return id * 3; } }";
    String using = SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id) + Ids.salt(id));");
    Feed f = feed(program(using, CONTROLLER, ids), program(using, CONTROLLER, ids.replace("id + 1", "id + 2").replace("id * 3", "id * 4")));
    assertEquals(List.of("POST /api/orders: writes Order with different data (in Ids.next and Ids.salt)"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(2, f.lines.get(0).methods().size());
  }

  @Test
  void aChangedHelperThatDecidesAWriteIsALine() throws IOException {
    String limits = "public class Limits { static boolean allowed(long id) { return id < 100; } }";
    String guarded = SERVICE.replace("repo.save(id);", "if (Limits.allowed(id)) repo.save(id);");
    Feed f = feed(program(guarded, CONTROLLER, limits), program(guarded, CONTROLLER, limits.replace("100", "1000")));
    assertEquals(List.of("POST /api/orders: changed the check before it writes Order (in Limits.allowed)"),
        f.lines.stream().map(Feed.Line::text).toList());
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
    assertEquals("## skimit: what this program does\n\nNo way in causes an effect skimit recognises.\n",
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
