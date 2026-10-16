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

  static final String DEFAULT_NOTE = "<sub>Critical here means data writes, the built-in rule: repositories, mappers,"
      + " DAOs, and clients such as JDBC, JPA, MyBatis, MongoDB, DynamoDB and S3. Declare your own with a policy file or"
      + " `@Critical`.</sub>";
  /** The same note for the analysis summary, which is read in a terminal. */
  static final String DEFAULT_NOTE_PLAIN = "No sinks are declared, so the built-in rule is in use: every data write is critical"
      + " (repositories, mappers, DAOs, and common clients such as JDBC, JPA, MyBatis, MongoDB, DynamoDB and S3)."
      + " Declare your own with `--policy` or `@Critical`, or pass `--no-default-sinks` to turn this off.";
  static final String NO_SINKS_NOTE = "**No sinks found, so nothing is critical and every method is isolated.**"
      + " Nothing is declared, and the built-in rule (data writes through repositories, mappers, DAOs and common"
      + " database clients) matched nothing in this code. Declare what is critical with `--policy` or `@Critical`.";

  public static String summary(Analysis a, boolean listMethods) {
    StringBuilder sb = new StringBuilder("# skimit analysis\n\n");
    if (a.usingDefaultSinks) sb.append(DEFAULT_NOTE_PLAIN).append("\n\n");
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

  // ---- The review, in words ------------------------------------------------------------------

  static final String MUST_TITLE = "Must read";
  static final String SHOULD_TITLE = "Worth a look";
  static final String SKIP_TITLE = "Skim";

  /** The answer in one or two sentences: what to read, and how closely. */
  public static String headline(Differ d) {
    long must = d.count(Bucket.MUST);
    long should = d.count(Bucket.SHOULD);
    List<String> out = new ArrayList<>();
    if (d.noSinks) out.add("Nothing is critical here: no sinks were found, so every method is isolated.");
    if (!d.policy.isEmpty()) out.add("What counts as critical changed: review that first.");
    if (!d.moves.isEmpty()) {
      long same = d.moves.stream().filter(m -> m.unchanged() == m.methods()).count();
      out.add(d.moves.size() == 1
          ? (same == 1 ? "1 class moved without changes." : "1 class moved, with some changes.")
          : d.moves.size() + " classes moved" + (same == d.moves.size() ? " without changes." : ", " + same + " of them without changes."));
    }
    if (must > 0) {
      out.add("Read " + methods(must) + " closely" + (should > 0 ? " and glance at " + should : "") + ".");
    } else if (should > 0) {
      out.add("Nothing changed can reach anything critical directly. Glance at " + methods(should) + ".");
    } else if (!d.changes.isEmpty() && !d.noSinks) {
      out.add("Nothing changed can reach anything critical: skim " + (d.changes.size() == 1 ? "it." : "them."));
    } else if (d.changes.isEmpty() && d.moves.isEmpty() && d.policy.isEmpty()) {
      out.add("Nothing to review: no compiled method changed.");
    }
    if (!d.reached.isEmpty()) {
      out.add(d.reached.size() == 1 ? "1 unchanged method now takes part in a critical flow: read it once."
          : d.reached.size() + " unchanged methods now take part in a critical flow: read them once.");
    }
    return String.join(" ", out);
  }

  private static String methods(long n) {
    return n + (n == 1 ? " method" : " methods");
  }

  /** What a domain is called in a sentence: "a data write", "a MONEY sink". */
  static String domainPhrase(Set<String> domains) {
    if (domains.isEmpty()) return "something critical";
    return domains.stream().map(d -> d.equals("DATA_WRITE") ? "a data write" : "a " + d + " sink")
        .collect(Collectors.joining(" and "));
  }

  /** Why to read a change, as a sentence for someone who has not read the docs. */
  public static String says(Change c) {
    String sentence;
    if (c.verdict() == Verdict.ON_PATH && c.reason().equals("is a critical sink")) {
      sentence = "Is " + domainPhrase(c.domains()) + " itself";
    } else if (c.verdict() == Verdict.ON_PATH && c.reason().equals("can reach a critical sink")) {
      sentence = "Reaches " + domainPhrase(c.domains());
    } else if (c.verdict() == Verdict.ON_PATH && c.reason().equals("before removal, can reach a critical sink")) {
      sentence = "Reached " + domainPhrase(c.domains()) + " before it was removed";
    } else {
      String r = plainReason(c.reason(), c.method());
      if (r.startsWith("before removal, ")) r = "before it was removed, it " + r.substring("before removal, ".length());
      sentence = Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }
    if (!c.newDomains().isEmpty()) sentence += ", which it could not before this change";
    return sentence;
  }

  private static final java.util.regex.Pattern FRAMEWORK_CALL =
      java.util.regex.Pattern.compile("runs when a framework or library calls (.+) through (.+), at a time the analysis cannot see");

  /** An analysis reason, shortened where it repeats the method's own name. */
  static String plainReason(String reason, MethodKey self) {
    java.util.regex.Matcher m = FRAMEWORK_CALL.matcher(reason);
    if (m.find()) {
      String how = m.group(2);
      String who = m.group(1).equals(self.pretty()) ? "it" : "`" + m.group(1) + "`";
      return reason.substring(0, m.start()) + "a framework or library calls " + who + " through " + how
          + ", at a time skimit cannot see";
    }
    return reason.replace("a call the analysis cannot follow", "a call skimit cannot follow");
  }

  /** The steps from a method to the sink it reaches, without the method itself; empty if it reaches none. */
  public static List<String> path(Change c) {
    if (c.verdict() != Verdict.ON_PATH || c.witness().size() < 2) return List.of();
    return c.witness().subList(1, c.witness().size()).stream().map(Report::shortName).toList();
  }

  /** {@code Ledger.post}: a method by its class and name, for a path where the types add nothing. */
  static String shortName(MethodKey m) {
    String p = m.pretty();
    int paren = p.indexOf('(');
    return paren < 0 ? p : p.substring(0, paren);
  }

  /** The class part of a method's short form: {@code Ledger} for {@code Ledger.post(long)}. */
  static String classOf(MethodKey m) {
    String p = shortName(m);
    int dot = p.lastIndexOf('.', p.endsWith("]") ? p.lastIndexOf('[') : p.length());
    return dot < 0 ? p : p.substring(0, dot);
  }

  public static String markdown(Differ d) {
    StringBuilder sb = new StringBuilder("## skimit: ").append(headline(d)).append("\n");
    if (!d.policy.isEmpty()) {
      sb.append("\n**What counts as critical changed** (").append(d.policy.size()).append("): always reviewed.\n\n");
      for (String p : d.policy) sb.append("- ").append(p).append('\n');
    }
    section(sb, d, Bucket.MUST, MUST_TITLE, "can reach something critical. Read every line.");
    section(sb, d, Bucket.SHOULD, SHOULD_TITLE, "skimit cannot rule these out. A quick read is enough.");
    if (!d.reached.isEmpty()) {
      sb.append("\n**Newly reachable** (").append(d.reached.size())
          .append("): unchanged, so the diff will not show them, but a change elsewhere connects them to a critical flow."
              + " Read them once.\n\n");
      for (Differ.Reached r : d.reached) {
        sb.append("- `").append(r.method().pretty()).append("` was isolated  \n  ")
            .append(Character.toUpperCase(r.reason().charAt(0))).append(r.reason().substring(1)).append(".\n");
      }
    }
    // Ripple: untouched methods that now reach a sink, beyond those listed as newly reachable.
    Set<MethodKey> listed = d.reached.stream().map(Differ.Reached::method).collect(Collectors.toSet());
    List<MethodKey> ripple = d.ripple.stream().filter(m -> !listed.contains(m)).toList();
    if (!ripple.isEmpty()) {
      sb.append("\nBecause of these changes, ").append(methods(ripple.size()))
          .append(" that did not change can now reach a sink: ")
          .append(ripple.stream().limit(10).map(m -> "`" + m.pretty() + "`").collect(Collectors.joining(", ")))
          .append(ripple.size() > 10 ? ", ...\n" : "\n");
    }
    if (!d.moves.isEmpty()) {
      sb.append("\n**Moved** (").append(d.moves.size()).append(d.moves.size() == 1 ? " class" : " classes")
          .append("): compared with what they were, so only real edits are listed above.\n\n");
      for (Differ.Move m : d.moves) {
        String[] n = Differ.shortened(m.from(), m.to());
        sb.append("- `").append(n[0]).append("` → `").append(n[1]).append("`: ");
        sb.append(m.unchanged() == m.methods() ? (m.methods() == 1 ? "its method is unchanged" : "all " + m.methods() + " methods unchanged")
            : m.unchanged() + " of " + m.methods() + " methods unchanged");
        long critical = m.carried().stream().filter(c -> c.verdict() == Verdict.ON_PATH).count();
        if (critical > 0) sb.append("; ").append(critical).append(critical == 1 ? " of them still reaches " : " of them still reach ")
            .append(domainPhrase(m.carried().stream().filter(c -> c.verdict() == Verdict.ON_PATH)
                .flatMap(c -> c.domains().stream()).collect(Collectors.toCollection(java.util.TreeSet::new))));
        sb.append('\n');
      }
    }
    List<Change> skim = d.changes.stream().filter(c -> c.bucket() == Bucket.SKIP).toList();
    if (!skim.isEmpty()) {
      sb.append("\n<details><summary><b>").append(SKIP_TITLE).append("</b> (").append(skim.size())
          .append("): nothing connects these to anything critical</summary>\n\n");
      java.util.Map<String, List<Change>> byClass = new java.util.TreeMap<>();
      for (Change c : skim) byClass.computeIfAbsent(classOf(c.method()), k -> new ArrayList<>()).add(c);
      for (Map.Entry<String, List<Change>> e : byClass.entrySet()) {
        Map<String, Long> kinds = e.getValue().stream().collect(Collectors.groupingBy(Change::kind, java.util.TreeMap::new, Collectors.counting()));
        sb.append("- `").append(e.getKey()).append("`: ")
            .append(kinds.entrySet().stream().map(k -> k.getValue() + " " + k.getKey()).collect(Collectors.joining(", ")))
            .append('\n');
      }
      sb.append("\n</details>\n");
    }
    if (!d.shape.isEmpty()) {
      sb.append("\n<details><summary><b>Structure</b> (").append(d.shape.size())
          .append("): public API and dependencies. Judge the design, not the bodies</summary>\n\n");
      for (String s : d.shape) sb.append("- ").append(s).append('\n');
      sb.append("\n</details>\n");
    }
    if (d.usingDefaultSinks && !d.noSinks) sb.append("\n").append(DEFAULT_NOTE).append("\n");
    if (d.noSinks) sb.append("\n").append(NO_SINKS_NOTE).append("\n");
    return sb.toString();
  }

  /** The most methods listed in a bucket; the HTML report has the rest. */
  static final int LISTED = 60;

  private static void section(StringBuilder sb, Differ d, Bucket bucket, String title, String hint) {
    List<Change> in = d.changes.stream().filter(c -> c.bucket() == bucket).toList();
    if (in.isEmpty()) return;
    sb.append("\n**").append(title).append("** (").append(in.size()).append("): ").append(hint).append("\n\n");
    for (Change c : in.subList(0, Math.min(in.size(), LISTED))) {
      sb.append("- `").append(c.method().pretty()).append("` ").append(c.kind());
      if (c.was() != null) sb.append(" (was `").append(shortName(c.was())).append("`)");
      sb.append("  \n  ").append(says(c));
      List<String> path = path(c);
      if (!path.isEmpty()) sb.append(": ").append(path.stream().map(x -> "`" + x + "`").collect(Collectors.joining(" → ")));
      sb.append(path.isEmpty() ? ".\n" : "\n");
    }
    if (in.size() > LISTED) sb.append("- and ").append(in.size() - LISTED).append(" more, listed in the full report\n");
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
      List<SourceDiff.Line> own = d.lines.get(c.method());
      changes.add("{\"method\":" + ref(ids, c.method()) + "," + kv("kind", c.kind()) + "," + kv("bucket", c.bucket().name())
          + "," + kv("verdict", c.verdict().name()) + ",\"domains\":" + arr(c.domains()) + ",\"newDomains\":" + arr(c.newDomains())
          + "," + kv("reason", c.reason()) + "," + kv("says", says(c)) + ",\"path\":" + arr(path(c))
          + "," + kv("name", c.method().pretty())
          + (c.was() == null ? "" : ",\"was\":" + ref(ids, c.was()))
          + ",\"witness\":" + refs(ids, c.witness()) + proofs(ids, c.proofs())
          + (own == null ? "" : ",\"lines\":[" + own.stream().map(l -> "[" + str(String.valueOf(l.kind())) + "," + l.before()
              + "," + l.after() + "," + str(l.text()) + "]").collect(Collectors.joining(",")) + "]")
          + "}");
    }
    List<String> moves = new ArrayList<>();
    for (Differ.Move m : d.moves) {
      moves.add("{" + kv("from", m.from()) + "," + kv("to", m.to()) + ",\"methods\":" + m.methods()
          + ",\"unchanged\":" + m.unchanged() + ",\"carried\":[" + m.carried().stream().map(c -> "{\"method\":" + ref(ids, c.method())
              + "," + kv("name", c.method().pretty()) + "," + kv("verdict", c.verdict().name()) + ",\"domains\":" + arr(c.domains())
              + ",\"path\":" + arr(c.verdict() == Verdict.ON_PATH && c.witness().size() > 1
                  ? c.witness().subList(1, c.witness().size()).stream().map(Report::shortName).toList() : List.of())
              + "}").collect(Collectors.joining(",")) + "]}");
    }
    return "{" + kv("headline", headline(d)) + ",\"mustReview\":" + d.count(Bucket.MUST) + ",\"shouldReview\":" + d.count(Bucket.SHOULD)
        + ",\"isolated\":" + d.count(Bucket.SKIP) + ",\"noSinks\":" + d.noSinks + ",\"defaultSinks\":" + d.usingDefaultSinks
        + ",\"policy\":" + arr(d.policy) + ",\"shape\":" + arr(d.shape)
        + ",\"ripple\":" + refs(ids, d.ripple)
        + ",\"reached\":[" + d.reached.stream().map(r -> "{\"method\":" + ref(ids, r.method()) + "," + kv("verdict", r.now().name())
            + "," + kv("was", "ISOLATED") + "," + kv("reason", r.reason()) + "," + kv("name", r.method().pretty())
            + ",\"witness\":" + refs(ids, r.witness()) + "}").collect(Collectors.joining(",")) + "]"
        + ",\"moves\":[" + String.join(",", moves) + "]"
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
