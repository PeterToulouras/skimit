package dev.skimit.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Which calls cause an effect, and which methods can lead to one: what the map is built from. */
class AnalysisTest {
  static final String LEDGER = """
      import dev.skimit.Critical;
      public class Ledger {
        @Critical("MONEY") public void post(long cents) {}
        public int size() { return 0; }
      }""";

  private static MethodKey find(Analysis a, String owner, String name) {
    return a.graph.methods.keySet().stream()
        .filter(m -> m.owner().equals(owner) && m.name().equals(name)).findFirst().orElseThrow();
  }

  /** Whether the method can lead to an effect, so a change to it can change how that effect happens. */
  private static boolean reaches(Analysis a, String owner, String name) {
    MethodKey m = find(a, owner, name);
    return new Behaviour(a).leadsTo.values().stream().anyMatch(s -> s.contains(m));
  }

  /** For a way in, each effect it causes and the chain of calls that shows it. */
  private static Map<String, List<String>> causes(Analysis a, String owner, String name) {
    Behaviour b = new Behaviour(a);
    MethodKey m = find(a, owner, name);
    Behaviour.Entry e = b.entries.values().stream().filter(x -> x.method().equals(m)).findFirst().orElseThrow();
    Map<String, List<String>> out = new TreeMap<>();
    b.effects.get(e.id()).forEach((effect, chain) ->
        out.put(effect.toString(), chain.stream().map(k -> k.owner() + "." + k.name()).toList()));
    return out;
  }

