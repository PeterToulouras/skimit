package dev.skimit.analyzer;

import dev.skimit.analyzer.Analysis.Verdict;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Compares two analyses and sorts every changed method into a review bucket with its reason. */
public final class Differ {

  public enum Bucket { MUST, SHOULD, SKIP }

  public record Change(
      MethodKey method,
      String kind,
      Bucket bucket,
      Verdict verdict,
      Set<String> domains,
      Set<String> newDomains,
      String reason,
      List<MethodKey> witness,
      String explanation,
      Map<String, List<MethodKey>> proofs,
      MethodKey was) {}

  /**
   * A class that was moved or renamed: gone from {@code from}, found again at {@code to}, with at
   * least half its methods the same once the new names are read as the old ones. Its methods are
   * compared with the methods they were, so a body that did not change is not a change.
   */
  public record Move(String from, String to, int methods, int unchanged, List<Carried> carried) {}

  /** A method that moved with its class and did not change, and the verdict it carries along. */
  public record Carried(MethodKey method, Verdict verdict, Set<String> domains, List<MethodKey> witness) {}

  /** Classes moved or renamed by the change, by their name before it. */
  public final List<Move> moves = new ArrayList<>();

  /** For each change, the lines of the source diff that belong to it; filled by {@link #attach}. */
  public final Map<MethodKey, List<SourceDiff.Line>> lines = new HashMap<>();

  public final List<Change> changes = new ArrayList<>();
  /** Sinks added or removed. Changing what counts as critical is itself always reviewed. */
  public final List<String> policy = new ArrayList<>();
  /** Structure changes: API surface, new dependencies between classes, position moves. */
  public final List<String> shape = new ArrayList<>();
  /** Untouched methods that can now reach a sink because of something they call. */
  public final List<MethodKey> ripple = new ArrayList<>();

  /** An untouched method that was isolated and is now part of a critical flow, and why. */
  public record Reached(MethodKey method, Verdict now, String reason, String explanation, List<MethodKey> witness) {}

  /**
   * Untouched methods that were isolated before the change and are not any more. A reviewer was
   * told their bodies could be skimmed; now they take part in a critical flow and must be read
   * once. They are not changes and are not counted as changed methods.
   */
  public final List<Reached> reached = new ArrayList<>();
  /** True when the changed code was judged by the built-in sinks because none were declared. */
  public final boolean usingDefaultSinks;
  /** True when the code after the change has no sink at all, so nothing in it is critical. */
  public final boolean noSinks;

