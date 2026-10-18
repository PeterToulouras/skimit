package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Renders behaviour for people: a map that fits on one screen, and a feed of what a change did to it. */
public final class Output {
  private Output() {}

  /** The most items shown at once; the rest are folded away. */
  static final int BUDGET = 7;

  static final String DEFAULT_NOTE = "Effects here are data writes found by the built-in rule (repositories, mappers,"
      + " DAOs and common database clients), calls out and messages sent. Declare your own with a policy file or `@Critical`.";

  // ---- The map --------------------------------------------------------------------------------

  /** Effects most consequential first: deletes, then writes, then messages and calls; then by how many ways in reach them. */
  static List<Map.Entry<Effect, Set<String>>> ranked(Behaviour b) {
    List<Map.Entry<Effect, Set<String>>> out = new ArrayList<>(b.causes().entrySet());
    out.sort((x, y) -> x.getKey().rank() != y.getKey().rank() ? Integer.compare(x.getKey().rank(), y.getKey().rank())
        : y.getValue().size() != x.getValue().size() ? Integer.compare(y.getValue().size(), x.getValue().size())
        : x.getKey().toString().compareTo(y.getKey().toString()));
    return out;
  }

  public static String map(Behaviour b) {
    List<Map.Entry<Effect, Set<String>>> ranked = ranked(b);
    StringBuilder sb = new StringBuilder("## skimit: what this program does\n\n");
    Map<String, Long> kinds = b.entries.values().stream().filter(e -> !e.kind().equals("uncalled"))
        .collect(Collectors.groupingBy(Behaviour.Entry::kind, TreeMap::new, Collectors.counting()));
    long ways = kinds.values().stream().mapToLong(Long::longValue).sum();
    if (ranked.isEmpty()) return sb.append("No way in causes an effect skimit recognises.\n").toString();
    sb.append(ways).append(ways == 1 ? " way in (" : " ways in (")
        .append(kinds.entrySet().stream().map(e -> kind(e.getKey(), e.getValue())).collect(Collectors.joining(", ")))
        .append(") can cause ").append(ranked.size()).append(ranked.size() == 1 ? " effect.\n\n" : " effects.\n\n");
    for (Map.Entry<Effect, Set<String>> e : ranked.subList(0, Math.min(BUDGET, ranked.size()))) {
      sb.append("- **").append(e.getKey()).append("** from ").append(e.getValue().size())
          .append(e.getValue().size() == 1 ? " way in: " : " ways in: ")
          .append(e.getValue().stream().limit(3).map(w -> "`" + w + "`").collect(Collectors.joining(", ")))
          .append(e.getValue().size() > 3 ? ", …" : "").append('\n');
    }
    if (ranked.size() > BUDGET) sb.append("- and ").append(ranked.size() - BUDGET).append(" more effects\n");
    if (b.analysis.usingDefaultSinks) sb.append("\n<sub>").append(DEFAULT_NOTE).append("</sub>\n");
    return sb.toString();
  }

  /** "2 routes", "1 on startup". */
  static String kind(String kind, long n) {
    return n + (kind.equals("startup") ? " on startup" : kind.equals("event") ? (n == 1 ? " event handler" : " event handlers")
        : " " + kind + (n == 1 ? "" : "s"));
  }

  // ---- The feed -------------------------------------------------------------------------------

  /** The answer in one sentence. */
  static String headline(Feed f) {
    long broken = f.broken();
    long what = f.what();
    long how = f.lines.size() - broken - what;
    if (f.isEmpty()) return "No change in behaviour.";
    List<String> parts = new ArrayList<>();
    if (broken > 0) parts.add(broken == 1 ? "1 thing needs a person." : broken + " things need a person.");
    if (what > 0) parts.add(what == 1 ? "1 change to what the system can do." : what + " changes to what the system can do.");
    if (how > 0) parts.add((what > 0 || broken > 0 ? "" : "No new behaviour; ") + how
        + (how == 1 ? " change to how it does something it already did." : " changes to how it does what it already did."));
    return String.join(" ", parts);
  }

