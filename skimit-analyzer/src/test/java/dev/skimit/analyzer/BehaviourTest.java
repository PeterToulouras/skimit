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

  @Test
  void changedCodeOnTheWayToAnEffectIsAQuieterLine() throws IOException {
    Analysis base = Compiler.analyze(program(SERVICE, CONTROLLER));
    Analysis head = Compiler.analyze(program(SERVICE.replace("repo.save(id);", "repo.save(id * 2);"), CONTROLLER));
    Feed f = new Feed(new Behaviour(base), new Behaviour(head), new Differ(base, head));
    assertEquals(1, f.lines.size(), f.lines.toString());
    assertEquals(Feed.Kind.CHANGED_PATH, f.lines.get(0).kind());
    assertEquals("Writes Order: code on the way changed in OrderService.place (from POST /api/orders)", f.lines.get(0).text());
    assertFalse(f.structural());
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
}