  public Differ(Analysis base, Analysis head) {
    usingDefaultSinks = head.usingDefaultSinks;
    noSinks = head.sinks.isEmpty();
    Renames renames = new Renames(renames(base, head));
    BiPredicate<MethodKey, MethodKey> sameBody = (b, h) -> base.hashes.get(b).equals(head.hashes.get(h))
        || !renames.map.isEmpty() && base.hashes.get(b).equals(head.graph.bodyHash(h, renames));
    Map<MethodKey, MethodKey> earlier = counterparts(base, head, renames, sameBody);
    for (MethodKey m : head.verdicts.keySet()) {
      MethodKey b = earlier.get(m);
      boolean existed = b != null;
      boolean same = existed && sameBody.test(b, m);
      Verdict hv = head.verdict(m);
      Verdict bv = existed ? base.verdict(b) : Verdict.ISOLATED;
      Set<String> baseDomains = bv == Verdict.ON_PATH ? base.domains(b) : Set.of();
      if (same) {
        if (hv == Verdict.ON_PATH && bv != Verdict.ON_PATH) ripple.add(m);
        if (bv == Verdict.ISOLATED && hv != Verdict.ISOLATED) {
          reached.add(new Reached(m, hv, head.reason(m), head.explain(m), head.witness(m)));
        }
        continue;
      }
      String kind = existed ? "changed" : "added";
      MethodKey was = existed && !renames.back(m).equals(m) ? b : null;
      if (hv == Verdict.ON_PATH) {
        Set<String> fresh = new TreeSet<>(head.domains(m));
        fresh.removeAll(baseDomains);
        add(m, m, was, kind, Bucket.MUST, head, existed ? fresh : Set.of(), null);
      } else if (bv == Verdict.ON_PATH) {
        add(m, b, was, kind, Bucket.MUST, base, Set.of(), "could reach a critical sink before this change and no longer can");
      } else if (hv == Verdict.INFLUENCES || hv == Verdict.UNPROVEN) {
        add(m, m, was, kind, Bucket.SHOULD, head, Set.of(), null);
      } else if (bv == Verdict.INFLUENCES || bv == Verdict.UNPROVEN) {
        add(m, b, was, kind, Bucket.SHOULD, base, Set.of(), "before this change, " + base.reason(b));
      } else {
        add(m, m, was, kind, Bucket.SKIP, head, Set.of(), null);
      }
    }
    Set<MethodKey> matched = new HashSet<>(earlier.values());
    for (MethodKey m : base.verdicts.keySet()) {
      if (matched.contains(m)) continue;
      Verdict bv = base.verdict(m);
      Bucket b = bv == Verdict.ON_PATH ? Bucket.MUST : bv == Verdict.ISOLATED ? Bucket.SKIP : Bucket.SHOULD;
      add(m, m, null, "removed", b, base, Set.of(), bv == Verdict.ISOLATED ? null : "before removal, " + base.reason(m));
    }

    // A sink in a class that moved is the same sink under its new name.
    Set<MethodKey> stillSinks = new HashSet<>();
    for (Map.Entry<MethodKey, Set<String>> e : head.sinks.entrySet()) {
      MethodKey was = renames.back(e.getKey());
      Set<String> before = base.sinks.get(was);
      if (before != null) stillSinks.add(was);
      if (before == null) policy.add("sink added: " + e.getKey().pretty() + " " + e.getValue());
      else if (!before.equals(e.getValue())) policy.add("sink domains changed: " + e.getKey().pretty() + " " + before + " -> " + e.getValue());
    }
    for (Map.Entry<MethodKey, Set<String>> e : base.sinks.entrySet()) {
      if (!stillSinks.contains(e.getKey())) policy.add("sink removed: " + e.getKey().pretty() + " " + e.getValue());
    }

    for (Map.Entry<String, String> e : renames.map.entrySet()) {
      int methods = 0;
      int unchanged = 0;
      List<Carried> carried = new ArrayList<>();
      // Every method counts here, abstract ones too: an interface that moved is all signatures.
      for (MethodKey m : head.hashes.keySet()) {
        if (!CallGraph.topLevel(m.owner()).equals(e.getKey())) continue;
        methods++;
        MethodKey b = earlier.getOrDefault(m, renames.back(m));
        if (!base.hashes.containsKey(b) || !sameBody.test(b, m)) continue;
        unchanged++;
        Verdict v = head.sinks.containsKey(m) ? Verdict.ON_PATH : head.verdict(m);
        Set<String> domains = head.sinks.containsKey(m) ? head.sinks.get(m) : v == Verdict.UNPROVEN ? Set.of() : head.domains(m);
        if (v != Verdict.ISOLATED) carried.add(new Carried(m, v, domains, head.witness(m)));
      }
      moves.add(new Move(e.getValue().replace('/', '.'), e.getKey().replace('/', '.'), methods, unchanged, carried));
    }
    moves.sort(java.util.Comparator.comparing(Move::from));

    shape(base, head, renames);
  }

  /** Rewrites the names that moved classes have after the change into the names they had before. */
  static final class Renames implements UnaryOperator<String> {
    /** Top-level class after the change, in internal form, to the class it was. */
    final Map<String, String> map;
    private final Pattern names;

    Renames(Map<String, String> map) {
      this.map = map;
      // A name is followed by its end, a nested class ($), or punctuation: never by more of a name.
      names = map.isEmpty() ? null : Pattern.compile(map.keySet().stream()
          .sorted(java.util.Comparator.comparingInt(String::length).reversed().thenComparing(x -> x))
          .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|", "(", ")(?![\\w/])")));
    }

    @Override
    public String apply(String text) {
      if (names == null) return text;
      return names.matcher(text).replaceAll(r -> Matcher.quoteReplacement(map.get(r.group(1))));
    }

    MethodKey back(MethodKey m) {
      return names == null ? m : new MethodKey(apply(m.owner()), m.name(), apply(m.desc()));
    }
  }

