package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import dev.skimit.analyzer.Behaviour.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What a change does to the program's behaviour, one line per thing a reader would want to know,
 * most consequential first. A change that moves, renames or reshapes code without changing what
 * any way in can cause produces no line at all.
 */
public final class Feed {

  /** How much a line matters to the reader; lower comes first. */
  public enum Kind {
    /** The change breaks a rule people set, or does something a rule says a person must look at. */
    BROKEN,
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
    /** Same effects, but a change decides differently whether, or with what, data is deleted. */
    CHANGED_DELETE,
    /** Same effects, but a change decides differently whether, or with what, one of them happens. */
    CHANGED_HOW
  }

  public record Line(Kind kind, String text) {}

  public final List<Line> lines = new ArrayList<>();

  public Feed(Behaviour base, Behaviour head, Differ diff) {
    this(base, head, diff, List.of());
  }

  public Feed(Behaviour base, Behaviour head, Differ diff, List<Rules.Rule> rules) {
    Map<String, String> matched = match(base, head, diff);
    checkRules(rules, base, head, matched);
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
    // Lines about how something already done is done, merged across the ways in they apply to.
    Map<String, Set<String>> waysByText = new TreeMap<>();
    Map<String, Boolean> deletesByText = new HashMap<>();
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
      // Calling out by another means (a generated client instead of raw HTTP) is the same effect reached differently.
      List<Effect> callsGained = gained.stream().filter(x -> x.verb().equals("calls")).toList();
      List<Effect> callsLost = lost.stream().filter(x -> x.verb().equals("calls")).toList();
      if (!callsGained.isEmpty() && !callsLost.isEmpty()) {
        waysByText.computeIfAbsent("calls out a different way, " + targets(callsGained) + " instead of " + targets(callsLost),
            k -> new TreeSet<>()).add(id);
        gained.removeAll(callsGained);
        lost.removeAll(callsLost);
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
    // Each changed method on the way to an effect a way in already had: how it changes that effect,
    // if at all. Lines with the same words are merged across ways in.
    Map<MethodKey, MethodKey> earlier = new HashMap<>();
    for (Differ.Change c : diff.changes) if (c.was() != null) earlier.put(c.method(), c.was());
    for (Map.Entry<MethodKey, Set<String>> e : onPaths.entrySet()) {
      MethodKey m = e.getKey();
      MethodKey was = earlier.getOrDefault(m, m);
      if (!base.analysis.graph.methods.containsKey(was)) was = sameName(base, diff.renames.back(m));
      MethodNode old = was == null ? null : base.analysis.graph.methods.get(was);
      MethodNode now = head.analysis.graph.methods.get(m);
      Map<Effect, Set<String>> byEffect = new TreeMap<>();
      for (String pair : e.getValue()) {
        String[] p = pair.split("\u0000", 2);
        Effect x = after.keySet().stream().filter(f -> f.toString().equals(p[0])).findFirst().orElseThrow();
        byEffect.computeIfAbsent(x, k -> new TreeSet<>()).add(p[1]);
      }
      // The same words for several effects reached through the same ways in become one line.
      Map<List<Object>, List<Effect>> alike = new java.util.LinkedHashMap<>();
      for (Map.Entry<Effect, Set<String>> fx : byEffect.entrySet()) {
        Effect x = fx.getKey();
        String template;
        if (old == null) {
          template = shortName(m) + " is new on the way to: " + EFFECT;
        } else if (now.instructions.size() > SLICE_LIMIT || old.instructions.size() > SLICE_LIMIT) {
          template = EFFECT + ", through " + shortName(m) + ", which changed and is too long to compare";
        } else {
          PathChange.Change c = PathChange.compare(was.owner(), old, toward(base, x), m.owner(), now, toward(head, x));
          if (!c.any()) continue;
          template = describe(c, m);
        }
        alike.computeIfAbsent(List.of(template, fx.getValue()), k -> new ArrayList<>()).add(x);
      }
      for (Map.Entry<List<Object>, List<Effect>> g : alike.entrySet()) {
        List<Effect> xs = g.getValue();
        String text = ((String) g.getKey().get(0)).replace(EFFECT, xs.stream().map(Effect::toString).collect(Collectors.joining(" and ")));
        @SuppressWarnings("unchecked")
        Set<String> ways = (Set<String>) g.getKey().get(1);
        waysByText.computeIfAbsent(text, k -> new TreeSet<>()).addAll(ways);
        deletesByText.put(text, xs.stream().anyMatch(x -> x.verb().equals("deletes")));
      }
    }
    // A new method another line already names ("now using JobCompleter.complete") needs no line of its own.
    waysByText.keySet().removeIf(t -> t.contains(" is new on the way to: ") && waysByText.keySet().stream()
        .anyMatch(o -> o != t && !o.contains(" is new on the way to: ") && o.contains(t.substring(0, t.indexOf(" is new on the way to: ")))));
    for (Map.Entry<String, Set<String>> e : waysByText.entrySet()) {
      Set<String> ways = e.getValue();
      String who = ways.size() == 1 ? ways.iterator().next() : ways.size() + " ways in (" + first(ways, 2) + ")";
      lines.add(new Line(deletesByText.getOrDefault(e.getKey(), false) ? Kind.CHANGED_DELETE : Kind.CHANGED_HOW, who + ": " + e.getKey()));
    }
    lines.sort(Comparator.comparing(Line::kind).thenComparing(Line::text));
  }

  /** The one method of the class with this name before the change, when its signature changed (a new return type). */
  private static MethodKey sameName(Behaviour base, MethodKey m) {
    MethodKey found = null;
    for (MethodKey k : base.analysis.graph.methods.keySet()) {
      if (!k.owner().equals(m.owner()) || !k.name().equals(m.name())) continue;
      if (found != null) return null;
      found = k;
    }
    return found;
  }

  /** Methods longer than this are not sliced; a change in one is reported without detail. */
  static final int SLICE_LIMIT = 4000;

  /** Whether an instruction calls toward the effect: a method that leads to it, or the lambda that does. */
  private static java.util.function.Predicate<AbstractInsnNode> toward(Behaviour b, Effect x) {
    Set<MethodKey> lead = b.leadsTo.getOrDefault(x, Set.of());
    Map<String, List<String>> owners = new HashMap<>();
    for (MethodKey k : lead) owners.computeIfAbsent(k.name() + k.desc(), z -> new ArrayList<>()).add(k.owner());
    CallGraph g = b.analysis.graph;
    return insn -> {
      if (insn instanceof InvokeDynamicInsnNode indy) {
        MethodKey t = PathChange.lambdaTarget(indy);
        return t != null && lead.contains(t);
      }
      if (!(insn instanceof MethodInsnNode mi)) return false;
      List<String> os = owners.get(mi.name + mi.desc);
      if (os == null) return false;
      for (String o : os) {
        if (o.equals(mi.owner) || g.subtypesOf(mi.owner).contains(o) || g.subtypesOf(o).contains(mi.owner)) return true;
      }
      return false;
    };
  }

  /** Stands for the effects a description is about, filled in once the lines for several effects are merged. */
  private static final String EFFECT = "\u0001";

  /** "new check before it writes Pet, on ClinicService.findOwnerById; writes Pet now using …" with where it happens. */
  private static String describe(PathChange.Change c, MethodKey m) {
    String x = EFFECT;
    List<String> parts = new ArrayList<>();
    if (c.reach()) parts.add("now reaches " + x + " here");
    if (c.when()) {
      parts.add(c.newChecks().isEmpty() ? "changed the check before it " + x
          : "new check before it " + x + ", on " + calls(c.newChecks()));
    }
    if (c.what()) {
      if (c.newCalls().isEmpty() && c.goneCalls().isEmpty()) parts.add(x + " with different data");
      else {
        String s = x + (c.newCalls().isEmpty() ? "" : " now using " + calls(c.newCalls()));
        if (!c.goneCalls().isEmpty()) s += (c.newCalls().isEmpty() ? " no longer using " : ", no longer ") + calls(c.goneCalls());
        parts.add(s);
      }
    }
    return String.join("; ", parts) + " (in " + shortName(m) + ")";
  }

  private static String targets(List<Effect> effects) {
    return effects.stream().map(x -> x.target().replaceFirst("^out ", "")).collect(Collectors.joining(" and "));
  }

  private static String calls(Set<String> names) {
    List<String> l = new ArrayList<>(names);
    return l.size() <= 2 ? String.join(" and ", l) : String.join(", ", l.subList(0, 2)) + " and " + (l.size() - 2) + " more";
  }

  /**
   * Never and only rules that the change breaks (true before, false after), then review rules a
   * gained effect falls under. Breaches that were there before the change are not its doing.
   */
  private void checkRules(List<Rules.Rule> rules, Behaviour base, Behaviour head, Map<String, String> matched) {
    if (rules.isEmpty()) return;
    Map<String, String> renamed = new HashMap<>();
    matched.forEach((now, was) -> renamed.put(was, now));
    Set<String> before = new HashSet<>();
    for (String k : Rules.breaches(rules, base)) {
      String[] p = k.split("\u0000", -1);
      before.add(p[0] + "\u0000" + renamed.getOrDefault(p[1], p[1]) + "\u0000" + p[2] + "\u0000" + p[3]);
    }
    for (String k : Rules.breaches(rules, head)) {
      if (before.contains(k)) continue;
      String[] p = k.split("\u0000", -1);
      lines.add(new Line(Kind.BROKEN, "Breaks \"" + p[0] + "\": " + p[1] + " " + p[2] + " " + p[3]));
    }
    for (Rules.Rule r : rules) {
      if (!r.kind().equals("review")) continue;
      head.effects.forEach((id, es) -> {
        if (head.entries.get(id).kind().equals("uncalled")) return;
        Set<Effect> had = matched.containsKey(id) ? base.effects.get(matched.get(id)).keySet() : Set.of();
        for (Effect e : es.keySet()) {
          if (!had.contains(e) && r.effect(e)) lines.add(new Line(Kind.BROKEN, "Needs a person (\"" + r.source() + "\"): " + id + " now " + e));
        }
      });
    }
  }

  /** How many lines break a rule or need a person; a check fails on any. */
  public long broken() {
    return lines.stream().filter(l -> l.kind() == Kind.BROKEN).count();
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

  /** True when what a way in can do changed, not only how it does something it already did. */
  public boolean structural() {
    return lines.stream().anyMatch(l -> l.kind().compareTo(Kind.CHANGED_DELETE) < 0);
  }
}
