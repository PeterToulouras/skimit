package dev.skimit.analyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which methods a change touched: each method after the change matched with the one it was
 * (through renamed lambdas and moved classes), and compared by its bytecode. Comments, formatting,
 * renamed locals and moved code are not changes.
 */
public final class Differ {

  /** A method the change added, removed or changed; {@code was} is what it was called before, when its class moved. */
  public record Change(MethodKey method, String kind, MethodKey was) {}

  public final List<Change> changes = new ArrayList<>();
  /** Reads the names of moved classes and renamed methods after the change as the names they had before. */
  final Renames renames;
  /** Each method renamed with its body kept, after the change, to what it was called before. */
  public final Map<MethodKey, MethodKey> methodRenames = new TreeMap<>();
  /** For each change, the lines of the source diff that belong to it; filled by {@link #attach}. */
  public final Map<MethodKey, List<SourceDiff.Line>> lines = new HashMap<>();

  public Differ(Analysis base, Analysis head) {
    Renames classes = new Renames(renames(base, head), Map.of());
    BiPredicate<MethodKey, MethodKey> alike = (b, h) -> base.hashes.get(b).equals(head.hashes.get(h))
        || classes.any() && base.hashes.get(b).equals(head.graph.bodyHash(h, classes));
    Map<MethodKey, MethodKey> earlier = counterparts(base, head, classes, alike);
    methodRenames.putAll(renamedMethods(base, head, classes, earlier));
    earlier.putAll(methodRenames);
    // A caller whose only edit is a method's new name is read with the old one, so it did not change.
    renames = new Renames(classes.map, methodRenames);
    BiPredicate<MethodKey, MethodKey> sameBody = (b, h) -> base.hashes.get(b).equals(head.hashes.get(h))
        || renames.any() && base.hashes.get(b).equals(head.graph.bodyHash(h, renames));
    for (MethodKey m : head.methods()) {
      MethodKey b = earlier.get(m);
      if (b != null && sameBody.test(b, m)) continue;
      changes.add(new Change(m, b != null ? "changed" : "added", b != null && !renames.back(m).equals(m) ? b : null));
    }
    Set<MethodKey> matched = new HashSet<>(earlier.values());
    for (MethodKey m : base.methods()) if (!matched.contains(m)) changes.add(new Change(m, "removed", null));
  }

  /**
   * Rewrites the names that moved classes and renamed methods have after the change into the
   * names they had before. In a method's text a call reads {@code owner.name (desc)}, and a
   * method handle {@code owner.name(desc)}; a class name is followed by its end, a nested class
   * ($) or punctuation, never by more of a name.
   */
  static final class Renames implements UnaryOperator<String> {
    /** Top-level class after the change, in internal form, to the class it was. */
    final Map<String, String> map;
    private final Map<MethodKey, MethodKey> methods;
    private final Pattern names;
    private final Pattern calls;

    Renames(Map<String, String> map, Map<MethodKey, MethodKey> methods) {
      this.map = map;
      this.methods = methods;
      names = map.isEmpty() ? null : Pattern.compile(map.keySet().stream()
          .sorted(java.util.Comparator.comparingInt(String::length).reversed().thenComparing(x -> x))
          .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|", "(", ")(?![\\w/])")));
      calls = methods.isEmpty() ? null : Pattern.compile(methods.keySet().stream()
          .map(m -> Pattern.quote(m.owner() + "." + m.name()) + "(?= ?" + Pattern.quote(m.desc()) + ")")
          .collect(java.util.stream.Collectors.joining("|", "(", ")")));
    }

    boolean any() {
      return names != null || calls != null;
    }

    @Override
    public String apply(String text) {
      String s = text;
      if (calls != null) {
        s = calls.matcher(text).replaceAll(r -> {
          String[] p = r.group(1).split("\\.(?=[^.]*$)", 2);
          MethodKey was = call(p[0], p[1], text, r.end());
          return Matcher.quoteReplacement(was == null ? r.group(1) : was.owner() + "." + was.name());
        });
      }
      if (names == null) return s;
      return names.matcher(s).replaceAll(r -> Matcher.quoteReplacement(map.get(r.group(1))));
    }

    /** The renamed method a matched call token stands for: the one of that owner and name whose descriptor follows. */
    private MethodKey call(String owner, String name, String text, int end) {
      for (Map.Entry<MethodKey, MethodKey> e : methods.entrySet()) {
        MethodKey k = e.getKey();
        if (k.owner().equals(owner) && k.name().equals(name) && text.startsWith(k.desc(), text.charAt(end) == ' ' ? end + 1 : end)) return e.getValue();
      }
      return null;
    }

    MethodKey back(MethodKey m) {
      MethodKey renamed = methods.get(m);
      if (renamed != null) return renamed;
      return names == null ? m : new MethodKey(apply(m.owner()), m.name(), apply(m.desc()));
    }
  }