  /**
   * Classes that moved: each top-level class that exists only after the change, paired with the
   * one that existed only before it whose methods it repeats. A pair needs half the larger class's
   * methods to be the same once the new name is read as the old one, and either two such methods
   * besides constructors and lambdas or one name contained in the other: two small classes with
   * only a default constructor are alike without being the same class.
   */
  static Map<String, String> renames(Analysis base, Analysis head) {
    Map<String, List<MethodKey>> before = families(base);
    Map<String, List<MethodKey>> after = families(head);
    List<String> gone = before.keySet().stream().filter(c -> !after.containsKey(c)).toList();
    List<String> fresh = after.keySet().stream().filter(c -> !before.containsKey(c)).toList();
    record Pair(String to, String from, int matched, int total) {}
    List<Pair> pairs = new ArrayList<>();
    for (String h : fresh) {
      List<MethodKey> hm = after.get(h);
      Set<String> hNames = new HashSet<>();
      for (MethodKey m : hm) hNames.add(m.name());
      for (String b : gone) {
        List<MethodKey> bm = before.get(b);
        int total = Math.max(hm.size(), bm.size());
        long common = bm.stream().filter(m -> hNames.contains(m.name())).count();
        if (common * 2 < total) continue;
        Renames one = new Renames(Map.of(h, b));
        Set<MethodKey> earlier = new HashSet<>(bm);
        int matched = 0;
        int plain = 0;
        for (MethodKey m : hm) {
          MethodKey k = one.back(m);
          if (!earlier.contains(k) || !base.hashes.get(k).equals(head.graph.bodyHash(m, one))) continue;
          matched++;
          if (!m.name().startsWith("<") && CallGraph.enclosing(m) == null) plain++;
        }
        if (matched * 2 < total) continue;
        String hs = simpleName(h);
        String bs = simpleName(b);
        if (plain < 2 && !hs.contains(bs) && !bs.contains(hs)) continue;
        pairs.add(new Pair(h, b, matched, total));
      }
    }
    pairs.sort(java.util.Comparator.comparingDouble((Pair p) -> -(double) p.matched() / p.total())
        .thenComparing(p -> -p.matched()).thenComparing(Pair::to).thenComparing(Pair::from));
    Map<String, String> out = new TreeMap<>();
    Set<String> taken = new HashSet<>();
    for (Pair p : pairs) {
      if (out.containsKey(p.to()) || taken.contains(p.from())) continue;
      out.put(p.to(), p.from());
      taken.add(p.from());
    }
    return out;
  }

  /** Every method, grouped under the top-level class it belongs to. */
  private static Map<String, List<MethodKey>> families(Analysis a) {
    Map<String, List<MethodKey>> out = new TreeMap<>();
    for (MethodKey m : a.hashes.keySet()) out.computeIfAbsent(CallGraph.topLevel(m.owner()), k -> new ArrayList<>()).add(m);
    return out;
  }

  /** Two dotted class names without the packages they share, but one: where it moved within. */
  static String[] shortened(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    int same = 0;
    while (same < x.length - 1 && same < y.length - 1 && x[same].equals(y[same])) same++;
    int from = Math.max(0, same - 1);
    return new String[] {String.join(".", java.util.Arrays.copyOfRange(x, from, x.length)),
        String.join(".", java.util.Arrays.copyOfRange(y, from, y.length))};
  }

  private static String simpleName(String internal) {
    return internal.substring(internal.lastIndexOf('/') + 1);
  }

  /**
   * The method each method after the change was before it: the same method, or for a javac
   * lambda the one with the same enclosing method and type, matched first by an identical body
   * and then in order. Lambda numbers alone say nothing, since adding a lambda renumbers the rest.
   */
  static Map<MethodKey, MethodKey> counterparts(Analysis base, Analysis head, Renames renames,
      BiPredicate<MethodKey, MethodKey> sameBody) {
    Map<MethodKey, MethodKey> out = new HashMap<>();
    Map<String, List<MethodKey>> baseGroups = new TreeMap<>();
    Map<String, List<MethodKey>> headGroups = new TreeMap<>();
    for (MethodKey m : base.verdicts.keySet()) {
      String enc = CallGraph.enclosing(m);
      if (enc != null) baseGroups.computeIfAbsent(m.owner() + "#" + enc + m.desc(), k -> new ArrayList<>()).add(m);
    }
    for (MethodKey m : head.verdicts.keySet()) {
      String enc = CallGraph.enclosing(m);
      MethodKey was = renames.back(m);
      if (enc != null) headGroups.computeIfAbsent(was.owner() + "#" + enc + was.desc(), k -> new ArrayList<>()).add(m);
      else if (base.verdicts.containsKey(was)) out.put(m, was);
    }
    for (Map.Entry<String, List<MethodKey>> e : headGroups.entrySet()) {
      List<MethodKey> after = e.getValue();
      List<MethodKey> earlier = new ArrayList<>(baseGroups.getOrDefault(e.getKey(), List.of()));
      after.sort(java.util.Comparator.comparingInt(CallGraph::lambdaIndex));
      earlier.sort(java.util.Comparator.comparingInt(CallGraph::lambdaIndex));
      List<MethodKey> unmatched = new ArrayList<>();
      for (MethodKey h : after) {
        MethodKey same = null;
        for (MethodKey b : earlier) if (sameBody.test(b, h)) { same = b; break; }
        if (same != null) { out.put(h, same); earlier.remove(same); }
        else unmatched.add(h);
      }
      for (int i = 0; i < unmatched.size() && i < earlier.size(); i++) out.put(unmatched.get(i), earlier.get(i));
    }
    return out;
  }

