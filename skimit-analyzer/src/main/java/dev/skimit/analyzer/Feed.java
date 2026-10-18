package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import dev.skimit.analyzer.Behaviour.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What a change does to the program's behaviour, one line per thing a reader would want to know,
 * most consequential first. A change that moves, renames or reshapes code without changing what
 * any way in can cause produces no line at all.
 */
public final class Feed {

  /** How much a line matters to the reader; lower comes first. */
  public enum Kind {
    /** A way in gains or loses the ability to delete data. */
    DELETES,
    /** A way in gains an effect: writing, sending, calling out. */
    GAINS,
    /** A new way in, with what it can cause. */
    NEW_WAY_IN,
    /** A way in that could cause something is gone. */
    REMOVED_WAY_IN,
    /** A way in can no longer cause something. */
    LOSES,
    /** Same effects, but code on the way to one of them changed. */
    CHANGED_PATH
  }

  public record Line(Kind kind, String text) {}

  public final List<Line> lines = new ArrayList<>();

  public Feed(Behaviour base, Behaviour head, Differ diff) {
    Map<String, String> matched = match(base, head, diff);
    Map<Effect, Set<String>> before = base.causes();
    Map<Effect, Set<String>> after = head.causes();
    Set<MethodKey> changed = new HashSet<>();
    for (Differ.Change c : diff.changes) if (!c.kind().equals("removed")) changed.add(c.method());

    // For each changed method, the (way in, effect) pairs whose path it lies on, when the pair
    // itself did not change. One line per group of methods that touch exactly the same pairs.
    Map<MethodKey, Set<String>> onPaths = new TreeMap<>();
    for (Map.Entry<String, Map<Effect, List<MethodKey>>> e : head.effects.entrySet()) {
      String id = e.getKey();
      String was = matched.get(id);
      Map<Effect, List<MethodKey>> old = was == null ? Map.of() : base.effects.get(was);
      Set<Effect> gained = new TreeSet<>(e.getValue().keySet());
      gained.removeAll(old.keySet());
      Set<Effect> lost = new TreeSet<>(old.keySet());
      lost.removeAll(e.getValue().keySet());
      if (was == null) {
        lines.add(new Line(Kind.NEW_WAY_IN, "New way in: " + id + " " + list(e.getValue().keySet(), before, after)));
        continue;
      }
      for (Effect x : gained) {
        lines.add(new Line(x.verb().equals("deletes") ? Kind.DELETES : Kind.GAINS,
            id + " now " + x + count(x, before, after)));
      }
      for (Effect x : lost) {
        lines.add(new Line(x.verb().equals("deletes") ? Kind.DELETES : Kind.LOSES,
            id + " no longer " + x + count(x, before, after)));
      }
      for (Effect x : e.getValue().keySet()) {
        if (gained.contains(x)) continue;
        Set<MethodKey> lead = head.leadsTo.getOrDefault(x, Set.of());
        for (MethodKey m : head.runs.get(id)) {
          if (changed.contains(m) && lead.contains(m)) onPaths.computeIfAbsent(m, k -> new TreeSet<>()).add(x + "\u0000" + id);
        }
      }
    }
    Set<String> stillThere = new HashSet<>(matched.values());
    for (Map.Entry<String, Map<Effect, List<MethodKey>>> e : base.effects.entrySet()) {
      if (stillThere.contains(e.getKey())) continue;
      lines.add(new Line(Kind.REMOVED_WAY_IN, "Removed: " + e.getKey() + " (it " + effects(e.getValue().keySet()) + ")"));
    }
    Map<Set<String>, List<MethodKey>> byPairs = new LinkedHashMap<>();
    for (Map.Entry<MethodKey, Set<String>> e : onPaths.entrySet()) {
      // A lambda is part of the method it is written in.
      byPairs.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
    }
    for (Map.Entry<Set<String>, List<MethodKey>> e : byPairs.entrySet()) {
      Map<Effect, Set<String>> byEffect = new TreeMap<>();
      for (String pair : e.getKey()) {
        String[] p = pair.split("\u0000", 2);
        Effect x = after.keySet().stream().filter(f -> f.toString().equals(p[0])).findFirst().orElseThrow();
        byEffect.computeIfAbsent(x, k -> new TreeSet<>()).add(p[1]);
      }
      Set<String> ways = byEffect.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));
      String who = e.getValue().stream().map(Feed::shortName).distinct().limit(2).collect(Collectors.joining(", "))
          + (e.getValue().stream().map(Feed::shortName).distinct().count() > 2 ? " and others" : "");
      lines.add(new Line(Kind.CHANGED_PATH, cap(effects(byEffect.keySet())) + ": code on the way changed in " + who
          + (ways.size() == 1 ? " (from " + ways.iterator().next() + ")" : " (from " + ways.size() + " ways in: " + first(ways, 2) + ")")));
    }
    // Deletions of data rank first among changed paths too.
    lines.sort(Comparator.comparing(Line::kind)
        .thenComparing(l -> l.kind() == Kind.CHANGED_PATH && !l.text().toLowerCase().contains("deletes"))
        .thenComparing(Line::text));
  }

  /** Each way in after the change to the one it was before: by name, or by the method it runs when its class moved. */
  private static Map<String, String> match(Behaviour base, Behaviour head, Differ diff) {
    Map<String, String> out = new HashMap<>();
    Map<MethodKey, String> baseByMethod = new HashMap<>();
    base.entries.values().forEach(e -> baseByMethod.put(e.method(), e.id()));
    Set<String> taken = new HashSet<>();
    for (String id : head.entries.keySet()) {
      if (base.entries.containsKey(id)) {
        out.put(id, id);
        taken.add(id);
      }
    }
    for (Entry e : head.entries.values()) {
      if (out.containsKey(e.id())) continue;
      String was = baseByMethod.get(diff.renames.back(e.method()));
      if (was != null && taken.add(was)) out.put(e.id(), was);
    }
    return out;
  }

  private static String count(Effect x, Map<Effect, Set<String>> before, Map<Effect, Set<String>> after) {
    int b = before.getOrDefault(x, Set.of()).size();
    int a = after.getOrDefault(x, Set.of()).size();
    if (b == 0) return " (nothing " + x.verb().replace("deletes", "deleted").replace("writes", "wrote")
        .replace("sends", "sent").replace("calls", "called") + " that before)";
    return a == b ? "" : " (" + b + " → " + a + " ways in)";
  }

  private static String list(Set<Effect> effects, Map<Effect, Set<String>> before, Map<Effect, Set<String>> after) {
    return "→ " + effects.stream().map(x -> x + count(x, before, after)).collect(Collectors.joining(", "));
  }

  private static String effects(Set<Effect> effects) {
    List<String> words = effects.stream().map(Effect::toString).toList();
    return words.size() <= 3 ? String.join(", ", words) : String.join(", ", words.subList(0, 3)) + " and " + (words.size() - 3) + " more";
  }

  private static String cap(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String first(Set<String> items, int n) {
    return items.stream().limit(n).collect(Collectors.joining(", ")) + (items.size() > n ? ", …" : "");
  }

  private static String shortName(MethodKey m) {
    String p = m.pretty();
    return p.indexOf('(') < 0 ? p : p.substring(0, p.indexOf('('));
  }

  public boolean isEmpty() {
    return lines.isEmpty();
  }

  /** True when something other than changed code on an existing path: a reader must look. */
  public boolean structural() {
    return lines.stream().anyMatch(l -> l.kind() != Kind.CHANGED_PATH);
  }
}
