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

  /** A line; {@code methods} are the changed methods it is about, when it is about some, so their code can be shown on request. */
  public record Line(Kind kind, String text, List<MethodKey> methods) {
    Line(Kind kind, String text) {
      this(kind, text, List.of());
    }
  }

  public final List<Line> lines = new ArrayList<>();

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
    // The same for changed methods a way in runs that do not themselves lead to the effect.
    Map<MethodKey, Set<String>> offPaths = new TreeMap<>();
    // Lines about how something already done is done: what changed, in which methods, for which
    // ways in and effects.
    Map<String, How> hows = new java.util.LinkedHashMap<>();
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
      if (was == null && base.quiet.contains(id)) {
        // It was there before and caused nothing; what it causes now is all it gained.
        for (Effect x : e.getValue().keySet()) {
          lines.add(new Line(x.verb().equals("deletes") ? Kind.DELETES : Kind.GAINS, id + " now " + x + count(x, before, after)));
        }
        continue;
      }
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
        how(hows, "calls out a different way, " + targets(callsGained) + " instead of " + targets(callsLost), null).ways.add(id);
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
          if (!changed.contains(m)) continue;
          (lead.contains(m) ? onPaths : offPaths).computeIfAbsent(m, k -> new TreeSet<>()).add(x + "\u0000" + id);
        }
      }
    }
    Set<String> stillThere = new HashSet<>(matched.values());
    for (Map.Entry<String, Map<Effect, List<MethodKey>>> e : base.effects.entrySet()) {
      if (stillThere.contains(e.getKey()) || base.entries.get(e.getKey()).kind().equals("uncalled")) continue;
      if (head.quiet.contains(e.getKey())) {
        for (Effect x : e.getValue().keySet()) {
          lines.add(new Line(x.verb().equals("deletes") ? Kind.DELETES : Kind.LOSES, e.getKey() + " no longer " + x + count(x, before, after)));
        }
        continue;
      }
      lines.add(new Line(Kind.REMOVED_WAY_IN, "Removed: " + e.getKey() + " (it " + effects(e.getValue().keySet()) + ")"));
    }
    // Each changed method on the way to an effect a way in already had: how it changes that effect,
    // if at all. Lines with the same words are merged across ways in.
    Map<MethodKey, MethodKey> earlier = new HashMap<>();
    for (Differ.Change c : diff.changes) if (c.was() != null) earlier.put(c.method(), c.was());
    for (Map.Entry<MethodKey, Set<String>> e : onPaths.entrySet()) {
      MethodKey m = e.getKey();
      MethodKey was = before(base, diff, earlier, m);
      MethodNode old = was == null ? null : base.analysis.graph.methods.get(was);
      MethodNode now = head.analysis.graph.methods.get(m);
      for (Map.Entry<Effect, Set<String>> fx : byEffect(e.getValue(), after).entrySet()) {
        Effect x = fx.getKey();
        String template;
        if (old == null) {
          template = NEW_ON_THE_WAY + EFFECT;
        } else if (now.instructions.size() > SLICE_LIMIT || old.instructions.size() > SLICE_LIMIT) {
          template = EFFECT + ", through a method that changed and is too long to compare";
        } else {
          PathChange.Change c = PathChange.compare(was.owner(), old, locals(base, was), toward(base, x), m.owner(), now,
              locals(head, m), toward(head, x));
          if (!c.any()) continue;
          template = describe(c);
        }
        How h = how(hows, template, m);
        h.ways.addAll(fx.getValue());
        h.effects.add(x);
        if (old == null) h.fresh.addAll(aka(head, m));
        // Nothing more to say from the slices, but the method itself takes or returns something else.
        String sig = old == null || !GENERIC.contains(template) ? null : PathChange.signature(was.desc(), m.desc());
        if (sig != null) h.details.put(m, sig);
      }
    }
    // Each changed method off the way whose result goes into an effect, or decides whether it
    // happens, from a method on the way: the price a payment is made with, the permission asked first.
    Map<String, Map<MethodKey, Set<String>>> roles = new HashMap<>();
    for (Map.Entry<MethodKey, Set<String>> e : offPaths.entrySet()) {
      MethodKey m = e.getKey();
      // What changed in it, said once whatever effects it touches: "now checks Order.quantity against 10".
      MethodKey was = before(base, diff, earlier, m);
      PathChange.Body then = was == null ? null : PathChange.body(was.owner(), base.analysis.graph.methods.get(was), locals(base, was));
      PathChange.Body now = PathChange.body(m.owner(), head.analysis.graph.methods.get(m), locals(head, m));
      // Only the packages of the classes it uses changed (javax to jakarta): it does the same.
      if (then != null && then.text().equals(now.text())) continue;
      String said = then == null ? "new" : bodyDetail(then, now);
      // Nothing more to say from its body, but it takes or returns something else.
      String detail = said.isEmpty() && PathChange.signature(was.desc(), m.desc()) != null ? PathChange.signature(was.desc(), m.desc()) : said;
      for (Map.Entry<Effect, Set<String>> fx : byEffect(e.getValue(), after).entrySet()) {
        Effect x = fx.getKey();
        Map<MethodKey, Set<String>> from = roles.computeIfAbsent(m + "\u0000" + x, k -> feeds(head, m, x));
        Map<Set<String>, Set<String>> waysByRole = new HashMap<>();
        for (String id : fx.getValue()) {
          Set<String> r = new TreeSet<>();
          from.forEach((p, rs) -> {
            if (head.runs.get(id).contains(p)) r.addAll(rs);
          });
          if (!r.isEmpty()) waysByRole.computeIfAbsent(r, k -> new TreeSet<>()).add(id);
        }
        waysByRole.forEach((r, ways) -> {
          List<String> parts = new ArrayList<>();
          if (r.contains("check") && r.contains("data")) parts.add(EFFECT + " with a changed check and different data");
          else if (r.contains("check")) parts.add("changed the check before it " + EFFECT);
          else if (r.contains("data")) parts.add(EFFECT + " with different data");
          if (parts.isEmpty()) parts.add(EFFECT + ", through a method too long to compare");
          How h = how(hows, String.join("; ", parts), m);
          h.ways.addAll(ways);
          h.effects.add(x);
          h.details.put(m, detail);
          if (then == null) h.fresh.addAll(aka(head, m));
        });
      }
    }
    lines.sort(Comparator.comparing(Line::kind).thenComparing(Line::text));
    lines.addAll(howLines(hows));
    // Stable: the lines about how keep their order, broadest first, after the lines about what.
    lines.sort(Comparator.comparing(Line::kind));
  }

  /** Pairs of effect and way in, as collected, grouped by effect. */
  private static Map<Effect, Set<String>> byEffect(Set<String> pairs, Map<Effect, Set<String>> after) {
    Map<Effect, Set<String>> out = new TreeMap<>();
    for (String pair : pairs) {
      String[] p = pair.split("\u0000", 2);
      Effect x = after.keySet().stream().filter(f -> f.toString().equals(p[0])).findFirst().orElseThrow();
      out.computeIfAbsent(x, k -> new TreeSet<>()).add(p[1]);
    }
    return out;
  }

  /** A change to how ways in do what they already did: its words, the methods where it happened, for whom and what. */
  private static final class How {
    final String template;
    final List<MethodKey> methods = new ArrayList<>();
    final Set<String> ways = new TreeSet<>();
    final Set<Effect> effects = new TreeSet<>();
    /** For a method off the way, what changed in it, in words. */
    final Map<MethodKey, String> details = new HashMap<>();
    /** When its method is new, the names a line that calls it would use: it needs no line of its own if one does. */
    final Set<String> fresh = new HashSet<>();

    How(String template) {
      this.template = template;
    }
  }

  private static final String NEW_ON_THE_WAY = "is new on the way to: ";

  /** The line for these words in this method, one per pair however many ways in and effects it covers. */
  private static How how(Map<String, How> hows, String template, MethodKey m) {
    return hows.computeIfAbsent(template + "\u0000" + (m == null ? "" : m.id()), k -> {
      How h = new How(template);
      if (m != null) h.methods.add(m);
      return h;
    });
  }

  /** Whether words name a method: "now using PermissionController.normalizeEnv", not "normalizeEnvironment". */
  private static boolean mentions(String words, String name) {
    int i = words.indexOf(name);
    while (i >= 0) {
      int end = i + name.length();
      if ((i == 0 || !Character.isJavaIdentifierPart(words.charAt(i - 1)) && words.charAt(i - 1) != '.')
          && (end == words.length() || !Character.isJavaIdentifierPart(words.charAt(end)))) return true;
      i = words.indexOf(name, i + 1);
    }
    return false;
  }

  /** A method by the names a call to it can have: its class's, a subclass's that inherits it, or a type's it implements. */
  private static Set<String> aka(Behaviour b, MethodKey m) {
    Set<String> out = new TreeSet<>(Set.of(shortName(m)));
    if (m.name().startsWith("<")) return out;
    CallGraph g = b.analysis.graph;
    for (String sub : g.subtypesOf(m.owner())) out.add(Behaviour.simple(sub) + "." + m.name());
    for (String type : g.program.classes.keySet()) {
      if (g.subtypesOf(type).contains(m.owner())) out.add(Behaviour.simple(type) + "." + m.name());
    }
    return out;
  }

  /**
   * The lines about how, ready to show: a new method another line already names needs no line of
   * its own, and methods that changed alike, for the same ways in and effects, are one line, so a
   * refactoring of thirty helpers behind one write is one thing to know. Lines about deletes come
   * first, then those that reach the most ways in.
   */
  private static List<Line> howLines(Map<String, How> hows) {
    List<How> all = new ArrayList<>(hows.values());
    all.removeIf(h -> !h.fresh.isEmpty() && all.stream().anyMatch(o -> o != h && o.fresh.isEmpty()
        && h.fresh.stream().anyMatch(n -> mentions(o.template, n) || o.details.values().stream().anyMatch(d -> mentions(d, n)))));
    Map<List<Object>, How> merged = new java.util.LinkedHashMap<>();
    for (How h : all) {
      How m = merged.computeIfAbsent(List.of(h.template, h.ways, h.effects), k -> {
        How n = new How(h.template);
        n.ways.addAll(h.ways);
        n.effects.addAll(h.effects);
        return n;
      });
      m.methods.addAll(h.methods);
      m.details.putAll(h.details);
    }
    List<How> sorted = new ArrayList<>(merged.values());
    sorted.sort(Comparator.comparing((How h) -> h.effects.stream().noneMatch(x -> x.verb().equals("deletes")))
        .thenComparing(h -> -h.ways.size()).thenComparing(h -> h.template));
    List<Line> out = new ArrayList<>();
    for (How h : sorted) {
      String who = h.ways.size() == 1 ? h.ways.iterator().next() : h.ways.size() + " ways in (" + examples(h.ways) + ")";
      String what = h.template.replace(EFFECT, effectList(h.effects));
      String text = h.template.startsWith(NEW_ON_THE_WAY) ? names(h.methods) + " " + what
          : h.methods.isEmpty() ? what : what + " (in " + where(h) + ")";
      boolean deletes = h.effects.stream().anyMatch(x -> x.verb().equals("deletes"));
      out.add(new Line(deletes ? Kind.CHANGED_DELETE : Kind.CHANGED_HOW, who + ": " + text, List.copyOf(h.methods)));
    }
    return out;
  }

  /** The methods a line is about, with what changed in them when few enough different things did. */
  private static String where(How h) {
    if (h.details.values().stream().allMatch(String::isEmpty)) return names(h.methods);
    // Methods that changed the same way are named together: "A, B and 4 more: userService → userServiceSupplier".
    Map<String, List<MethodKey>> byDetail = new java.util.LinkedHashMap<>();
    h.methods.stream().sorted(Comparator.comparing(Feed::shortName))
        .forEach(m -> byDetail.computeIfAbsent(h.details.getOrDefault(m, ""), k -> new ArrayList<>()).add(m));
    if (byDetail.size() > 3) return names(h.methods);
    return byDetail.entrySet().stream().map(e -> names(e.getValue()) + (e.getKey().isEmpty() ? "" : ": " + e.getKey()))
        .collect(Collectors.joining("; "));
  }

  /**
   * What changed in a method whose result goes into an effect, from what it names and uses before
   * and after: "now checks Order.quantity against 10; now uses 90 and 100", "100 → 1000".
   */
  static String bodyDetail(PathChange.Body before, PathChange.Body after) {
    List<String> parts = new ArrayList<>();
    Set<String> said = new HashSet<>();
    PathChange.Delta checks = PathChange.Delta.between(before.checks(), after.checks());
    if (!checks.newNames().isEmpty()) {
      parts.add("now checks " + list(checks.newNames()) + against(checks.newValues()));
      said.addAll(checks.newNames());
      said.addAll(checks.newValues());
    }
    if (!checks.goneNames().isEmpty()) {
      parts.add("no longer checks " + list(checks.goneNames()));
      said.addAll(checks.goneNames());
      said.addAll(checks.goneValues());
    }
    PathChange.Delta uses = PathChange.Delta.between(before.all(), after.all());
    List<String> newNames = uses.newNames().stream().filter(n -> !said.contains(n)).toList();
    List<String> goneNames = uses.goneNames().stream().filter(n -> !said.contains(n)).toList();
    List<String> newValues = uses.newValues().stream().filter(n -> !said.contains(n)).toList();
    List<String> goneValues = uses.goneValues().stream().filter(n -> !said.contains(n)).toList();
    if (newNames.size() == 1 && goneNames.size() == 1) parts.add(goneNames.get(0) + " → " + newNames.get(0));
    else {
      if (!newNames.isEmpty()) parts.add("now uses " + list(newNames));
      if (!goneNames.isEmpty()) parts.add("no longer uses " + list(goneNames));
    }
    String v = values(newValues, goneValues, "uses ");
    if (!v.isEmpty()) parts.add(v);
    parts.addAll(uses.given());
    // Nothing named or used changed: say what else did, or else what kind of logic.
    if (parts.isEmpty()) parts.add(otherwise(uses));
    parts.remove("");
    if (parts.isEmpty() && checks.comparisons()) parts.add("a comparison changed");
    if (parts.isEmpty() && uses.arithmetic()) parts.add("a calculation changed");
    return String.join("; ", parts);
  }

  /** "100 → 1000", "1 → 2", or "now uses 10, 90 and 100", "no longer uses 5"; a lone 0 or 1 is left out. */
  private static String values(List<String> now, List<String> gone, String verb) {
    if (!now.isEmpty() && now.size() == gone.size() && now.size() <= 3) {
      List<String> pairs = new ArrayList<>();
      for (int i = 0; i < now.size(); i++) pairs.add(gone.get(i) + " → " + now.get(i));
      return String.join(", ", pairs);
    }
    now = plain(now);
    gone = plain(gone);
    List<String> parts = new ArrayList<>();
    if (!now.isEmpty()) parts.add("now " + verb + list(now));
    if (!gone.isEmpty()) parts.add("no longer " + verb + list(gone));
    return String.join(", ", parts);
  }

  private static List<String> plain(List<String> values) {
    return values.stream().filter(v -> !PathChange.small(v)).toList();
  }

  private static String against(List<String> values) {
    return values.isEmpty() ? "" : " against " + list(values);
  }

  /** "A", "A and B", "A, B and 3 more". */
  private static String list(java.util.Collection<String> items) {
    List<String> l = new ArrayList<>(items);
    return l.size() <= 2 ? String.join(" and ", l) : String.join(", ", l.subList(0, 2)) + " and " + (l.size() - 2) + " more";
  }

  /** "writes Order", "deletes App and writes Audit", "deletes App, writes Audit and 5 more". */
  private static String effectList(Set<Effect> effects) {
    List<String> e = effects.stream().map(Effect::toString).toList();
    return e.size() <= 3 ? String.join(" and ", e) : e.get(0) + ", " + e.get(1) + " and " + (e.size() - 2) + " more";
  }

  /** "PriceCalculator.total", "A.x and B.y", "A.x, B.y and 3 more". */
  private static String names(List<MethodKey> ms) {
    List<String> n = ms.stream().map(Feed::shortName).distinct().sorted().toList();
    return n.size() <= 2 ? String.join(" and ", n) : n.get(0) + ", " + n.get(1) + " and " + (n.size() - 2) + " more";
  }

  /**
   * For a method that does not lead to the effect, the methods on the way to it that call it,
   * directly or through others, and what the call is to the effect there: "data" when its result
   * goes into it, "check" when it decides whether it happens.
   */
  private static Map<MethodKey, Set<String>> feeds(Behaviour b, MethodKey m, Effect x) {
    CallGraph g = b.analysis.graph;
    Set<MethodKey> lead = b.leadsTo.getOrDefault(x, Set.of());
    Map<MethodKey, PathChange.Slices> sliced = new HashMap<>();
    Map<MethodKey, Set<String>> out = new HashMap<>();
    java.util.Deque<CallGraph.Node> work = new java.util.ArrayDeque<>(g.nodesOf.getOrDefault(m, List.of()));
    Set<CallGraph.Node> seen = new HashSet<>(work);
    while (!work.isEmpty()) {
      CallGraph.Node q = work.poll();
      for (CallGraph.Node p : g.callersOf(q)) {
        if (!lead.contains(p.method())) {
          if (seen.add(p)) work.add(p);
          continue;
        }
        MethodNode mn = g.methods.get(p.method());
        if (mn == null) continue;
        Set<String> r = out.computeIfAbsent(p.method(), k -> new TreeSet<>());
        // Too long to slice: whether the call matters to the effect is not known, so it is said.
        if (mn.instructions.size() > SLICE_LIMIT) {
          r.add("long");
          continue;
        }
        PathChange.Slices s = sliced.computeIfAbsent(p.method(), k -> PathChange.slices(k.owner(), mn, Map.of(), toward(b, x)));
        if (s.dataInsns().stream().anyMatch(i -> calls(g, i, q.method()))) r.add("data");
        if (s.checkInsns().stream().anyMatch(i -> calls(g, i, q.method()))) r.add("check");
      }
    }
    out.values().removeIf(Set::isEmpty);
    return out;
  }

  /** Whether an instruction calls the method, or creates the lambda that is it. */
  private static boolean calls(CallGraph g, AbstractInsnNode insn, MethodKey m) {
    if (insn instanceof InvokeDynamicInsnNode indy) return m.equals(PathChange.lambdaTarget(indy));
    if (!(insn instanceof MethodInsnNode mi) || !mi.name.equals(m.name()) || !mi.desc.equals(m.desc())) return false;
    return mi.owner.equals(m.owner()) || g.subtypesOf(mi.owner).contains(m.owner()) || g.subtypesOf(m.owner()).contains(mi.owner);
  }

  /**
   * The method a changed method was before the change: itself, what it was called before its
   * class moved, the one method of its class with its name when its signature changed, or the
   * one method with its name in a class of the same name that moved to another package. Null
   * when it is new.
   */
  private static MethodKey before(Behaviour base, Differ diff, Map<MethodKey, MethodKey> earlier, MethodKey m) {
    MethodKey was = earlier.getOrDefault(m, m);
    if (base.analysis.graph.methods.containsKey(was)) return was;
    was = sameName(base, diff.renames.back(m));
    if (was != null) return was;
    MethodKey found = null;
    for (MethodKey k : base.analysis.graph.methods.keySet()) {
      if (!k.name().equals(m.name()) || !Behaviour.simple(k.owner()).equals(Behaviour.simple(m.owner()))) continue;
      if (found != null) return null;
      found = k;
    }
    return found;
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

  private static Map<Integer, String> locals(Behaviour b, MethodKey m) {
    return b.analysis.graph.program.locals.getOrDefault(m, Map.of());
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

  /** The words of a line about how that says only where. */
  private static final Set<String> GENERIC = Set.of("changed the check before it " + EFFECT, EFFECT + " with different data",
      EFFECT + " with a changed check and different data");

  /** "new check before it writes Pet, on ClinicService.findOwnerById; writes Pet now using …". */
  private static String describe(PathChange.Change c) {
    String x = EFFECT;
    List<String> parts = new ArrayList<>();
    if (c.reach()) parts.add("now reaches " + x + " here");
    if (c.when()) {
      PathChange.Delta d = c.check();
      if (!d.goneNames().isEmpty()) parts.add("no longer checks " + list(d.goneNames()) + " before it " + x);
      if (!d.newNames().isEmpty()) parts.add("new check before it " + x + ", on " + list(d.newNames()) + against(d.newValues()));
      if (d.newNames().isEmpty() && d.goneNames().isEmpty()) {
        List<String> said = new ArrayList<>();
        String v = values(d.newValues(), d.goneValues(), "against ");
        if (!v.isEmpty()) said.add(v);
        said.addAll(d.given());
        if (said.isEmpty()) said.add(otherwise(d));
        said.remove("");
        if (said.isEmpty() && d.comparisons()) said.add("a comparison changed");
        parts.add("changed the check before it " + x + (said.isEmpty() ? "" : ": " + String.join("; ", said)));
      }
    }
    if (c.what()) {
      PathChange.Delta d = c.data();
      boolean swap = !d.newValues().isEmpty() && d.newValues().size() == d.goneValues().size() && d.newValues().size() <= 3;
      if (d.newNames().isEmpty() && d.goneNames().isEmpty() && (swap || plain(d.newValues()).isEmpty() && plain(d.goneValues()).isEmpty())) {
        List<String> said = new ArrayList<>();
        String v = values(d.newValues(), d.goneValues(), "");
        if (!v.isEmpty()) said.add(v);
        said.addAll(d.given());
        if (said.isEmpty()) said.add(otherwise(d));
        said.remove("");
        if (said.isEmpty() && d.arithmetic()) said.add("a calculation changed");
        parts.add(x + " with different data" + (said.isEmpty() ? "" : ": " + String.join("; ", said)));
      } else {
        List<String> now = new ArrayList<>(d.newNames());
        now.addAll(plain(d.newValues()));
        List<String> gone = new ArrayList<>(d.goneNames());
        gone.addAll(plain(d.goneValues()));
        String s = x + (now.isEmpty() ? "" : " now using " + list(now));
        if (!gone.isEmpty()) s += (now.isEmpty() ? " no longer using " : ", no longer ") + list(gone);
        parts.add(s);
        parts.addAll(d.given());
      }
    }
    // Both changed and say the same, or nothing more: one phrase, so the effects are named once.
    String checkWords = "changed the check before it " + x;
    String dataWords = x + " with different data";
    for (int check = 0; check < parts.size(); check++) {
      if (!parts.get(check).startsWith(checkWords)) continue;
      String what = parts.get(check).substring(checkWords.length());
      int data = parts.indexOf(dataWords + what);
      if (data < 0) continue;
      parts.set(check, x + " with a changed check and different data" + what);
      parts.remove(data);
      break;
    }
    return String.join("; ", parts);
  }

  /**
   * When nothing named, valued or given changed, what did: a call made a different number of
   * times, the means ("List.size → List.isEmpty", "xxlJobInfoDao → xxlJobInfoMapper"), or the
   * order of the same steps.
   */
  private static String otherwise(PathChange.Delta d) {
    if (!d.recounted().isEmpty()) return String.join("; ", d.recounted());
    if (d.newMeans().size() == 1 && d.goneMeans().size() == 1) return d.goneMeans().iterator().next() + " → " + d.newMeans().iterator().next();
    List<String> parts = new ArrayList<>();
    if (!d.newMeans().isEmpty()) parts.add("now uses " + list(d.newMeans()));
    if (!d.goneMeans().isEmpty()) parts.add("no longer uses " + list(d.goneMeans()));
    if (parts.isEmpty() && d.reordered()) parts.add("the same steps in another order");
    return String.join(", ", parts);
  }

  private static String targets(List<Effect> effects) {
    return effects.stream().map(x -> x.target().replaceFirst("^out ", "")).collect(Collectors.joining(" and "));
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
      lines.add(new Line(Kind.BROKEN, "Breaks a rule (" + p[0] + "): " + p[1] + " " + p[2] + " " + p[3]));
    }
    for (Rules.Rule r : rules) {
      if (!r.kind().equals("review")) continue;
      head.effects.forEach((id, es) -> {
        if (head.entries.get(id).kind().equals("uncalled")) return;
        Set<Effect> had = matched.containsKey(id) ? base.effects.get(matched.get(id)).keySet() : Set.of();
        for (Effect e : es.keySet()) {
          if (!had.contains(e) && r.effect(e)) lines.add(new Line(Kind.BROKEN, "Needs a person (" + r.source() + "): " + id + " now " + e));
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
    // A way in that was there and caused nothing is itself, not a renamed one.
    for (Entry e : head.entries.values()) {
      if (out.containsKey(e.id()) || base.quiet.contains(e.id())) continue;
      String was = baseByMethod.get(diff.renames.back(e.method()));
      if (was != null && taken.add(was)) out.put(e.id(), was);
    }
    // A way in renamed along with its method (/jobinfo/remove to /jobinfo/delete) is still the
    // same way in when it is the only one of its kind that does exactly the same things.
    Map<String, List<String>> leftBefore = new HashMap<>();
    for (Entry e : base.entries.values()) {
      if (!taken.contains(e.id()) && !head.quiet.contains(e.id())) {
        leftBefore.computeIfAbsent(signature(e, base), k -> new ArrayList<>()).add(e.id());
      }
    }
    Map<String, List<String>> leftAfter = new HashMap<>();
    for (Entry e : head.entries.values()) {
      if (!out.containsKey(e.id()) && !base.quiet.contains(e.id())) {
        leftAfter.computeIfAbsent(signature(e, head), k -> new ArrayList<>()).add(e.id());
      }
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

  /** One or two of the ways in, as many as read in a glance. */
  private static String examples(Set<String> ways) {
    List<String> two = ways.stream().limit(2).toList();
    return first(ways, two.size() == 2 && two.get(0).length() + two.get(1).length() <= 60 ? 2 : 1);
  }

  private static String first(Set<String> items, int n) {
    return items.stream().limit(n).collect(Collectors.joining(", ")) + (items.size() > n ? ", …" : "");
  }

  private static String shortName(MethodKey m) {
    String p = m.pretty();
    p = (p.indexOf('(') < 0 ? p : p.substring(0, p.indexOf('('))).replaceAll("\\$(?=[A-Za-z])", ".");
    // A constructor reads as what it is: new Order.
    return p.endsWith(".<init>") ? "new " + p.substring(0, p.length() - ".<init>".length()) : p;
  }

  public boolean isEmpty() {
    return lines.isEmpty();
  }

  /** Lines about what a way in can do, as opposed to how it does something it already did; broken rules aside. */
  public long what() {
    return lines.stream().filter(l -> l.kind() != Kind.BROKEN && l.kind().compareTo(Kind.CHANGED_DELETE) < 0).count();
  }
}