  /**
   * {@code m} names the change; {@code in} is the same method in {@code from}; {@code was} is the
   * method it was before the change when its class moved or was renamed, else null.
   */
  private void add(MethodKey m, MethodKey in, MethodKey was, String kind, Bucket bucket, Analysis from,
      Set<String> newDomains, String reason) {
    Verdict v = from.verdict(in);
    changes.add(new Change(m, kind, bucket, v, v == Verdict.UNPROVEN ? Set.of() : from.domains(in), newDomains,
        reason != null ? reason : from.reason(in), from.witness(in), from.explain(in), from.proofs(in),
        was));
  }

  /**
   * Finds each change's own lines in a diff of the sources, by the source file and lines its
   * bytecode was compiled from. A lambda's lines lie inside the method that wrote it, so when
   * that method changed too they are shown once, there.
   */
  public void attach(SourceDiff diff, Analysis base, Analysis head) {
    for (Change c : changes) {
      boolean after = !c.kind().equals("removed");
      Program p = (after ? head : base).graph.program;
      int[] range = p.lines.get(c.method());
      if (range == null) continue;
      String owner = c.method().owner();
      SourceDiff.File f = diff.fileOf(owner, p.sourceFiles.get(owner), after);
      if (f == null) continue;
      List<SourceDiff.Line> own = SourceDiff.within(f, range[0], range[1], after);
      if (!own.isEmpty()) lines.put(c.method(), own);
    }
    Set<String> shown = new HashSet<>();
    for (MethodKey m : lines.keySet()) shown.add(m.owner() + "#" + m.name());
    lines.keySet().removeIf(m -> CallGraph.enclosing(m) != null && shown.contains(m.owner() + "#" + CallGraph.enclosing(m)));
  }

  public long count(Bucket b) {
    return changes.stream().filter(c -> c.bucket() == b).count();
  }

  private void shape(Analysis base, Analysis head, Renames renames) {
    Map<String, ClassNode> bc = base.graph.program.classes;
    Map<String, ClassNode> hc = head.graph.program.classes;
    Set<String> carriedOver = new HashSet<>();
    for (ClassNode cn : hc.values()) {
      String was = renames.apply(cn.name);
      ClassNode old = bc.get(was);
      if (old == null) {
        if (exposed(cn.access)) shape.add("new public type " + dotted(cn.name));
        continue;
      }
      if (!was.equals(cn.name)) {
        carriedOver.add(was);
        if (exposed(cn.access) && renames.map.containsKey(cn.name)) {
          String[] n = shortened(old.name.replace('/', '.'), cn.name.replace('/', '.'));
          shape.add("public type moved " + n[0] + " -> " + n[1]);
        }
      }
      // A moved class is compared under its old name, so only real API changes show.
      Set<String> before = api(old, UnaryOperator.identity());
      Set<String> after = api(cn, renames);
      for (String s : after) if (!before.contains(s)) shape.add("new public method " + s);
      for (String s : before) if (!after.contains(s)) shape.add("public method removed " + s);
    }
    for (ClassNode cn : bc.values()) {
      if (!hc.containsKey(cn.name) && !carriedOver.contains(cn.name) && exposed(cn.access)) {
        shape.add("public type removed " + dotted(cn.name));
      }
    }
    for (Map.Entry<String, Set<String>> e : head.graph.classDeps.entrySet()) {
      Set<String> before = base.graph.classDeps.get(renames.apply(e.getKey()));
      if (before == null) continue;
      for (String dep : e.getValue()) {
        if (!before.contains(renames.apply(dep))) {
          shape.add("new dependency " + dotted(e.getKey()) + " -> " + dotted(dep)
              + " (" + head.positions.get(dep) + ", " + head.dependents.get(dep) + " dependents)");
        }
      }
    }
    for (Map.Entry<String, Analysis.Position> e : head.positions.entrySet()) {
      String was = renames.apply(e.getKey());
      Analysis.Position before = base.positions.get(was);
      if (before != null && before != e.getValue()) {
        shape.add("position " + dotted(e.getKey()) + ": " + before + " -> " + e.getValue()
            + " (" + base.dependents.get(was) + " -> " + head.dependents.get(e.getKey()) + " dependents)");
      }
    }
  }

  private static boolean exposed(int access) {
    return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0;
  }

  private static Set<String> api(ClassNode cn, UnaryOperator<String> rename) {
    Set<String> out = new TreeSet<>();
    if (!exposed(cn.access)) return out;
    for (MethodNode mn : cn.methods) {
      if (exposed(mn.access) && !mn.name.equals("<clinit>")) {
        out.add(new MethodKey(rename.apply(cn.name), mn.name, rename.apply(mn.desc)).pretty());
      }
    }
    return out;
  }

  private static String dotted(String internal) {
    return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
  }
}
