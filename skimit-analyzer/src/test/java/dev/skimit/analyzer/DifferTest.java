package dev.skimit.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skimit.analyzer.Differ.Bucket;
import dev.skimit.analyzer.Differ.Change;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
  void identicalProgramsProduceAnEmptyManifest() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT}, new String[] {LEDGER, CHECKOUT, REPORT});
    assertTrue(d.changes.isEmpty() && d.policy.isEmpty() && d.shape.isEmpty() && d.ripple.isEmpty());
  }

  @Test
  void commentsFormattingAndLocalNamesAreNotChanges() throws IOException {
    Differ d = diff(
        new String[] {LEDGER, "public class Report { String render() { int n = 1; return \"a\" + n; } }"},
        new String[] {LEDGER, "public class Report {\n\n  // reformatted\n  String render() {\n    int count = 1;\n    return \"a\" + count;\n  }\n}"});
    assertTrue(d.changes.isEmpty(), d.changes.toString());
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
    // Of countShort's two identical lambdas, only the second changed: it is reported as changed,
    // and countShort itself, whose own bytecode is the same, is not.
    java.util.List<String> changed = d.changes.stream().map(c -> c.method().name() + ":" + c.kind()).sorted().toList();
    assertEquals(java.util.List.of("lambda$countShort$4:changed", "lambda$post$1:added", "post:changed"),
        changed);
  }

  @Test
  void isolatedBodyChangeIsSkippable() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT},
        new String[] {LEDGER, CHECKOUT, "public class Report { public String render() { return \"b\"; } }"});
    assertEquals(Bucket.SKIP, only(d).bucket());
    assertTrue(d.shape.isEmpty());
  }

  @Test
  void changeOnACriticalPathMustBeReviewed() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT},
        new String[] {LEDGER, "public class Checkout { Ledger l; void submit() { l.post(6); } }", REPORT});
    Change c = only(d);
    assertEquals(Bucket.MUST, c.bucket());
    assertEquals(Set.of("MONEY"), c.domains());
    assertTrue(c.newDomains().isEmpty());
  }

  @Test
  void oneLineThatStartsCallingASinkIsFlaggedAsANewPath() throws IOException {
    Differ d = diff(
        new String[] {LEDGER, "public class Report { Ledger l; String render() { return \"a\"; } }",
            "public class Page { Report r; String show() { return r.render(); } }"},
        new String[] {LEDGER, "public class Report { Ledger l; String render() { l.post(1); return \"a\"; } }",
            "public class Page { Report r; String show() { return r.render(); } }"});
    Change c = d.changes.stream().filter(x -> x.method().name().equals("render")).findFirst().orElseThrow();
    assertEquals(Bucket.MUST, c.bucket());
    assertEquals(Set.of("MONEY"), c.newDomains());
    assertEquals(1, d.ripple.size());
    assertEquals("show", d.ripple.get(0).name());
  }

  @Test
  void removingTheCallToASinkIsStillReviewed() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT},
        new String[] {LEDGER, "public class Checkout { Ledger l; void submit() { } }"});
    Change c = only(d);
    assertEquals(Bucket.MUST, c.bucket());
    assertTrue(c.reason().contains("no longer"));
  }

  @Test
  void addingOrRemovingASinkIsAPolicyChange() throws IOException {
    String untagged = "public class Ledger { public void post(long cents) {} public int size() { return 0; } }";
    Differ removed = diff(new String[] {LEDGER, CHECKOUT}, new String[] {untagged, CHECKOUT});
    assertEquals(1, removed.policy.size());
    assertTrue(removed.policy.get(0).startsWith("sink removed"));
    Differ added = diff(new String[] {untagged, CHECKOUT}, new String[] {LEDGER, CHECKOUT});
    assertTrue(added.policy.get(0).startsWith("sink added"));
    assertEquals(1, added.ripple.size());
  }

  @Test
  void structureChangesAreReportedSeparatelyFromBodies() throws IOException {
    Differ d = diff(new String[] {LEDGER, CHECKOUT, REPORT},
        new String[] {LEDGER, CHECKOUT,
            "public class Report { public String render() { return new Csv().export(); } public String title() { return \"t\"; } }",
            "public class Csv { public String export() { return \"\"; } }"});
    assertTrue(d.shape.contains("new public type Csv"), d.shape.toString());
    assertTrue(d.shape.contains("new public method Report.title()"), d.shape.toString());
    assertEquals(0, d.count(Bucket.MUST));
    Differ dep = diff(new String[] {LEDGER, CHECKOUT, REPORT},
        new String[] {LEDGER, CHECKOUT, "public class Report { Checkout c; public String render() { return \"a\"; } }"});
    assertTrue(dep.shape.stream().anyMatch(s -> s.startsWith("new dependency Report -> Checkout")), dep.shape.toString());
    assertTrue(dep.shape.stream().anyMatch(s -> s.startsWith("position Checkout: LEAF -> TRUNK")), dep.shape.toString());
  }
}
