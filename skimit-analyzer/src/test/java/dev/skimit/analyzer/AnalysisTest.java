package dev.skimit.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skimit.analyzer.Analysis.Position;
import dev.skimit.analyzer.Analysis.Verdict;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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

  private static Verdict verdict(Analysis a, String owner, String name) {
    return a.verdict(find(a, owner, name));
  }

  private static List<String> witness(Analysis a, String owner, String name) {
    return a.witness(find(a, owner, name)).stream().map(m -> m.owner() + "." + m.name()).toList();
  }

  @Test
  void callThroughAnInterfaceReachesTheImplementation() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public interface Payments { void charge(long cents); }",
        "public class CardPayments implements Payments { Ledger l; public void charge(long c) { l.post(c); } }",
        "public class Checkout { Payments p; void submit() { p.charge(5); } }",
        "public class Banner { String text() { return \"hi\"; } }");
    assertEquals(Verdict.ON_PATH, verdict(a, "Checkout", "submit"));
    assertEquals(List.of("Checkout.submit", "CardPayments.charge", "Ledger.post"), witness(a, "Checkout", "submit"));
    assertEquals(Set.of("MONEY"), a.domains(find(a, "Checkout", "submit")));
    assertEquals(Verdict.ISOLATED, verdict(a, "Banner", "text"));
  }

  @Test
  void calleeOfAnOnPathMethodInfluencesAndUnrelatedCodeIsIsolated() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Prices { long total() { return Rounding.round(3); } }",
        "public class Rounding { static long round(long x) { return x; } }",
        "public class Checkout { Ledger l; Prices p; void submit() { l.post(p.total()); } }",
        "public class Report { String render() { return pad(\"x\"); } String pad(String s) { return s; } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Prices", "total"));
    assertEquals(Verdict.INFLUENCES, verdict(a, "Rounding", "round"));
    assertEquals(List.of("Checkout.submit", "Prices.total", "Rounding.round"), witness(a, "Rounding", "round"));
    assertEquals("`Checkout.submit()` calls `Prices.total()` calls `Rounding.round(long)`", a.explain(find(a, "Rounding", "round")));
    assertEquals(Set.of("MONEY"), a.domains(find(a, "Rounding", "round")));
    assertEquals(Verdict.ISOLATED, verdict(a, "Report", "render"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Report", "pad"));
  }

  @Test
  void whateverWritesAFieldTheFlowReadsInfluencesIt() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        public class Checkout {
          Ledger l; long fee; String note;
          Checkout(Ledger l) { this.l = l; }
          void setFee(long f) { fee = f; }
          void setNote(String n) { note = n; }
          String label() { return "x"; }
          void submit() { l.post(fee); }
        }""",
        "public class Report { Report() {} }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Checkout", "<init>"));
    assertEquals(Verdict.INFLUENCES, verdict(a, "Checkout", "setFee"));
    assertTrue(a.reason(find(a, "Checkout", "setFee")).startsWith("writes a field read by Checkout.submit()"));
    // Same class, but they touch nothing the flow reads.
    assertEquals(Verdict.ISOLATED, verdict(a, "Checkout", "setNote"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Checkout", "label"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Report", "<init>"));
  }

  @Test
  void readingACollectionFieldCountsAsWritingIt() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        import java.util.*;
        public class Cart {
          Ledger l; final List<Long> items = new ArrayList<>();
          void add(long x) { items.add(x); }
          void pay() { for (long x : items) l.post(x); }
        }""");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Cart", "add"));
  }

  @Test
  void typeHandledOnACriticalPathIsFlaggedEvenIfOnlyAFrameworkFillsIt() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Person { String name; public void setName(String n) { name = n; } }",
        "public class Owner extends Person { long balance; public void setBalance(long b) { balance = b; } }",
        "public class Save { Ledger l; void save(Owner o) { l.post(o.balance); } }",
        "public class Unrelated { int x; public void setX(int v) { x = v; } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Owner", "setBalance"));
    assertEquals(Verdict.INFLUENCES, verdict(a, "Person", "setName"));
    assertTrue(a.reason(find(a, "Person", "setName")).startsWith("belongs to a type that Save.save(Owner) handles"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Unrelated", "setX"));
  }

  @Test
  void aCollaboratorIsNotDataSoItsOtherMethodsStayClear() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Mailer { void receipt() { } void newsletter() { } }",
        "public class Checkout { Ledger l; Mailer m; void submit() { l.post(1); m.receipt(); } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Mailer", "receipt"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Mailer", "newsletter"));
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
    assertEquals(Verdict.ON_PATH, verdict(a, "Router", "dispatch"));
    assertEquals(List.of("Router.dispatch", "Api.pay", "Ledger.post"), witness(a, "Router", "dispatch"));
    assertEquals(Verdict.INFLUENCES, verdict(a, "Api", "wire"));
    assertTrue(a.reason(find(a, "Api", "wire")).startsWith("creates the reference through which Api.pay()"));
    // The other route is an alternative at the same call, not part of the payment flow.
    assertEquals(Verdict.ISOLATED, verdict(a, "Api", "info"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Api", "describe"));
  }

  @Test
  void classOnlyAFrameworkConstructsIsNeverDeclaredSafe() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        """
        public class TrimFilter implements java.util.function.UnaryOperator<String> {
          public String apply(String s) { return clean(s); }
          static String clean(String s) { return s.trim(); }
        }""",
        "public class Plain { String id(String s) { return s; } }");
    assertEquals(Verdict.UNPROVEN, verdict(a, "TrimFilter", "clean"));
    assertTrue(a.reason(find(a, "TrimFilter", "clean")).contains("through UnaryOperator"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Plain", "id"));
  }

  @Test
  void baseClassHookBindsToTheSubclassItRunsIn() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public abstract class Handler { Handler() { configure(); } abstract void configure(); void serve() { configure(); } }",
        "public class PayHandler extends Handler { Ledger l; void configure() { l.post(1); } }",
        "public class SearchHandler extends Handler { void configure() { } }",
        "public class Boot { void pay() { new PayHandler().serve(); } void search() { new SearchHandler().serve(); } }");
    assertEquals(Verdict.ON_PATH, verdict(a, "PayHandler", "<init>"));
    assertEquals(Verdict.ISOLATED, verdict(a, "SearchHandler", "<init>"));
    assertEquals(Verdict.ISOLATED, verdict(a, "SearchHandler", "configure"));
    // The shared base method is on a path because one of the classes it runs on is.
    assertEquals(Verdict.ON_PATH, verdict(a, "Handler", "serve"));
    assertEquals("`Handler.serve() on PayHandler` calls `PayHandler.configure()` calls `Ledger.post(long)`",
        a.explain(find(a, "Handler", "serve")));
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
    assertEquals(Verdict.ISOLATED, verdict(a, "InfoRoutes", "mine"));
    assertEquals(Verdict.ON_PATH, verdict(a, "Routes", "route"));
  }

  @Test
  void writerToAStoreInfluencesACriticalReaderOfIt() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public interface SettingsRepository { long findFee(); void saveFee(long f); }",
        "public interface AuditRepository { void saveEntry(String s); }",
        "public class Checkout { Ledger l; SettingsRepository settings; void submit() { l.post(settings.findFee()); } }",
        "public class Admin { SettingsRepository settings; AuditRepository audit; void setFee(long f) { settings.saveFee(f); } void note() { audit.saveEntry(\"x\"); } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Admin", "setFee"));
    assertTrue(a.reason(find(a, "Admin", "setFee")).startsWith("writes to a store that Checkout.submit() reads"));
    // A different store, which no critical flow reads.
    assertEquals(Verdict.ISOLATED, verdict(a, "Admin", "note"));
  }

  @Test
  void storeCanBeDeclaredInThePolicy() throws IOException {
    Policy policy = Policy.defaults();
    policy.storeWrites.add(Policy.rule("flags", "Flags#enable"));
    policy.storeReads.add(Policy.rule("flags", "Flags#isOn"));
    Analysis a = Compiler.analyze(policy, LEDGER,
        "public class Flags { static void enable(String k) { } static boolean isOn(String k) { return false; } }",
        "public class Billing { Ledger l; void run() { if (Flags.isOn(\"auto\")) l.post(1); } }",
        "public class Portal { void optIn() { Flags.enable(\"auto\"); } void other() { } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Portal", "optIn"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Portal", "other"));
  }

  @Test
  void frameworkParticipantsOfTheSameObjectJoinTheCriticalFlow() throws IOException {
    Policy policy = Policy.defaults();
    policy.participants.add(new Policy.Participant(java.util.regex.Pattern.compile("Before"), false, "Before"));
    Analysis a = Compiler.analyze(policy, LEDGER,
        "import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Before {}",
        "public class PayController { Ledger l; @Before void load() { } void pay() { l.post(1); } void help() { } }",
        "public class InfoController { @Before void load() { } void show() { } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "PayController", "load"));
    assertTrue(a.reason(find(a, "PayController", "load")).contains("@Before"));
    assertEquals(Verdict.ISOLATED, verdict(a, "PayController", "help"));
    assertEquals(Verdict.ISOLATED, verdict(a, "InfoController", "load"));
  }

  @Test
  void whatAHandledObjectHoldsIsHandledToo() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Address { String city; public void setCity(String c) { city = c; } }",
        "public class Owner { java.util.List<Address> addresses; long balance; }",
        "public class Save { Ledger l; void save(Owner o) { l.post(o.balance); } }",
        "public class Unrelated { public void set() { } }");
    assertEquals(Verdict.INFLUENCES, verdict(a, "Address", "setCity"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Unrelated", "set"));
  }

  @Test
  void reflectionIsNeverDeclaredSafe() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Plugins { Object load(String n) throws Exception { return Class.forName(n); } Object run() throws Exception { return load(\"x\"); } }");
    assertEquals(Verdict.UNPROVEN, verdict(a, "Plugins", "load"));
    assertEquals(Verdict.UNPROVEN, verdict(a, "Plugins", "run"));
    assertTrue(a.reason(find(a, "Plugins", "run")).endsWith("Class.forName"));
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
    assertEquals(Verdict.ON_PATH, verdict(a, "Batch", "viaLambda"));
    assertEquals(Verdict.ON_PATH, verdict(a, "Batch", "viaReference"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Other", "harmless"));
  }

  @Test
  void objectHandedToALibraryCanBeCalledBack() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class Job implements Runnable { Ledger l; public void run() { l.post(1); } }",
        "public class Scheduler { void start(java.util.concurrent.Executor e) { e.execute(new Job()); } }");
    assertEquals(Verdict.ON_PATH, verdict(a, "Scheduler", "start"));
    assertEquals(List.of("Scheduler.start", "Job.run", "Ledger.post"), witness(a, "Scheduler", "start"));
  }

  @Test
  void overrideOfASinkIsASink() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "public class AuditedLedger extends Ledger { @Override public void post(long c) {} }",
        "public class Caller { void go(AuditedLedger l) { l.post(1); } }");
    assertTrue(a.sinks.containsKey(find(a, "AuditedLedger", "post")));
    assertEquals(Verdict.ON_PATH, verdict(a, "Caller", "go"));
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
        public class Dao {
          void save(java.sql.PreparedStatement s) throws Exception { s.executeUpdate(); }
          void close(java.sql.PreparedStatement s) throws Exception { s.close(); }
        }""",
        "public class Service { Dao d; void store() throws Exception { d.save(null); } }");
    assertEquals(Verdict.ON_PATH, verdict(a, "Service", "store"));
    assertEquals(Set.of("DATA"), a.domains(find(a, "Service", "store")));
    assertEquals(Verdict.ISOLATED, verdict(a, "Dao", "close"));
  }

  @Test
  void positionComesFromTheDependencyGraph() throws IOException {
    Analysis a = Compiler.analyze(
        "public class Money { long cents; }",
        "public class A { Money m; }", "public class B { Money m; A a; }",
        "public class C { Money m; }", "public class D { B b; }");
    assertEquals(Position.ROOT, a.positions.get("Money"));
    assertEquals(4, a.dependents.get("Money"));
    assertEquals(Position.TRUNK, a.positions.get("A"));
    assertEquals(Position.LEAF, a.positions.get("D"));
    assertEquals(Position.LEAF, a.positions.get("C"));
  }

  @Test
  void beanMethodNamedInAnAnnotationExpressionIsCalledByTheAnnotatedMethod() throws IOException {
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
    assertEquals(Verdict.INFLUENCES, verdict(a, "AccessRules", "canPay"));
    assertEquals(Verdict.INFLUENCES, verdict(a, "AccessRules", "limit"));
    assertEquals(List.of("PayController.pay", "AccessRules.canPay"), witness(a, "AccessRules", "canPay"));
    assertEquals(Verdict.ISOLATED, verdict(a, "AccessRules", "canRead"));
  }

  @Test
  void filterThatHandsOnToTheChainCannotBeProvenClear() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "package jakarta.servlet; public interface FilterChain { void doFilter(Object request, Object response); }",
        """
        public class AuditFilter {
          void doFilter(Object req, Object res, jakarta.servlet.FilterChain chain) { note(); chain.doFilter(req, res); }
          void note() {}
          void describe() {}
        }""",
        "public class Checkout { Ledger l; void submit() { l.post(5); } }");
    assertEquals(Verdict.UNPROVEN, verdict(a, "AuditFilter", "doFilter"));
    assertEquals(Verdict.ISOLATED, verdict(a, "AuditFilter", "describe"));
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
    assertEquals(Verdict.ON_PATH, verdict(a, "OrderService", "place"));
    assertEquals(Set.of("DATA_WRITE"), a.domains(find(a, "OrderService", "place")));
    assertEquals(Verdict.ISOLATED, verdict(a, "Banner", "text"));
  }

  @Test
  void aDeclaredSinkReplacesTheBuiltInRule() throws IOException {
    Analysis annotated = Compiler.analyze(LEDGER, ORDERS, ORDER_SERVICE);
    assertTrue(!annotated.usingDefaultSinks);
    assertTrue(annotated.sinks.keySet().stream().noneMatch(m -> m.owner().equals("OrderRepository")));

    Policy policy = Policy.defaults();
    policy.sinks.add(Policy.rule("READS", "OrderRepository#findById"));
    Analysis byPolicy = Compiler.analyze(policy, ORDERS, ORDER_SERVICE);
    assertTrue(!byPolicy.usingDefaultSinks);
    assertEquals(Verdict.ON_PATH, verdict(byPolicy, "OrderService", "show"));
    assertEquals(Set.of("READS"), byPolicy.domains(find(byPolicy, "OrderService", "show")));
  }

  @Test
  void theBuiltInRuleCanBeTurnedOff() throws IOException {
    Policy policy = Policy.defaults();
    policy.defaultSinks = false;
    Analysis a = Compiler.analyze(policy, ORDERS, ORDER_SERVICE);
    assertTrue(!a.usingDefaultSinks);
    assertTrue(a.sinks.isEmpty());
    assertEquals(Verdict.ISOLATED, verdict(a, "OrderService", "place"));
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
    assertEquals(Verdict.ON_PATH, verdict(a, "Payments", "charge"));
    assertTrue(verdict(a, "Payments", "show") != Verdict.ON_PATH);
  }

  @Test
  void withNoSinkAtAllEveryMethodIsIsolated() throws IOException {
    Policy policy = Policy.defaults();
    policy.defaultSinks = false;
    Analysis a = Compiler.analyze(policy,
        "public class Loader { Object load(String n) throws Exception { return Class.forName(n); } }");
    assertTrue(a.sinks.isEmpty());
    assertEquals(Verdict.ISOLATED, verdict(a, "Loader", "load"));
  }

  @Test
  void aMethodThatReachesSeveralKindsOfSinkHasAProofForEach() throws IOException {
    Analysis a = Compiler.analyze(LEDGER,
        "import dev.skimit.Critical; public class Signatures { @Critical(\"AUTH\") boolean check(String s) { return true; } }",
        """
        public class Webhook {
          Signatures sig; Ledger ledger;
          void handle(String body) { if (sig.check(body)) settle(5); }
          void settle(long c) { ledger.post(c); }
        }""");
    java.util.Map<String, List<MethodKey>> proofs = a.proofs(find(a, "Webhook", "handle"));
    assertEquals(Set.of("AUTH", "MONEY"), proofs.keySet());
    assertEquals(List.of("Webhook.handle", "Webhook.settle", "Ledger.post"),
        proofs.get("MONEY").stream().map(m -> m.owner() + "." + m.name()).toList());
    assertEquals(List.of("Webhook.handle", "Signatures.check"),
        proofs.get("AUTH").stream().map(m -> m.owner() + "." + m.name()).toList());
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
    assertEquals(Verdict.ON_PATH, verdict(a, "OrderService", "place"));
    assertEquals(Verdict.ISOLATED, verdict(a, "OrderService", "show"));
    assertEquals(Verdict.ISOLATED, verdict(a, "OrderMapperImpl", "updateFromDto"));
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
    assertEquals(Verdict.ISOLATED, verdict(a, "Owners", "page"));
    assertEquals(Verdict.ON_PATH, verdict(a, "Owners", "add"));
    assertEquals(Verdict.ON_PATH, verdict(a, "Owners", "purge"));
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
    assertEquals(Verdict.ON_PATH, verdict(a, "Payments", "pay"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Dto", "toString"));
    assertEquals(Verdict.ISOLATED, verdict(a, "Dto", "setTags"));
  }
}
