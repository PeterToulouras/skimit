package dev.skimit.analyzer;

import dev.skimit.analyzer.Analysis.Position;
import dev.skimit.analyzer.Analysis.Verdict;
import dev.skimit.analyzer.Differ.Bucket;
import dev.skimit.analyzer.Differ.Change;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders analyses and diffs as Markdown (for people) and JSON (for gates). */
public final class Report {
  private Report() {}

  static final String DEFAULT_NOTE = "No sinks are declared, so the built-in rule is in use: every data write is critical"
      + " (repositories, mappers, DAOs, and common clients such as JDBC, JPA, MyBatis, MongoDB, DynamoDB and S3)."
      + " Declare your own with `--policy` or `@Critical`, or pass `--no-default-sinks` to turn this off.";
  static final String NO_SINKS_NOTE = "**No sinks found, so nothing is critical and every method is isolated.**"
      + " Nothing is declared, and the built-in rule (data writes through repositories, mappers, DAOs and common"
      + " database clients) matched nothing in this code. Declare what is critical with `--policy` or `@Critical`.";

  public static String summary(Analysis a, boolean listMethods) {
    StringBuilder sb = new StringBuilder("# skimit analysis\n\n");
    if (a.usingDefaultSinks) sb.append(DEFAULT_NOTE).append("\n\n");
    if (a.sinks.isEmpty()) sb.append(NO_SINKS_NOTE).append("\n\n");
    sb.append("## Sinks\n\n");
    if (a.sinks.isEmpty()) sb.append("None declared. Add `@Critical` or a policy file; without sinks nothing is critical.\n");
    for (Map.Entry<MethodKey, Set<String>> e : a.sinks.entrySet()) {
      sb.append("- `").append(e.getKey().pretty()).append("` ").append(e.getValue()).append('\n');
    }
    Map<Verdict, int[]> totals = a.totals();
    int methods = totals.values().stream().mapToInt(t -> t[0]).sum();
    int insns = totals.values().stream().mapToInt(t -> t[1]).sum();
    sb.append("\n## Methods by verdict\n\n| Verdict | Methods | Share | Share by code size |\n|---|---:|---:|---:|\n");
    for (Map.Entry<Verdict, int[]> e : totals.entrySet()) {
      sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()[0]).append(" | ")
          .append(pct(e.getValue()[0], methods)).append(" | ").append(pct(e.getValue()[1], insns)).append(" |\n");
    }
    sb.append("\n## Classes by position\n\n");
    for (Position p : Position.values()) {
      List<String> in = a.positions.entrySet().stream().filter(e -> e.getValue() == p).map(Map.Entry::getKey)
          .sorted(Comparator.comparing((String c) -> -a.dependents.get(c)).thenComparing(c -> c)).toList();
      sb.append("- ").append(p).append(": ").append(in.size());
      if (p != Position.LEAF && !in.isEmpty()) {
        sb.append(" (").append(in.stream().limit(8)
            .map(c -> c.substring(c.lastIndexOf('/') + 1) + " " + a.dependents.get(c)).collect(Collectors.joining(", ")));
        sb.append(in.size() > 8 ? ", ...)" : ")");
      }
      sb.append('\n');
    }
    if (listMethods) {
      sb.append("\n## Every method\n\n");
      for (Map.Entry<MethodKey, Verdict> e : a.verdicts.entrySet()) {
        sb.append("- ").append(e.getValue()).append(" `").append(e.getKey().pretty()).append('`');
        String why = a.explain(e.getKey());
        if (!why.isEmpty()) sb.append(": ").append(why);
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  public static String markdown(Differ d) {
    StringBuilder sb = new StringBuilder("# Review manifest\n\n");
    if (d.noSinks) sb.append(NO_SINKS_NOTE).append("\n\n");
    sb.append(d.changes.size()).append(d.changes.size() == 1 ? " method" : " methods").append(" changed: **").append(d.count(Bucket.MUST)).append(" must review**, ")
        .append(d.count(Bucket.SHOULD)).append(" should review, ").append(d.count(Bucket.SKIP)).append(" isolated.\n");
    if (d.usingDefaultSinks) sb.append("\n").append(DEFAULT_NOTE).append("\n");
    if (!d.policy.isEmpty()) {
      sb.append("\n## Policy changes (always reviewed)\n\n");
      for (String p : d.policy) sb.append("- ").append(p).append('\n');
    }
    section(sb, d, Bucket.MUST, "Must review: on a critical path");
    // Methods that were isolated are listed below with their reason; the sentence names the rest.
    Set<MethodKey> listed = d.reached.stream().map(Differ.Reached::method).collect(Collectors.toSet());
    List<MethodKey> ripple = d.ripple.stream().filter(m -> !listed.contains(m)).toList();
    if (!ripple.isEmpty()) {
      sb.append("\nBecause of these changes, ").append(ripple.size())
          .append(" untouched method(s) can now reach a sink: ")
          .append(ripple.stream().limit(10).map(m -> "`" + m.pretty() + "`").collect(Collectors.joining(", ")))
          .append(ripple.size() > 10 ? ", ...\n" : "\n");
    }
    section(sb, d, Bucket.SHOULD, "Should review: takes part in a critical flow, or cannot be proven clear");
    if (!d.reached.isEmpty()) {
      sb.append("\n## Newly reachable: unchanged, but no longer isolated\n\n")
          .append("These bodies did not change, but a change elsewhere connects them to a critical flow."
              + " Read them once.\n\n");
      for (Differ.Reached r : d.reached) {
        sb.append("- `").append(r.method().pretty()).append("` (was isolated, now ")
            .append(r.now() == Analysis.Verdict.ON_PATH ? "on a critical path" : r.now() == Analysis.Verdict.INFLUENCES
                ? "in a critical flow" : "unproven").append(")")
            .append("\n  - ").append(r.reason());
        if (!r.explanation().isEmpty()) sb.append("\n  - ").append(r.explanation());
        sb.append('\n');
      }
    }
    if (!d.shape.isEmpty()) {
      sb.append("\n## Shape changes: review the structure, not the bodies\n\n");
      for (String s : d.shape) sb.append("- ").append(s).append('\n');
    }
    section(sb, d, Bucket.SKIP, "Isolated: nothing connects these to a sink, body review can be skipped");
    return sb.toString();
  }

  private static void section(StringBuilder sb, Differ d, Bucket bucket, String title) {
    List<Change> in = d.changes.stream().filter(c -> c.bucket() == bucket).toList();
    if (in.isEmpty()) return;
    sb.append("\n## ").append(title).append("\n\n");
    for (Change c : in) {
      sb.append("- `").append(c.method().pretty()).append("` (").append(c.kind()).append(")");
      if (!c.domains().isEmpty()) sb.append(" ").append(c.domains());
      if (!c.newDomains().isEmpty()) sb.append(" **NEW PATH into ").append(String.join(", ", c.newDomains())).append("**");
      if (bucket != Bucket.SKIP) {
        sb.append("\n  - ").append(c.reason());
        if (c.proofs().size() > 1) {
          for (Map.Entry<String, List<MethodKey>> p : c.proofs().entrySet()) {
            sb.append("\n  - ").append(p.getKey()).append(": ").append(Analysis.explainCalls(p.getValue()));
          }
        } else if (!c.explanation().isEmpty()) {
          sb.append("\n  - ").append(c.explanation());
        }
      }
      sb.append('\n');
    }
  }

  private static String pct(int part, int whole) {
    return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
  }

  // ---- HTML ---------------------------------------------------------------------------------

  /** One self-contained page: the map of verdicts and, for a diff, the manifest beside it. */
  public static String html(Analysis base, Analysis head, Differ diff) throws java.io.IOException {
    String template;
    try (java.io.InputStream in = Report.class.getResourceAsStream("report.html")) {
      if (in == null) throw new java.io.IOException("report template missing from the jar");
      template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    // Method ids are long and repeat in every edge and proof; the page stores each once and refers
    // to it by number, which keeps a large codebase's report a few megabytes instead of tens.
    Ids ids = new Ids();
    String body = "\"base\":" + (base == null ? "null" : json(base, ids).strip())
        + ",\"head\":" + json(head, ids).strip()
        + ",\"diff\":" + (diff == null ? "null" : json(diff, ids).strip());
    String data = "{\"ids\":" + arr(ids.table) + "," + body + "}";
    return template.replace("__DATA__", data.replace("</", "<\\/"));
  }

  /** Interns method ids for the HTML page; a null {@code Ids} writes them out in full. */
  private static final class Ids {
    final Map<String, Integer> index = new java.util.HashMap<>();
    final List<String> table = new ArrayList<>();

    String ref(String id) {
      return String.valueOf(index.computeIfAbsent(id, k -> { table.add(k); return table.size() - 1; }));
    }
  }

  private static String ref(Ids ids, MethodKey m) {
    return ids == null ? str(m.id()) : ids.ref(m.id());
  }

  private static String refs(Ids ids, List<MethodKey> ms) {
    return "[" + ms.stream().map(m -> ref(ids, m)).collect(Collectors.joining(",")) + "]";
  }

  /** Every domain's proof, written only when there is more than one; otherwise the witness says it all. */
  private static String proofs(Ids ids, Map<String, List<MethodKey>> proofs) {
    if (proofs.size() < 2) return "";
    return ",\"proofs\":{" + proofs.entrySet().stream().map(e -> str(e.getKey()) + ":" + refs(ids, e.getValue()))
        .collect(Collectors.joining(",")) + "}";
  }

  // ---- JSON -----------------------------------------------------------------------------------

  public static String json(Differ d) {
    return json(d, null);
  }

  private static String json(Differ d, Ids ids) {
    List<String> changes = new ArrayList<>();
    for (Change c : d.changes) {
      changes.add("{\"method\":" + ref(ids, c.method()) + "," + kv("kind", c.kind()) + "," + kv("bucket", c.bucket().name())
          + "," + kv("verdict", c.verdict().name()) + ",\"domains\":" + arr(c.domains()) + ",\"newDomains\":" + arr(c.newDomains())
          + "," + kv("reason", c.reason()) + "," + kv("name", c.method().pretty())
          + ",\"witness\":" + refs(ids, c.witness()) + proofs(ids, c.proofs()) + "}");
    }
    return "{\"mustReview\":" + d.count(Bucket.MUST) + ",\"shouldReview\":" + d.count(Bucket.SHOULD)
        + ",\"isolated\":" + d.count(Bucket.SKIP) + ",\"noSinks\":" + d.noSinks + ",\"defaultSinks\":" + d.usingDefaultSinks
        + ",\"policy\":" + arr(d.policy) + ",\"shape\":" + arr(d.shape)
        + ",\"ripple\":" + refs(ids, d.ripple)
        + ",\"reached\":[" + d.reached.stream().map(r -> "{\"method\":" + ref(ids, r.method()) + "," + kv("verdict", r.now().name())
            + "," + kv("was", "ISOLATED") + "," + kv("reason", r.reason()) + "," + kv("name", r.method().pretty())
            + ",\"witness\":" + refs(ids, r.witness()) + "}").collect(Collectors.joining(",")) + "]"
        + ",\"changes\":[" + String.join(",", changes) + "]}\n";
  }

  public static String json(Analysis a) {
    return json(a, null);
  }

  private static String json(Analysis a, Ids ids) {
    List<String> methods = new ArrayList<>();
    for (Map.Entry<MethodKey, Verdict> e : a.verdicts.entrySet()) {
      MethodKey m = e.getKey();
      // The page derives the class from the id and has no use for the hash.
      String extra = ids != null ? "" : "," + kv("class", m.owner().replace('/', '.')) + "," + kv("hash", a.hashes.get(m));
      methods.add("{\"method\":" + ref(ids, m) + "," + kv("name", m.pretty()) + extra + "," + kv("reason", a.reason(m))
          + ",\"links\":" + arr(a.links(m)) + "," + kv("verdict", e.getValue().name())
          + ",\"domains\":" + arr(a.domains(m)) + ",\"witness\":" + refs(ids, a.witness(m)) + proofs(ids, a.proofs(m)) + "}");
    }
    List<String> classes = new ArrayList<>();
    for (Map.Entry<String, Position> e : a.positions.entrySet()) {
      classes.add("{" + kv("class", e.getKey().replace('/', '.')) + "," + kv("position", e.getValue().name())
          + ",\"dependents\":" + a.dependents.get(e.getKey()) + "}");
    }
    List<String> edges = new ArrayList<>();
    for (MethodKey from : a.verdicts.keySet()) {
      for (MethodKey to : a.graph.calleesOf(from)) {
        edges.add("[" + ref(ids, from) + "," + ref(ids, to) + "]");
      }
    }
    return "{\"defaultSinks\":" + a.usingDefaultSinks + ",\"sinks\":" + refs(ids, new ArrayList<>(a.sinks.keySet()))
        + ",\"edges\":[" + String.join(",", edges) + "]"
        + ",\"classes\":[" + String.join(",", classes) + "],\"methods\":[" + String.join(",", methods) + "]}\n";
  }

  private static String kv(String k, String v) {
    return str(k) + ":" + str(v);
  }

  private static String arr(Collection<String> items) {
    return "[" + items.stream().map(Report::str).collect(Collectors.joining(",")) + "]";
  }

  private static String str(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        default -> {
          if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
          else sb.append(ch);
        }
      }
    }
    return sb.append('"').toString();
  }
}
