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
    /** A route, job or listener answers to a new name and does the same things. */
    RENAMED_WAY_IN,
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
    // A handler that only got a new name and path runs the same code: that is not a change on its way.
    matched.forEach((id, was) -> {
      MethodKey now = head.entries.get(id).method();
      if (sameCode(base, base.entries.get(was).method(), head, now)) changed.remove(now);
    });

    // For each changed method, the (way in, effect) pairs whose path it lies on, when the pair
    // itself did not change. One line per group of methods that touch exactly the same pairs.
    Map<MethodKey, Set<String>> onPaths = new TreeMap<>();
    for (Map.Entry<String, Map<Effect, List<MethodKey>>> e : head.effects.entrySet()) {
      String id = e.getKey();
      // Code nothing calls is not a way in: a service written before its route, or dead code.
      if (head.entries.get(id).kind().equals("uncalled")) continue;
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
      if (!was.equals(id) && namedOutside(head.entries.get(id))) {
        lines.add(new Line(Kind.RENAMED_WAY_IN, was + " is now " + id + " (it " + effects(e.getValue().keySet()) + ")"));
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
      if (stillThere.contains(e.getKey()) || base.entries.get(e.getKey()).kind().equals("uncalled")) continue;
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
    // A way in renamed along with its method (/jobinfo/remove to /jobinfo/delete) is still the
    // same way in when it is the only one of its kind that does exactly the same things.
    Map<String, List<String>> leftBefore = new HashMap<>();
    for (Entry e : base.entries.values()) {
      if (!taken.contains(e.id())) leftBefore.computeIfAbsent(signature(e, base), k -> new ArrayList<>()).add(e.id());
    }
    Map<String, List<String>> leftAfter = new HashMap<>();
    for (Entry e : head.entries.values()) {
      if (!out.containsKey(e.id())) leftAfter.computeIfAbsent(signature(e, head), k -> new ArrayList<>()).add(e.id());
    }
    for (Map.Entry<String, List<String>> e : leftAfter.entrySet()) {
      List<String> was = new ArrayList<>(leftBefore.getOrDefault(e.getKey(), List.of()));
      List<String> now = new ArrayList<>(e.getValue());
      // Several alike: pair those that kept their last word (/user/updatePwd and /auth/updatePwd),
      // then a last one on each side.
      for (String n : List.copyOf(now)) {
        String w = was.stream().filter(b -> lastWord(b).equals(lastWord(n))).findFirst().orElse(null);
        if (w != null && was.stream().filter(b -> lastWord(b).equals(lastWord(n))).count() == 1) {
          out.put(n, w);
          was.remove(w);
          now.remove(n);
        }
      }
      if (was.size() == 1 && now.size() == 1) out.put(now.get(0), was.get(0));
    }
    return out;
  }

  private static String lastWord(String id) {
    return id.substring(Math.max(id.lastIndexOf('/'), id.lastIndexOf('.')) + 1);
  }

  /** The same instructions, whatever the method's name and annotations. */
  private static boolean sameCode(Behaviour a, MethodKey x, Behaviour b, MethodKey y) {
    return code(a, x).equals(code(b, y));
  }

  private static String code(Behaviour b, MethodKey m) {
    org.objectweb.asm.tree.MethodNode mn = b.analysis.graph.methods.get(m);
    if (mn == null) return "";
    org.objectweb.asm.util.Textifier t = new org.objectweb.asm.util.Textifier();
    mn.instructions.accept(new org.objectweb.asm.util.TraceMethodVisitor(t));
    StringBuilder sb = new StringBuilder(mn.desc);
    for (Object o : t.getText()) sb.append(o);
    return sb.toString();
  }

  private static String signature(Entry e, Behaviour b) {
    return e.kind() + "|" + b.effects.get(e.id()).keySet();
  }

  /** Routes, jobs and listeners are named by the outside world; a thread's name is only where its code sits. */
  private static boolean namedOutside(Entry e) {
    return Set.of("route", "job", "listener", "schedule", "event").contains(e.kind());
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