  /**
   * Methods renamed and nothing more: a method after the change that has no counterpart, paired
   * with the one method of its class that existed only before it and has the same descriptor and
   * the same body. Two methods that happen to share a body are left alone. A supertype that
   * declares the new name where it declared the old one (an interface renamed along with its
   * implementation) is renamed with it.
   */
  static Map<MethodKey, MethodKey> renamedMethods(Analysis base, Analysis head, Renames classes, Map<MethodKey, MethodKey> earlier) {
    Set<MethodKey> taken = new HashSet<>(earlier.values());
    Map<String, List<MethodKey>> gone = new HashMap<>();
    Map<String, List<MethodKey>> fresh = new HashMap<>();
    for (MethodKey m : base.methods()) {
      if (!taken.contains(m) && plain(m)) gone.computeIfAbsent(m.owner() + "|" + m.desc() + "|" + base.hashes.get(m), k -> new ArrayList<>()).add(m);
    }
    for (MethodKey m : head.methods()) {
      if (earlier.containsKey(m) || !plain(m)) continue;
      MethodKey was = classes.back(m);
      fresh.computeIfAbsent(was.owner() + "|" + was.desc() + "|" + head.graph.bodyHash(m, classes), k -> new ArrayList<>()).add(m);
    }
    Map<MethodKey, MethodKey> out = new TreeMap<>();
    for (Map.Entry<String, List<MethodKey>> e : fresh.entrySet()) {
      List<MethodKey> b = gone.get(e.getKey());
      if (b != null && b.size() == 1 && e.getValue().size() == 1 && !b.get(0).name().equals(e.getValue().get(0).name())) {
        out.put(e.getValue().get(0), b.get(0));
      }
    }
    for (Map.Entry<MethodKey, MethodKey> e : new ArrayList<>(out.entrySet())) {
      MethodKey h = e.getKey();
      MethodKey b = e.getValue();
      for (String t : head.graph.supertypesOf(h.owner())) {
        MethodKey declared = new MethodKey(t, h.name(), h.desc());
        MethodKey was = new MethodKey(classes.apply(t), b.name(), b.desc());
        if (head.graph.methods.containsKey(declared) && base.graph.methods.containsKey(was)) out.put(declared, was);
      }
    }
    return out;
  }

  /** An ordinary method: not a constructor, an initializer or a lambda body. */
  private static boolean plain(MethodKey m) {
    return !m.name().startsWith("<") && CallGraph.enclosing(m) == null;
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
        Renames one = new Renames(Map.of(h, b), Map.of());
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
    for (MethodKey m : base.methods()) {
      String enc = CallGraph.enclosing(m);
      if (enc != null) baseGroups.computeIfAbsent(m.owner() + "#" + enc + m.desc(), k -> new ArrayList<>()).add(m);
    }
    for (MethodKey m : head.methods()) {
      String enc = CallGraph.enclosing(m);
      MethodKey was = renames.back(m);
      if (enc != null) headGroups.computeIfAbsent(was.owner() + "#" + enc + was.desc(), k -> new ArrayList<>()).add(m);
      else if (base.methods().contains(was)) out.put(m, was);
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
}
