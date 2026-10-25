package dev.skimit.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skimit.analyzer.Differ.Change;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which methods a change touched, read through moved classes and renumbered lambdas. */
class DifferTest {
  static final String LEDGER = AnalysisTest.LEDGER;
  static final String CHECKOUT = "public class Checkout { Ledger l; void submit() { l.post(5); } }";
  static final String REPORT = "public class Report { public String render() { return \"a\"; } }";

  private static Differ diff(String[] base, String[] head) throws IOException {
    return new Differ(Compiler.analyze(base), Compiler.analyze(head));
  }

  private static Change only(Differ d) {
    assertEquals(1, d.changes.size(), d.changes.toString());
    return d.changes.get(0);
  }

  @Test
  void identicalProgramsHaveNoChanges() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT}, new String[] {LEDGER, CHECKOUT, REPORT});
    assertTrue(d.changes.isEmpty() && d.renames.map.isEmpty());
  }

  @Test
  void commentsFormattingAndLocalNamesAreNotChanges() throws IOException {
    Differ d = diff(
        new String[] {LEDGER, "public class Report { String render() { int n = 1; return \"a\" + n; } }"},
        new String[] {LEDGER, "public class Report {\n\n  // reformatted\n  String render() {\n    int count = 1;\n    return \"a\" + count;\n  }\n}"});
    assertTrue(d.changes.isEmpty(), d.changes.toString());
  }

  @Test
  void aBodyChangeIsAChangeToThatMethodAlone() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT},
        new String[] {LEDGER, CHECKOUT, "public class Report { public String render() { return \"b\"; } }"});
    Change c = only(d);
    assertEquals("render", c.method().name());
    assertEquals("changed", c.kind());
    assertEquals(null, c.was());
  }

  @Test
  void addingALambdaDoesNotChangeTheMethodsWhoseLambdasGetRenumbered() throws IOException {
    String before = """
        import java.util.List;
        public class Orders {
          Ledger l;
          void post(List<Long> xs) { xs.forEach(x -> l.post(x)); }
          long countLong(List<String> xs) { return xs.stream().filter(x -> x.length() > 3).count(); }
          long countShort(List<String> xs) { return xs.stream().filter(x -> x.length() < 3).filter(x -> x.length() < 3).count(); }
        }""";
    String after = """
        import java.util.List;
        public class Orders {
          Ledger l;
          void post(List<Long> xs) { xs.forEach(x -> l.post(x)); Runnable done = () -> System.out.println("done"); done.run(); }
          long countLong(List<String> xs) { return xs.stream().filter(x -> x.length() > 3).count(); }
          long countShort(List<String> xs) { return xs.stream().filter(x -> x.length() < 3).filter(x -> x.length() < 4).count(); }
        }""";
    Differ d = diff(new String[] {LEDGER, before}, new String[] {LEDGER, after});
    // post() and its new lambda changed; countLong and its lambda, now numbered one higher, did not.
    // Of countShort's two identical lambdas, only the second changed: it is the change, and
    // countShort itself, whose own bytecode is the same, is not.
    assertEquals(List.of("lambda$countShort$4:changed", "lambda$post$1:added", "post:changed"),
        d.changes.stream().map(c -> c.method().name() + ":" + c.kind()).sorted().toList());
  }

  static final String ORDERS = """
      public class Orders {
        Ledger l;
        public void place(long cents) { l.post(cents); }
        public long total(long a, long b) { return a + b; }
        public String label() { return "orders"; }
      }""";
  static final String SHOP = "public class Shop { Orders o; void buy() { o.place(5); } }";

  @Test
  void aRenamedClassWhoseCodeDidNotChangeIsAMoveNotAChange() throws IOException {
    Differ d = diff(new String[] {LEDGER, ORDERS, SHOP},
        new String[] {LEDGER, ORDERS.replace("class Orders", "class OrderBook"), SHOP.replace("Orders o", "OrderBook o")});
    // Neither the renamed class nor Shop, whose only edit is the new name, changed.
    assertTrue(d.changes.isEmpty(), d.changes.toString());
    assertEquals(Map.of("OrderBook", "Orders"), d.renames.map);
  }

  @Test
  void aSinkWhoseClassWasRenamedIsStillTheSameSink() throws IOException {
    String dao = "public class OrderDao { public void save(long o) {} public long find(int id) { return id; } public long count() { return 0; } }";
    String svc = "public class Orders { OrderDao d; void place() { d.save(5); } }";
    Differ d = diff(new String[] {dao, svc},
        new String[] {dao.replace("OrderDao", "OrderMapper"), svc.replace("OrderDao", "OrderMapper")});
    assertTrue(d.changes.isEmpty(), d.changes.toString());
    assertEquals(Map.of("OrderMapper", "OrderDao"), d.renames.map);
  }

  @Test
  void anEditInsideAMovedClassIsReportedAgainstWhatTheMethodWas() throws IOException {
    Differ d = diff(new String[] {LEDGER, ORDERS, SHOP},
        new String[] {LEDGER, ORDERS.replace("class Orders", "class OrderBook").replace("l.post(cents)", "l.post(cents + 1)"),
            SHOP.replace("Orders o", "OrderBook o")});
    Change c = only(d);
    assertEquals("OrderBook", c.method().owner());
    assertEquals("changed", c.kind());
    assertEquals("Orders", c.was().owner());
  }

  @Test
  void aMethodRenamedWithTheSameBodyIsNotAChangeNorAreItsCallers() throws IOException {
    Differ d = diff(new String[] {LEDGER, ORDERS, SHOP},
        new String[] {LEDGER, ORDERS.replace("void place(long cents)", "void submit(long cents)"), SHOP.replace("o.place(5)", "o.submit(5)")});
    assertTrue(d.changes.isEmpty(), d.changes.toString());
    assertEquals("Orders#place(J)V", d.methodRenames.get(new MethodKey("Orders", "submit", "(J)V")).id());
    // Two methods that happen to have the same body are not a rename of each other.
    String twins = ORDERS.replace("public String label() { return \"orders\"; }", "public String label() { return \"orders\"; } public String name() { return \"orders\"; }");
    Differ e = diff(new String[] {LEDGER, twins, SHOP},
        new String[] {LEDGER, twins.replace("String label()", "String title()").replace("String name()", "String id()"), SHOP});
    assertTrue(e.methodRenames.isEmpty(), e.methodRenames.toString());
    assertEquals(4, e.changes.size(), e.changes.toString());
  }

  @Test
  void aFieldRenamedWithItsUsesIsNotAChange() throws IOException {
    Differ d = diff(new String[] {LEDGER, ORDERS, SHOP},
        new String[] {LEDGER, ORDERS.replace("Ledger l;", "Ledger ledger;").replace("l.post(cents)", "ledger.post(cents)"), SHOP});
    assertTrue(d.changes.isEmpty(), d.changes.toString());
    assertEquals(Map.of("Orders.ledger : LLedger;", "Orders.l : LLedger;"), d.fieldRenames);
    // Several fields of one type renamed together pair by the names they are closest to.
    String books = "public class Books { Ledger lgr; Ledger adt; void a(long c) { lgr.post(c); } void b(long c) { adt.post(c); } }";
    Differ e = diff(new String[] {LEDGER, books},
        new String[] {LEDGER, books.replace("Ledger lgr;", "Ledger ledger;").replace("lgr.post", "ledger.post").replace("Ledger adt;", "Ledger audit;").replace("adt.post", "audit.post")});
    assertTrue(e.changes.isEmpty(), e.changes.toString());
  }

  @Test
  void anAnonymousClassRenumberedByAnInsertionIsTheSameClass() throws IOException {
    String before = """
        public class Pool {
          Ledger l;
          void start() { Runnable a = new Runnable() { public void run() { l.post(1); } }; a.run(); }
          void stop() { Runnable b = new Runnable() { public void run() { l.post(2); } }; b.run(); }
        }""";
    // A new anonymous class in start() renumbers the one in stop(), which did not change.
    String after = before.replace("a.run(); }", "a.run(); Runnable x = new Runnable() { public void run() { } }; x.run(); }");
    Differ d = diff(new String[] {LEDGER, before}, new String[] {LEDGER, after});
    assertEquals("Pool$2", d.renames.map.get("Pool$3"));
    assertEquals(List.of("Pool$2.<init>:added", "Pool$2.run:added", "Pool.start:changed"),
        d.changes.stream().map(c -> c.method().owner() + "." + c.method().name() + ":" + c.kind()).sorted().toList());
  }

  @Test
  void twoSmallClassesThatOnlyLookAlikeAreNotAMove() throws IOException {
    Differ d = diff(new String[] {LEDGER, "public class Alpha { }"}, new String[] {LEDGER, "public class Beta { }"});
    assertTrue(d.renames.map.isEmpty());
    assertEquals(List.of("Alpha:removed", "Beta:added"),
        d.changes.stream().map(c -> c.method().owner() + ":" + c.kind()).sorted().toList());
  }

  @Test
  void eachChangeIsShownWithItsOwnLinesOfTheSourceDiff() throws IOException {
    String before = "public class Report {\n  public String render() {\n    return \"a\";\n  }\n"
        + "  public String title() {\n    return \"t\";\n  }\n}\n";
    String after = before.replace("return \"a\"", "return \"b\"").replace("return \"t\"", "return \"u\"");
    String patch = """
        diff --git a/src/main/java/Report.java b/src/main/java/Report.java
        index 1111111..2222222 100644
        --- a/src/main/java/Report.java
        +++ b/src/main/java/Report.java
        @@ -1,8 +1,8 @@
         public class Report {
           public String render() {
        -    return "a";
        +    return "b";
           }
           public String title() {
        -    return "t";
        +    return "u";
           }
        """;
    Analysis base = Compiler.analyze(LEDGER, before);
    Analysis head = Compiler.analyze(LEDGER, after);
    Differ d = new Differ(base, head);
    d.attach(SourceDiff.parse(patch), base, head);
    assertEquals(2, d.changes.size());
    for (Change c : d.changes) {
      List<String> edits = d.lines.get(c.method()).stream().filter(l -> l.kind() != ' ')
          .map(l -> l.kind() + l.text().strip()).toList();
      // Each method gets its own edit and not its neighbour's.
      assertEquals(c.method().name().equals("render") ? List.of("-return \"a\";", "+return \"b\";")
          : List.of("-return \"t\";", "+return \"u\";"), edits);
    }
  }
}
