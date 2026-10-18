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
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
    assertTrue(f.isEmpty(), f.lines.toString());
  }

  @Test
  void aRouteThatStartsDeletingDataIsTheFirstLine() throws IOException {
    String deleting = SERVICE.replace("repo.save(id);", "repo.save(id); repo.deleteById(id - 1);");
    String reworded = CONTROLLER.replace("service.place(id);", "service.place(id + 0L);");
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(deleting, reworded));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
    assertEquals(Feed.Kind.DELETES, f.lines.get(0).kind());
    assertEquals("POST /api/orders now deletes Order (nothing deleted that before)", f.lines.get(0).text());
    assertTrue(f.structural());
  }

  static Feed feed(String[] base, String[] head) throws IOException {
    Analysis a = Compiler.analyze(base);
    Analysis b = Compiler.analyze(head);
    return new Feed(new Behaviour(a), new Behaviour(b), new Differ(a, b));
  }

  @Test
  void aChangeToWhatGoesIntoAWriteSaysWhereTheDataNowComesFrom() throws IOException {
    String ids = "public class Ids { static long next(long id) { return id + 1; } }";
    Feed f = feed(program(SERVICE, CONTROLLER, ids), program(SERVICE.replace("repo.save(id);", "repo.save(Ids.next(id));"), CONTROLLER, ids));
    assertEquals(List.of("POST /api/orders: writes Order now using Ids.next (in OrderService.place)"),
        f.lines.stream().map(Feed.Line::text).toList());
    assertEquals(Feed.Kind.CHANGED_HOW, f.lines.get(0).kind());
    assertFalse(f.structural());
  }

  @Test
  void aNewCheckBeforeAWriteNamesWhatItAsks() throws IOException {
    Feed f = feed(program(SERVICE, CONTROLLER),
        program(SERVICE.replace("repo.save(id);", "if (repo.count() > 100) return; repo.save(id);"), CONTROLLER));
    assertEquals(List.of("POST /api/orders: new check before it writes Order, on OrderRepository.count (in OrderService.place)"),
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
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
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
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
    assertEquals(List.of("POST /api/orders is now POST /api/order/create (it writes Order)",
        "POST /api/orders/again is now POST /api/order/again (it writes Order)"),
        f.lines.stream().map(Feed.Line::text).toList());
  }

  @Test
  void codeThatNothingCallsIsNotAWayIn() throws IOException {
    String unused = "public class Archive { OrderRepository repo; public void purge(long id) { repo.deleteById(id); } }";
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(SERVICE, CONTROLLER, unused));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
    assertTrue(f.isEmpty(), f.lines.toString());
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

  static List<Rules.Rule> rules(String... lines) {
    return java.util.Arrays.stream(lines).map(l -> Rules.parse(l.split(" ")[0], l.substring(l.indexOf(' ') + 1), l)).toList();
  }

  static Feed feed(String[] base, String[] head, List<Rules.Rule> rules) throws IOException {
    Analysis a = Compiler.analyze(base);
    Analysis b = Compiler.analyze(head);
    return new Feed(new Behaviour(a), new Behaviour(b), new Differ(a, b), rules);
  }

  @Test
  void aRouteThatStartsWritingBreaksANeverRule() throws IOException {
    String writingCount = CONTROLLER.replace("public long count() { return 0; }", "public long count() { service.place(0); return 0; }");
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, writingCount), rules("never \"POST /api/orders/count\" writes *"));
    assertEquals("Breaks \"never \"POST /api/orders/count\" writes *\": POST /api/orders/count writes Order", f.lines.get(0).text());
    assertEquals(1, f.broken());
  }

  @Test
  void writingAroundTheServiceBreaksAnOnlyRule() throws IOException {
    String direct = CONTROLLER.replace("OrderService service;", "OrderService service; OrderRepository repo;")
        .replace("service.place(id);", "repo.save(id);");
    List<Rules.Rule> only = rules("only OrderService writes Order");
    assertEquals(0, feed(program(SERVICE, CONTROLLER), program(SERVICE, CONTROLLER), only).broken());
    Feed f = feed(program(SERVICE, CONTROLLER), program(SERVICE, direct), only);
    assertEquals(List.of("Breaks \"only OrderService writes Order\": POST /api/orders writes Order"),
        f.lines.stream().filter(l -> l.kind() == Feed.Kind.BROKEN).map(Feed.Line::text).toList());
  }

  @Test
  void aNewWayToDeleteDataNeedsAPersonUnderAReviewRule() throws IOException {
    String deleting = SERVICE.replace("repo.save(id);", "repo.save(id); repo.deleteById(id - 1);");
    Feed f = feed(program(SERVICE, CONTROLLER), program(deleting, CONTROLLER), rules("review deletes *"));
    assertEquals("Needs a person (\"review deletes *\"): POST /api/orders now deletes Order", f.lines.get(0).text());
  }

  @Test
  void aRuleThatWasAlreadyBrokenIsNotHeldAgainstTheChange() throws IOException {
    String changed = SERVICE.replace("repo.save(id);", "repo.save(id + 1);");
    Feed f = feed(program(SERVICE, CONTROLLER), program(changed, CONTROLLER), rules("never * writes Order"));
    assertEquals(0, f.broken(), f.lines.toString());
  }
}