  public static String feed(Feed f, Behaviour head) {
    StringBuilder sb = new StringBuilder("## skimit: ").append(headline(f)).append("\n");
    if (!f.isEmpty()) {
      sb.append('\n');
      List<Feed.Line> lines = f.lines;
      for (Feed.Line l : lines.subList(0, Math.min(BUDGET, lines.size()))) sb.append("- ").append(markdown(l)).append('\n');
      if (lines.size() > BUDGET) {
        sb.append("\n<details><summary>").append(lines.size() - BUDGET).append(lines.size() - BUDGET == 1 ? " more change" : " more changes")
            .append("</summary>\n\n");
        for (Feed.Line l : lines.subList(BUDGET, lines.size())) sb.append("- ").append(markdown(l)).append('\n');
        sb.append("\n</details>\n");
      }
    }
    if (head.analysis.usingDefaultSinks) sb.append("\n<sub>").append(DEFAULT_NOTE).append("</sub>\n");
    return sb.toString();
  }

  private static String markdown(Feed.Line l) {
    return l.kind() == Feed.Kind.BROKEN ? "**" + l.text() + "**" : l.text();
  }

  // ---- JSON and the page ----------------------------------------------------------------------

  public static String json(Feed f, Differ d) {
    long what = f.what();
    return "{\"headline\":" + str(headline(f)) + ",\"broken\":" + f.broken() + ",\"what\":" + what
        + ",\"how\":" + (f.lines.size() - f.broken() - what) + ",\"lines\":["
        + f.lines.stream().map(l -> line(l, d)).collect(Collectors.joining(",")) + "]}\n";
  }

  private static String line(Feed.Line l, Differ d) {
    StringBuilder sb = new StringBuilder("{").append(kv("kind", l.kind().name())).append(',').append(kv("text", l.text()));
    if (!l.methods().isEmpty()) {
      sb.append(",\"methods\":[").append(l.methods().stream().map(m -> str(m.pretty())).collect(Collectors.joining(","))).append(']');
      // The code of each method the line is about; when there are several, each under its name.
      List<String> rows = new ArrayList<>();
      for (MethodKey m : l.methods()) {
        List<SourceDiff.Line> code = d == null ? null : d.lines.get(m);
        if (code == null) continue;
        if (l.methods().size() > 1) rows.add("[\"~\",0,0," + str(m.pretty()) + "]");
        for (SourceDiff.Line c : code) {
          rows.add("[" + str(String.valueOf(c.kind())) + "," + c.before() + "," + c.after() + "," + str(c.text()) + "]");
        }
      }
      if (!rows.isEmpty()) sb.append(",\"code\":[").append(String.join(",", rows)).append(']');
    }
    return sb.append('}').toString();
  }

  public static String json(Behaviour b) {
    List<String> items = new ArrayList<>();
    for (Behaviour.Entry e : b.entries.values()) {
      if (e.kind().equals("uncalled")) continue;
      items.add("{" + kv("id", e.id()) + "," + kv("kind", e.kind()) + "," + kv("method", e.method().pretty())
          + ",\"effects\":[" + b.effects.get(e.id()).keySet().stream().map(x -> str(x.toString())).collect(Collectors.joining(","))
          + "]}");
    }
    return "{\"defaultSinks\":" + b.analysis.usingDefaultSinks + ",\"entries\":[" + String.join(",", items) + "]}\n";
  }

  /** One self-contained page: the change's feed when there is one, and the map of the program after it. */
  public static String page(Behaviour head, Feed f, Differ d) throws IOException {
    String template;
    try (InputStream in = Output.class.getResourceAsStream("page.html")) {
      if (in == null) throw new IOException("page template missing from the jar");
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String data = "{\"feed\":" + (f == null ? "null" : json(f, d).strip()) + ",\"map\":" + json(head).strip() + "}";
    return template.replace("__DATA__", data.replace("</", "<\\/"));
  }

  static String kv(String k, String v) {
    return str(k) + ":" + str(v);
  }

  static String str(String s) {
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