  @Test
  void callThroughAnInterfaceReachesTheImplementation() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public interface Payments { void charge(long cents); }",
        "public class CardPayments implements Payments { Ledger l; public void charge(long c) { l.post(c); } }",
        "public class Checkout { Payments p; void submit() { p.charge(5); } }",
        "public class Banner { String text() { return \"hi\"; } }");
    assertEquals(Map.of("writes MONEY via Ledger", List.of("Checkout.submit", "CardPayments.charge", "Ledger.post")),
        causes(a, "Checkout", "submit"));
    assertFalse(reaches(a, "Banner", "text"));
  }

  @Test
  void lambdaOfAnApplicationInterfaceIsCalledWhereTheInterfaceIsCalled() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public interface Route { void handle(); }",
        "public class Router { java.util.Map<String, Route> routes = new java.util.HashMap<>(); void add(String p, Route r) { routes.put(p, r); } void dispatch(String p) { routes.get(p).handle(); } }",
        """
        public class Api {
          Ledger l; Router router = new Router();
          void wire() { router.add("/pay", this::pay); router.add("/info", this::info); }
          void pay() { l.post(1); }
          void info() { describe(); }
          void describe() { }
        }""");
    // The dispatcher reaches the sink; registering a route does not call it.
    assertEquals(List.of("Router.dispatch", "Api.pay", "Ledger.post"), causes(a, "Router", "dispatch").get("writes MONEY via Ledger"));
    assertFalse(reaches(a, "Api", "wire"));
    // The other route is an alternative at the same call, not part of the payment flow.
    assertFalse(reaches(a, "Api", "info"));
    assertFalse(reaches(a, "Api", "describe"));
  }

  @Test
  void baseClassHookBindsToTheSubclassItRunsIn() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public abstract class Handler { Handler() { configure(); } abstract void configure(); void serve() { configure(); } }",
        "public class PayHandler extends Handler { Ledger l; void configure() { l.post(1); } }",
        "public class SearchHandler extends Handler { void configure() { } }",
        "public class Boot { void pay() { new PayHandler().serve(); } void search() { new SearchHandler().serve(); } }");
    assertTrue(reaches(a, "PayHandler", "<init>"));
    assertFalse(reaches(a, "SearchHandler", "<init>"));
    assertFalse(reaches(a, "SearchHandler", "configure"));
    // The shared base method leads to the sink because one of the classes it runs on does.
    assertTrue(reaches(a, "Handler", "serve"));
    assertTrue(reaches(a, "Boot", "pay"));
    assertFalse(reaches(a, "Boot", "search"));
  }

  @Test
  void methodReferenceOnThisBindsToTheSameObject() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        public abstract class Routes {
          Runnable route() { return this::handle; }
          abstract void handle();
        }""",
        "public class PayRoutes extends Routes { Ledger l; void handle() { l.post(1); } }",
        "public class InfoRoutes extends Routes { void handle() { } Runnable mine() { return route(); } }");
    assertFalse(reaches(a, "InfoRoutes", "mine"));
    assertTrue(reaches(a, "Routes", "route"));
  }

  @Test
  void lambdaAndMethodReferenceAreChargedToTheMethodThatCreatesThem() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        import java.util.List;
        public class Batch {
          Ledger l;
          void viaLambda(List<Long> xs) { xs.forEach(x -> l.post(x)); }
          void viaReference(List<Long> xs) { xs.forEach(l::post); }
        }""",
        "public class Other { void harmless(java.util.List<Long> xs) { xs.forEach(x -> {}); } }");
    assertTrue(reaches(a, "Batch", "viaLambda"));
    assertTrue(reaches(a, "Batch", "viaReference"));
    assertFalse(reaches(a, "Other", "harmless"));
  }

  @Test
  void aWrapperThatRunsACallbackItIsHandedLeadsToWhatTheCallbackDoes() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        import java.util.concurrent.Executor;
        import java.util.function.Consumer;
        import java.util.function.Supplier;
        public class Payments {
          Ledger l; Executor executor; Supplier<String> configured;
          void pay() { withMetrics(() -> { l.post(1); return null; }); }
          <T> T withMetrics(Supplier<T> s) { long t = System.nanoTime(); T r = s.get(); return r; }
          void payLater() { outer(x -> l.post(x)); }
          void outer(Consumer<Long> fn) { inner(() -> fn.accept(1L)); }
          void inner(Runnable r) { r.run(); }
          void payAsync() { submit(() -> l.post(2)); }
          void submit(Runnable r) { executor.execute(r); }
          void tick() { audit(() -> { }); }
          void audit(Runnable r) { r.run(); }
          String fromField() { return configured.get(); }
        }""");
    assertTrue(reaches(a, "Payments", "withMetrics"));   // runs the callback
    assertTrue(reaches(a, "Payments", "outer"));         // wraps it in another lambda
    assertTrue(reaches(a, "Payments", "inner"));
    assertTrue(reaches(a, "Payments", "submit"));        // hands it on to a library
    assertFalse(reaches(a, "Payments", "audit"));        // only ever given a harmless one
    assertFalse(reaches(a, "Payments", "fromField"));    // not a parameter
  }

  @Test
  void aMethodThatHandsAnObjectItWasGivenToALibraryCallsItsCallbacks() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        import org.xml.sax.Attributes;
        import org.xml.sax.helpers.DefaultHandler;
        class RowHandler extends DefaultHandler {
          Ledger l;
          RowHandler(Ledger l) { this.l = l; }
          @Override public void startElement(String uri, String local, String q, Attributes atts) { l.post(1); }
        }""",
        """
        import org.xml.sax.helpers.DefaultHandler;
        class QuietHandler extends DefaultHandler { Ledger l; public void flush() { l.post(2); } }""",
        """
        import org.xml.sax.ContentHandler;
        import org.xml.sax.XMLReader;
        public class Importer {
          Ledger l; XMLReader reader; ContentHandler configured;
          void importRows() throws Exception { parseVia(new RowHandler(l)); }
          void parseVia(ContentHandler h) throws Exception { parse(h); }
          void parse(ContentHandler h) throws Exception { reader.setContentHandler(h); reader.parse("rows.xml"); }
          void importQuietly() throws Exception { parseQuiet(new QuietHandler()); }
          void parseQuiet(ContentHandler h) { reader.setContentHandler(h); }
          void fromField() { reader.setContentHandler(configured); }
        }""");
    assertTrue(reaches(a, "Importer", "parse"));         // hands the handler to the parser
    assertTrue(reaches(a, "Importer", "parseVia"));      // passes it on again
    // The handler's callbacks are charged to the method that creates it as well.
    assertEquals(List.of("Importer.importRows", "RowHandler.startElement", "Ledger.post"),
        causes(a, "Importer", "importRows").get("writes MONEY via Ledger"));
    // Only the methods that implement the parameter's type: flush() is no ContentHandler method.
    assertFalse(reaches(a, "Importer", "parseQuiet"));
    assertFalse(reaches(a, "Importer", "fromField"));    // a field, not a parameter
  }

  @Test
  void objectHandedToALibraryCanBeCalledBack() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Job implements Runnable { Ledger l; public void run() { l.post(1); } }",
        "public class Scheduler { void start(java.util.concurrent.Executor e) { e.execute(new Job()); } }");
    assertTrue(reaches(a, "Scheduler", "start"));
    assertTrue(reaches(a, "Job", "run"));
  }

  @Test
  void overrideOfASinkIsASink() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class AuditedLedger extends Ledger { @Override public void post(long c) {} }",
        "public class Caller { void go(AuditedLedger l) { l.post(1); } }");
    assertTrue(a.sinks.containsKey(find(a, "AuditedLedger", "post")));
    assertTrue(reaches(a, "Caller", "go"));
  }

  @Test
  void classLevelAnnotationMakesEveryMethodASinkWithTheDefaultDomain() throws IOException {
    Analysis a = Compiler.analyze(
        "import dev.skimit.Critical; @Critical public class Vault { void open() {} void close() {} }");
    assertEquals(Set.of("CRITICAL"), a.sinks.get(find(a, "Vault", "open")));
    assertEquals(Set.of("CRITICAL"), a.sinks.get(find(a, "Vault", "close")));
  }

  @Test
  void policyFileCanDeclareLibraryMethodsAsSinks() throws IOException {
    Policy policy = Policy.defaults();
    policy.sinks.add(Policy.rule("DATA", "java.sql.*Statement#execute*"));
    Analysis a = Compiler.analyze(policy,
        """
        public class Statements {
          void apply(java.sql.PreparedStatement s) throws Exception { s.executeUpdate(); }
          void close(java.sql.PreparedStatement s) throws Exception { s.close(); }
        }""",
        "public class Service { Statements d; void store() throws Exception { d.apply(null); } }");
    assertEquals(Set.of("writes DATA via PreparedStatement"), causes(a, "Service", "store").keySet());
    assertFalse(reaches(a, "Statements", "close"));
  }

  @Test
  void beanMethodNamedInAnAnnotationExpressionRunsBeforeTheAnnotatedMethod() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Guard { String value(); }",
        """
        public class AccessRules {
          boolean canPay(long id) { return limit() > id; }
          long limit() { return 9; }
          boolean canRead(long id) { return true; }
        }""",
        """
        public class PayController {
          Ledger l;
          @Guard("@accessRules.canPay(#id)") void pay(long id) { l.post(id); }
          @Guard("@accessRules.canRead(#id)") int size(long id) { return l.size(); }
        }""");
    Behaviour b = new Behaviour(a);
    Set<MethodKey> runs = b.runs.get(b.entries.values().stream()
        .filter(e -> e.method().name().equals("pay")).findFirst().orElseThrow().id());
    assertTrue(runs.contains(find(a, "AccessRules", "canPay")));
    assertTrue(runs.contains(find(a, "AccessRules", "limit")));
    assertTrue(b.runs.values().stream().noneMatch(r -> r.contains(find(a, "AccessRules", "canRead"))));
  }

  static final String ORDERS = """
      public interface OrderRepository { void save(Object o); Object findById(long id); }""";
  static final String ORDER_SERVICE = """
      public class OrderService {
        OrderRepository orders;
        void place(Object o) { orders.save(o); }
        Object show(long id) { return orders.findById(id); }
      }""";

  @Test
  void withNothingDeclaredDataWritesAreTheSinks() throws IOException {
    Analysis a = Compiler.analyze(ORDERS, ORDER_SERVICE, "public class Banner { String text() { return \"hi\"; } }");
    assertTrue(a.usingDefaultSinks);
    assertEquals(Set.of("writes Order"), causes(a, "OrderService", "place").keySet());
    assertFalse(reaches(a, "OrderService", "show"));
    assertFalse(reaches(a, "Banner", "text"));
  }

  @Test
  void aDeclaredSinkAddsToTheBuiltInRule() throws IOException {
    Analysis annotated = Compiler.analyze(LEDGER, ORDERS, ORDER_SERVICE);
    assertFalse(annotated.usingDefaultSinks);  // something was declared, so the output does not say the rule found it all
    assertEquals(Set.of("MONEY"), annotated.sinks.get(find(annotated, "Ledger", "post")));
    assertTrue(reaches(annotated, "OrderService", "place"));

    Policy policy = Policy.defaults();
    policy.sinks.add(Policy.rule("READS", "OrderRepository#findById"));
    Analysis byPolicy = Compiler.analyze(policy, ORDERS, ORDER_SERVICE);
    assertTrue(reaches(byPolicy, "OrderService", "show"));
    assertTrue(reaches(byPolicy, "OrderService", "place"));
    assertEquals(Set.of("writes READS via OrderRepository"), causes(byPolicy, "OrderService", "show").keySet());
  }

  @Test
  void theBuiltInRuleFindsAWriteWordAnywhereInTheName() throws IOException {
    for (String name : new String[] {"save", "saveAll", "deleteById", "batchDeleteByAppId", "scheduleBatchUpdate",
        "softDelete", "bulkInsert", "upsertJob", "clearLog", "markDeleted"}) {
      assertTrue(Policy.rule("D", "JobMapper#@write").matches("JobMapper", name), name);
    }
    for (String name : new String[] {"findById", "findByUpdateTimeAfter", "countByDeleted", "existsByRemovedFalse",
        "getLastUpdate", "isDeleted", "listToDelete", "pageList", "selectForUpdate", "lambda$batchDelete$0"}) {
      assertFalse(Policy.rule("D", "JobMapper#@write").matches("JobMapper", name), name);
    }
    Analysis a = Compiler.analyze(
        "public interface JobMapper { int scheduleBatchUpdate(Object jobs); Object findByUpdateTime(long t); }",
        """
        public class Scheduler {
          JobMapper jobs;
          void tick(Object j) { jobs.scheduleBatchUpdate(j); }
          Object recent(long t) { return jobs.findByUpdateTime(t); }
        }""");
    assertTrue(a.usingDefaultSinks);
    assertTrue(reaches(a, "Scheduler", "tick"));
    assertFalse(reaches(a, "Scheduler", "recent"));
  }

  @Test
  void theBuiltInRuleCanBeTurnedOff() throws IOException {
    Policy policy = Policy.defaults();
    policy.defaultSinks = false;
    Analysis a = Compiler.analyze(policy, ORDERS, ORDER_SERVICE);
    assertFalse(a.usingDefaultSinks);
    assertTrue(a.sinks.isEmpty());
    assertFalse(reaches(a, "OrderService", "place"));
  }

  @Test
  void withNothingDeclaredWritesThroughACommonDatabaseClientAreSinks() throws IOException {
    Analysis a = Compiler.analyze(
        "package software.amazon.awssdk.services.dynamodb; public interface DynamoDbClient { Object putItem(Object r); Object getItem(Object r); }",
        """
        public class Payments {
          software.amazon.awssdk.services.dynamodb.DynamoDbClient db;
          void charge(Object r) { db.putItem(r); }
          Object show(Object r) { return db.getItem(r); }
        }""");
    assertTrue(a.usingDefaultSinks);
    assertTrue(reaches(a, "Payments", "charge"));
    assertFalse(reaches(a, "Payments", "show"));
  }

  @Test
  void aWayInThatCausesSeveralEffectsHasAChainForEach() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "import dev.skimit.Critical; public class Signatures { @Critical(\"AUTH\") boolean check(String s) { return true; } }",
        """
        public class Webhook {
          Signatures sig; Ledger ledger;
          void handle(String body) { if (sig.check(body)) settle(5); }
          void settle(long c) { ledger.post(c); }
        }""");
    assertEquals(Map.of(
        "writes MONEY via Ledger", List.of("Webhook.handle", "Webhook.settle", "Ledger.post"),
        "writes AUTH via Signatures", List.of("Webhook.handle", "Signatures.check")),
        causes(a, "Webhook", "handle"));
  }

  @Test
  void aStaticInitializerIsNamedAsOne() {
    assertEquals("Prices (static initializer)", new MethodKey("shop/Prices", "<clinit>", "()V").pretty());
  }

  @Test
  void aMapStructMapperIsAConverterNotAStore() throws IOException {
    Analysis a = Compiler.analyze(ORDERS,
        "package org.mapstruct; public @interface Mapper {}",
        "@org.mapstruct.Mapper public interface OrderMapper { Object toDto(Object o); Object updateFromDto(Object d); }",
        "public class OrderMapperImpl implements OrderMapper { public Object toDto(Object o) { return o; } public Object updateFromDto(Object d) { return d; } }",
        """
        public class OrderService {
          OrderRepository orders; OrderMapper mapper;
          void place(Object o) { orders.save(o); }
          Object show(long id) { return mapper.toDto(orders.findById(id)); }
        }""");
    assertTrue(reaches(a, "OrderService", "place"));
    assertFalse(reaches(a, "OrderService", "show"));
    assertFalse(reaches(a, "OrderMapperImpl", "updateFromDto"));
    assertTrue(a.sinks.keySet().stream().noneMatch(m -> m.owner().contains("Mapper")));
  }

  @Test
  void buildingAQueryIsNotAWriteButRunningAnUpdateIs() throws IOException {
    String jpa = "package jakarta.persistence; public interface EntityManager { TypedQuery createQuery(String q); void persist(Object o); }";
    String query = "package jakarta.persistence; public interface TypedQuery { TypedQuery setMaxResults(int n); java.util.List getResultList(); int executeUpdate(); }";
    Analysis a = Compiler.analyze(jpa, query,
        """
        public class Owners {
          jakarta.persistence.EntityManager em;
          java.util.List page(int n) { return em.createQuery("from Owner").setMaxResults(n).getResultList(); }
          void add(Object o) { em.persist(o); }
          int purge() { return em.createQuery("delete from Owner").executeUpdate(); }
        }""");
    assertFalse(reaches(a, "Owners", "page"));
    assertTrue(reaches(a, "Owners", "add"));
    assertTrue(reaches(a, "Owners", "purge"));
  }

  @Test
  void toStringOnAPlainObjectDoesNotPullEveryClassIntoTheFlow() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        public class Dto {
          java.util.List<String> tags = new java.util.ArrayList<>();
          public void setTags(java.util.List<String> t) { tags = t; }
          public String toString() { return "Dto" + tags; }
        }""",
        """
        public class Payments {
          Ledger l;
          void pay(Object note) { l.post(note.toString().length()); }
        }""");
    assertTrue(reaches(a, "Payments", "pay"));
    assertFalse(reaches(a, "Dto", "toString"));
    assertFalse(reaches(a, "Dto", "setTags"));
  }
}
