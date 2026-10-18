package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Renders behaviour for people: a map that fits on one screen, and a feed of what changed. */
public final class Ledger {
  private Ledger() {}

  /** The most items shown at once; the rest are counted, never listed. */
  static final int BUDGET = 7;

  public static String map(Behaviour b) {
    Map<Effect, Set<String>> causes = b.causes();
    StringBuilder sb = new StringBuilder("## What this program does\n\n");
    Map<String, Long> kinds = b.entries.values().stream()
        .collect(Collectors.groupingBy(Behaviour.Entry::kind, TreeMap::new, Collectors.counting()));
    sb.append(b.entries.size()).append(" ways in (")
        .append(kinds.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", ")))
        .append(") cause ").append(causes.size()).append(" effects.\n\n");
    List<Map.Entry<Effect, Set<String>>> ranked = new ArrayList<>(causes.entrySet());
    ranked.sort((x, y) -> x.getKey().rank() != y.getKey().rank() ? Integer.compare(x.getKey().rank(), y.getKey().rank())
        : y.getValue().size() != x.getValue().size() ? Integer.compare(y.getValue().size(), x.getValue().size())
        : x.getKey().toString().compareTo(y.getKey().toString()));
    for (Map.Entry<Effect, Set<String>> e : ranked.subList(0, Math.min(BUDGET, ranked.size()))) {
      sb.append("- **").append(e.getKey()).append("** from ").append(e.getValue().size())
          .append(e.getValue().size() == 1 ? " way in: " : " ways in: ")
          .append(e.getValue().stream().limit(3).collect(Collectors.joining(", ")))
          .append(e.getValue().size() > 3 ? ", …" : "").append('\n');
    }
    if (ranked.size() > BUDGET) sb.append("- and ").append(ranked.size() - BUDGET).append(" more effects\n");
    return sb.toString();
  }

  /** The feed for one change: at most {@link #BUDGET} lines, the rest counted. */
  public static String feed(Feed f) {
    if (f.isEmpty()) return "## No change in behaviour\n";
    StringBuilder sb = new StringBuilder("## ").append(f.lines.size())
        .append(f.lines.size() == 1 ? " change in behaviour\n\n" : " changes in behaviour\n\n");
    for (Feed.Line l : f.lines.subList(0, Math.min(BUDGET, f.lines.size()))) sb.append("- ").append(l.text()).append('\n');
    if (f.lines.size() > BUDGET) sb.append("- and ").append(f.lines.size() - BUDGET).append(" smaller changes\n");
    return sb.toString();
  }

  public static String json(Feed f) {
    return "{\"lines\":[" + f.lines.stream().map(l -> "{" + kv("kind", l.kind().name()) + "," + kv("text", l.text()) + "}")
        .collect(Collectors.joining(",")) + "]}\n";
  }

  public static String json(Behaviour b) {
    StringBuilder sb = new StringBuilder("{\"entries\":[");
    List<String> items = new ArrayList<>();
    for (Behaviour.Entry e : b.entries.values()) {
      items.add("{" + kv("id", e.id()) + "," + kv("kind", e.kind()) + "," + kv("method", e.method().pretty())
          + ",\"effects\":[" + b.effects.get(e.id()).keySet().stream().map(x -> str(x.toString())).collect(Collectors.joining(","))
          + "]}");
    }
    return sb.append(String.join(",", items)).append("]}\n").toString();
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
