package dev.skimit.analyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * How a changed method changes one effect it leads to. Within the method, the calls that lead to
 * the effect are found, and two slices of the method are taken: what feeds those calls (their
 * arguments and whatever was done to those objects first) and what decides whether they run (the
 * checks whose outcome reaches them on one branch and not the other). Comparing the slices before
 * and after the change says whether the change altered what goes into the effect, when it happens,
 * or neither, in which case the change is somewhere the effect does not see.
 */
final class PathChange {

  /**
   * One part of one version of a method (what feeds an effect, what decides whether it happens, or
   * all of it): as comparable text, and as what a reader would name. The calls, objects and fields
   * it uses, its constants in program order, the kinds of logic in it, what each call it makes is
   * given, the types of the fields it uses, and its means: the calls into the language's own
   * library and the fields it reaches collaborators through, which say how it is written.
   */
  record Part(String text, Set<String> names, List<String> values, Ops ops, Map<String, Call> calls, Map<String, Integer> counts,
      Map<String, String> fields, Set<String> means, boolean accessors) {
    static final Part NONE = new Part("", Set.of(), List.of(), Ops.NONE, Map.of(), Map.of(), Map.of(), Set.of(), false);
  }

  /**
   * A call as one version of a method makes it: the types it takes and returns, and for each
   * argument what it is in words, where the bytecode says (a local's name, a constant, a field,
   * another call), and how it is computed, as comparable text.
   */
  record Call(List<String> takes, String returns, List<String> given, List<String> code) {}

  /** The two slices of one version of a method, and the instructions in each. */
  record Slices(Part data, Part checks, boolean reaches, Set<AbstractInsnNode> dataInsns, Set<AbstractInsnNode> checkInsns) {
    static final Slices NONE = new Slices(Part.NONE, Part.NONE, false, Set.of(), Set.of());
  }

  /** The comparisons and the arithmetic among some instructions, counted by kind. */
  record Ops(List<Integer> comparisons, List<Integer> arithmetic) {
    static final Ops NONE = new Ops(List.of(), List.of());

    static Ops of(Set<AbstractInsnNode> insns) {
      List<Integer> cmp = new ArrayList<>();
      List<Integer> calc = new ArrayList<>();
      for (AbstractInsnNode insn : insns) {
        int op = insn.getOpcode();
        if (op >= Opcodes.LCMP && op <= Opcodes.IF_ACMPNE || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) cmp.add(op);
        if (op >= Opcodes.IADD && op <= Opcodes.LXOR) calc.add(op);
      }
      cmp.sort(null);
      calc.sort(null);
      return new Ops(cmp, calc);
    }
  }

  /**
   * What one part of a method gained and lost: the names and values it uses, whether its
   * comparisons or arithmetic changed, how the calls it still makes are given something different
   * ("ClusterService.createCluster now also given operator") and the fields it still uses hold
   * something else, its means, and whether it does the same steps in another order.
   */
  record Delta(Set<String> newNames, Set<String> goneNames, List<String> newValues, List<String> goneValues,
      boolean comparisons, boolean arithmetic, List<String> given, List<String> recounted, Set<String> newMeans, Set<String> goneMeans,
      boolean reordered) {
    static Delta between(Part before, Part after) {
      return between(before, after, true);
    }

    /** As {@link #between(Part, Part)}; {@code mayReorder} is false when the method as a whole does more than reorder its steps. */
    static Delta between(Part before, Part after, boolean mayReorder) {
      Set<String> newNames = added(before.names(), after.names());
      Set<String> goneNames = added(after.names(), before.names());
      // Code compiled for another Java reaches fields another way: the fields it reaches say nothing.
      boolean recompiled = before.accessors() != after.accessors();
      if (recompiled) {
        newNames.removeAll(after.fields().keySet());
        goneNames.removeAll(before.fields().keySet());
      }
      List<String> newValues = without(after.values(), before.values());
      List<String> goneValues = without(before.values(), after.values());
      // What a call is given is said only when the names and values that are new or gone do not say it already.
      Set<String> said = new HashSet<>(newNames);
      said.addAll(goneNames);
      said.addAll(newValues);
      said.addAll(goneValues);
      List<String> given = new ArrayList<>();
      for (Map.Entry<String, Call> e : new TreeMap<>(before.calls()).entrySet()) {
        Call now = after.calls().get(e.getKey());
        // A call made more than once, or more or fewer times than before, cannot be paired with itself.
        boolean once = before.counts().getOrDefault(e.getKey(), 0) == 1 && after.counts().getOrDefault(e.getKey(), 0) == 1;
        String s = now == null || !once ? null : reshaped(e.getValue(), now, said, "given");
        if (s != null) given.add(e.getKey() + " " + s);
      }
      for (Map.Entry<String, String> e : new TreeMap<>(before.fields()).entrySet()) {
        String now = after.fields().get(e.getKey());
        if (now != null && !now.equals(e.getValue())) given.add(e.getKey() + " is now " + now + ", not " + e.getValue());
      }
      // A call made a different number of times: "RabbitTemplate.convertAndSend now called once, not twice".
      List<String> recounted = new ArrayList<>();
      for (Map.Entry<String, Integer> e : new TreeMap<>(before.counts()).entrySet()) {
        Integer now = after.counts().get(e.getKey());
        String verb = e.getKey().startsWith("new ") ? " now made " : " now called ";
        if (now != null && !now.equals(e.getValue())) recounted.add(e.getKey() + verb + times(now) + ", not " + times(e.getValue()));
      }
      return new Delta(newNames, goneNames, newValues, goneValues,
          !before.ops().comparisons().equals(after.ops().comparisons()), !before.ops().arithmetic().equals(after.ops().arithmetic()),
          given, recounted, recompiled ? Set.of() : added(before.means(), after.means()),
          recompiled ? Set.of() : added(after.means(), before.means()),
          mayReorder && !before.text().equals(after.text()) && steps(before.text()).equals(steps(after.text())));
    }

    /** The steps of a part regardless of their order and of how its locals are numbered. */
    static List<String> steps(String text) {
      return text.lines().map(l -> l.replaceAll(" v\\d+$", " v")).sorted().toList();
    }

    private static String times(int n) {
      return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    private static Set<String> added(Set<String> before, Set<String> after) {
      Set<String> out = new TreeSet<>(after);
      out.removeAll(before);
      return out;
    }

    private static List<String> without(List<String> a, List<String> b) {
      List<String> out = new ArrayList<>(a);
      out.removeAll(b);
      return out;
    }
  }

  /**
   * How a call is given something different, in words: "now also given operator", "no longer
   * given a Date", "now given owner, not pet", "now returns Response, not ReturnT". Null when what
   * it is given is computed the same way, or when the bytecode cannot say what changed.
   */
  static String reshaped(Call was, Call now, Set<String> said, String verb) {
    List<String> parts = new ArrayList<>();
    if (was.takes().equals(now.takes())) {
      List<String> to = new ArrayList<>();
      List<String> from = new ArrayList<>();
      for (int i = 0; i < now.takes().size(); i++) {
        String a = was.given().get(i);
        String b = now.given().get(i);
        if (a == null || b == null || a.equals(b) || said.contains(a) || said.contains(b) || was.code().get(i).equals(now.code().get(i))) continue;
        to.add(b);
        from.add(a);
      }
      if (!to.isEmpty()) parts.add("now " + verb + " " + String.join(" and ", to) + ", not " + String.join(" and ", from));
    } else {
      // The arguments both versions have in common, in order, by type and by what they are; the rest
      // were added or taken away.
      List<String> before = new ArrayList<>();
      List<String> after = new ArrayList<>();
      for (int k = 0; k < was.takes().size(); k++) before.add(was.takes().get(k) + " " + was.given().get(k));
      for (int k = 0; k < now.takes().size(); k++) after.add(now.takes().get(k) + " " + now.given().get(k));
      int[][] lcs = new int[before.size() + 1][after.size() + 1];
      for (int i = before.size() - 1; i >= 0; i--) {
        for (int j = after.size() - 1; j >= 0; j--) {
          lcs[i][j] = before.get(i).equals(after.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        }
      }
      List<String> added = new ArrayList<>();
      List<String> gone = new ArrayList<>();
      int i = 0;
      int j = 0;
      while (i < before.size() || j < after.size()) {
        if (i < before.size() && j < after.size() && before.get(i).equals(after.get(j))) {
          i++;
          j++;
        } else if (j < after.size() && (i == before.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
          added.add(said(now, j++));
        } else {
          gone.add(said(was, i++));
        }
      }
      if (added.equals(gone)) {
        // The same thing passed as another type: say the types.
        added.clear();
        gone.clear();
        for (int k = 0; k < now.takes().size(); k++) if (!was.takes().contains(now.takes().get(k))) added.add(article(now.takes().get(k)));
        for (int k = 0; k < was.takes().size(); k++) if (!now.takes().contains(was.takes().get(k))) gone.add(article(was.takes().get(k)));
      }
      added.removeAll(said);
      gone.removeAll(said);
      if (gone.isEmpty() && !added.isEmpty()) parts.add("now also " + verb + " " + String.join(" and ", added));
      else if (added.isEmpty() && !gone.isEmpty()) parts.add("no longer " + verb + " " + String.join(" and ", gone));
      else if (!added.isEmpty()) parts.add("now " + verb + " " + String.join(" and ", added) + " instead of " + String.join(" and ", gone));
    }
    if (!was.returns().equals(now.returns())) parts.add("now returns " + now.returns() + ", not " + was.returns());
    return parts.isEmpty() ? null : String.join(", and ", parts);
  }

  /** How a method's own signature changed: "now also takes a String", "now returns void, not Owner". Null when it did not. */
  static String signature(String descBefore, String descAfter) {
    Call was = ofDesc(descBefore);
    Call now = ofDesc(descAfter);
    return was.takes().equals(now.takes()) && was.returns().equals(now.returns()) ? null : reshaped(was, now, Set.of(), "takes");
  }

  private static Call ofDesc(String desc) {
    List<String> takes = new ArrayList<>();
    List<String> nothing = new ArrayList<>();
    for (Type t : Type.getArgumentTypes(desc)) {
      takes.add(typeName(t));
      nothing.add(null);
    }
    return new Call(takes, typeName(Type.getReturnType(desc)), nothing, nothing);
  }

  /** An argument in words: what it is, or else its type. */
  private static String said(Call c, int i) {
    String g = c.given().get(i);
    return g != null ? g : article(c.takes().get(i));
  }

  private static String article(String type) {
    return ("AEIOUaeiou".indexOf(type.charAt(0)) >= 0 ? "an " : "a ") + type;
  }

  /** What changed for the effect: in the data going into it, in the checks before it, or in whether this method reaches it at all. */
  record Change(boolean what, boolean when, boolean reach, Delta data, Delta check) {
    boolean any() {
      return what || when || reach;
    }
  }

  /** What a method calls toward an effect, and what it calls that never returns (a helper that always throws). */
  record Calls(Predicate<AbstractInsnNode> toward, Predicate<AbstractInsnNode> stops) {}

  static Change compare(String ownerBefore, MethodNode before, Map<Integer, String> localsBefore, Calls callsBefore,
      String ownerAfter, MethodNode after, Map<Integer, String> localsAfter, Calls callsAfter) {
    Slices a = slices(ownerBefore, before, localsBefore, callsBefore);
    Slices b = slices(ownerAfter, after, localsAfter, callsAfter);
    boolean both = a.reaches() && b.reaches();
    // A slice can look reordered while the method around it changed what guards it: say so only
    // when the whole method takes the same steps.
    boolean reordered = before != null && after != null
        && Delta.steps(text(before, Set.copyOf(java.util.Arrays.asList(before.instructions.toArray()))))
            .equals(Delta.steps(text(after, Set.copyOf(java.util.Arrays.asList(after.instructions.toArray())))));
    return new Change(both && !sameSteps(a.data().text(), b.data().text()), both && !sameSteps(a.checks().text(), b.checks().text()),
        a.reaches() != b.reaches(), Delta.between(a.data(), b.data(), reordered), Delta.between(a.checks(), b.checks(), reordered));
  }

  /** Reading a parameter, a field or a constant changes nothing; moving such a step earlier or later is not a change. */
  private static final Pattern PURE = Pattern.compile("(?m)^(?:ALOAD this|[ILFDA]LOAD [^v\\s]\\S*|GET(?:FIELD|STATIC) .*|[ILFDA]CONST_\\S+|BIPUSH .*|SIPUSH .*|LDC .*|CHECKCAST .*)\\n");

  /** Whether two texts of a part do the same steps, allowing the pure ones to sit in another order. */
  static boolean sameSteps(String before, String after) {
    return before.equals(after)
        || Delta.steps(before).equals(Delta.steps(after)) && PURE.matcher(before).replaceAll("").equals(PURE.matcher(after).replaceAll(""));
  }

  /**
   * A whole method as a reader of its result needs it: all of it, and the part its branches test.
   * Logging and the exceptions it throws, with the messages they are built with, are left out.
   */
  record Body(Part checks, Part all) {
    static final Body NONE = new Body(Part.NONE, Part.NONE);

    String text() {
      return all.text();
    }
  }

  private static final Pattern LOGGING = Pattern.compile("(?i).*(/log|logger|logging|/slf4j/|/log4j|PrintStream).*");

  static Body body(String owner, MethodNode mn, Map<Integer, String> locals) {
    if (mn == null || mn.instructions.size() == 0) return Body.NONE;
    Frame<Dep>[] frames;
    Frame<SourceValue>[] sources;
    try {
      frames = new Analyzer<>(new Deps()).analyze(owner, mn);
      sources = new Analyzer<>(new SourceInterpreter()).analyze(owner, mn);
    } catch (AnalyzerException e) {
      return Body.NONE;
    }
    // What is set aside: logging and throwing, and what is done to the objects made only for them
    // (an exception, the builder of a message).
    Set<AbstractInsnNode> roots = new HashSet<>();
    Set<AbstractInsnNode> thrown = new HashSet<>();
    Set<AbstractInsnNode> builders = new HashSet<>();
    for (boolean more = true; more; ) {
      more = false;
      for (AbstractInsnNode insn : mn.instructions) {
        Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
        if (f == null || roots.contains(insn)) continue;
        Set<Object> on = receiver(insn, f);
        boolean throwing = insn.getOpcode() == Opcodes.ATHROW || on.stream().anyMatch(thrown::contains);
        boolean logging = insn instanceof MethodInsnNode m && LOGGING.matcher(m.owner).matches() || on.stream().anyMatch(builders::contains);
        if (!throwing && !logging) continue;
        roots.add(insn);
        more = true;
        for (Dep d : inputs(insn, f)) {
          for (AbstractInsnNode n : d.deps) {
            if (!(n instanceof TypeInsnNode t) || n.getOpcode() != Opcodes.NEW) continue;
            if (throwing) thrown.add(n);
            else if (t.desc.equals("java/lang/StringBuilder") || t.desc.equals("java/lang/StringBuffer")) builders.add(n);
          }
        }
      }
    }
    // Set aside only what nothing else needs: a value that is logged and also returned stays. What
    // the method does is what it decides, returns, stores, and calls for its own sake.
    Set<AbstractInsnNode> used = new HashSet<>();
    for (AbstractInsnNode insn : mn.instructions) {
      Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
      if (f != null) inputs(insn, f).forEach(d -> used.addAll(d.deps));
    }
    Set<AbstractInsnNode> aside = new HashSet<>(roots);
    Set<AbstractInsnNode> needed = new HashSet<>();
    Set<AbstractInsnNode> all = new HashSet<>();
    Set<AbstractInsnNode> checks = new HashSet<>();
    for (AbstractInsnNode insn : mn.instructions) {
      Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
      if (f == null) continue;
      all.add(insn);
      List<Dep> in = inputs(insn, f);
      if (roots.contains(insn)) {
        in.forEach(d -> aside.addAll(d.deps));
      } else if (isBranch(insn) || in.size() > 0 && !(insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode)
          || (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) && !used.contains(insn)) {
        needed.add(insn);
        in.forEach(d -> needed.addAll(d.deps));
      }
      if (isBranch(insn)) {
        checks.add(insn);
        for (Dep d : in) checks.addAll(d.deps);
      }
    }
    aside.removeAll(needed);
    all.removeAll(aside);
    checks.removeAll(aside);
    // The fields it reads say how it gets at things: userService, or userServiceSupplier.
    return new Body(part(owner, mn, locals, checks, frames, sources, false), part(owner, mn, locals, all, frames, sources, true));
  }

  private static boolean isBranch(AbstractInsnNode insn) {
    return insn instanceof JumpInsnNode && insn.getOpcode() != Opcodes.GOTO && insn.getOpcode() != Opcodes.JSR
        || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
  }

  /** The slices of a method; {@code locals} are the names of its locals, by slot, where known. */
  static Slices slices(String owner, MethodNode mn, Map<Integer, String> locals, Calls calls) {
    if (mn == null || mn.instructions.size() == 0) return Slices.NONE;
    Predicate<AbstractInsnNode> toward = calls.toward();
    Map<AbstractInsnNode, Set<AbstractInsnNode>> next = new HashMap<>();
    Analyzer<Dep> analyzer = new Analyzer<>(new Deps()) {
      @Override
      protected void newControlFlowEdge(int from, int to) {
        next.computeIfAbsent(mn.instructions.get(from), k -> new LinkedHashSet<>()).add(mn.instructions.get(to));
      }
    };
    Frame<Dep>[] frames;
    Frame<SourceValue>[] sources;
    try {
      frames = analyzer.analyze(owner, mn);
      sources = new Analyzer<>(new SourceInterpreter()).analyze(owner, mn);
    } catch (AnalyzerException e) {
      Part unknown = new Part("?", Set.of(), List.of(), Ops.NONE, Map.of(), Map.of(), Map.of(), Set.of(), false);
      return new Slices(unknown, unknown, false, Set.of(), Set.of());
    }
    List<AbstractInsnNode> targets = new ArrayList<>();
    for (AbstractInsnNode insn : mn.instructions) {
      if (frames[mn.instructions.indexOf(insn)] != null && toward.test(insn)) targets.add(insn);
    }
    if (targets.isEmpty()) return Slices.NONE;

    // What feeds the calls: their arguments, and anything else done to those objects beforehand
    // (pet.setOwner(owner) before save(pet)).
    Set<AbstractInsnNode> data = new HashSet<>();
    Set<Object> objects = new HashSet<>();
    for (AbstractInsnNode t : targets) {
      for (Dep d : inputs(t, frames[mn.instructions.indexOf(t)])) {
        data.addAll(d.deps);
        objects.addAll(d.origins);
      }
      data.add(t);
    }
    // Only what is done to those objects before the last call toward the effect: what happens to
    // them afterwards (mapping the saved entity to a response) is not what was written.
    int last = mn.instructions.indexOf(targets.get(targets.size() - 1));
    for (AbstractInsnNode insn : mn.instructions) {
      if (!(insn instanceof MethodInsnNode) && insn.getOpcode() != Opcodes.PUTFIELD) continue;
      int at = mn.instructions.indexOf(insn);
      Frame<Dep> f = frames[at];
      if (f == null || targets.contains(insn) || at > last) continue;
      // Reading from the object (a getter, toString) does nothing to it.
      if (insn instanceof MethodInsnNode m && Type.getArgumentTypes(m.desc).length == 0 && Type.getReturnType(m.desc).getSort() != Type.VOID) continue;
      List<Dep> in = inputs(insn, f);
      if (in.stream().anyMatch(d -> d.origins.stream().anyMatch(objects::contains))) {
        data.add(insn);
        in.forEach(d -> data.addAll(d.deps));
      }
    }

    // What decides whether they run: a branch with a target call reachable down one side only.
    Set<AbstractInsnNode> checks = new HashSet<>();
    for (AbstractInsnNode insn : mn.instructions) {
      if (!isBranch(insn)) continue;
      Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
      if (f == null) continue;
      Set<Boolean> outcomes = new HashSet<>();
      for (AbstractInsnNode s : next.getOrDefault(insn, Set.of())) outcomes.add(reaches(s, targets, next, insn, calls.stops()));
      if (outcomes.size() < 2) continue;
      checks.add(insn);
      for (Dep d : inputs(insn, f)) checks.addAll(d.deps);
    }
    return new Slices(part(owner, mn, locals, data, frames, sources, false), part(owner, mn, locals, checks, frames, sources, false),
        true, data, checks);
  }

  /**
   * A part of a method from its instructions. Fields are named when their value is used, as with
   * a flag or a limit, or always when {@code allFields}: the fields a whole method reads say how
   * it gets at things.
   */
  private static Part part(String owner, MethodNode mn, Map<Integer, String> locals, Set<AbstractInsnNode> insns,
      Frame<Dep>[] frames, Frame<SourceValue>[] sources, boolean allFields) {
    Set<String> names = new TreeSet<>();
    Set<String> means = new TreeSet<>();
    Map<String, Call> calls = new TreeMap<>();
    Map<String, Integer> counts = new TreeMap<>();
    Map<String, String> fields = new TreeMap<>();
    Set<String> overloaded = new HashSet<>();
    boolean accessors = false;
    for (AbstractInsnNode insn : mn.instructions) {
      if (!insns.contains(insn)) continue;
      if (insn instanceof MethodInsnNode m && ACCESSOR.matcher(m.name).matches()) {
        // How javac before Java 11 let a nested class reach its outer class's private members.
        accessors = true;
        continue;
      }
      if (insn instanceof MethodInsnNode m) {
        String name = m.name.equals("<init>") ? "new " + Behaviour.simple(m.owner) : Behaviour.simple(m.owner) + "." + m.name;
        if (core(m.owner)) {
          if (!asWritten(m)) means.add(name);
          continue;
        }
        if (!m.name.equals("<init>")) names.add(name);
        counts.merge(name, 1, Integer::sum);
        Call c = call(owner, mn, locals, m, frames, sources);
        Call seen = calls.putIfAbsent(name, c);
        if (seen != null && !(seen.takes().equals(c.takes()) && seen.returns().equals(c.returns()))) overloaded.add(name);
      }
      if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && !core(t.desc)) names.add("new " + Behaviour.simple(t.desc));
      if (insn instanceof FieldInsnNode f && !core(f.owner)) {
        String name = insn.getOpcode() == Opcodes.GETSTATIC || insn.getOpcode() == Opcodes.PUTSTATIC ? Behaviour.simple(f.owner) + "." + f.name
            : field(owner, f);
        fields.put(name, typeName(Type.getType(f.desc)));
        if (insn.getOpcode() == Opcodes.GETSTATIC) names.add(name);
        if (insn.getOpcode() == Opcodes.GETFIELD) (allFields || plainValue(Type.getType(f.desc)) ? names : means).add(name);
      }
    }
    overloaded.forEach(calls::remove);
    return new Part(text(mn, insns), names, values(mn, insns), Ops.of(insns), calls, counts, fields, means, accessors);
  }

  /** A field as a reader would name it: by itself in its own class, with its class elsewhere. */
  private static String field(String owner, FieldInsnNode f) {
    return f.owner.equals(owner) ? f.name : Behaviour.simple(f.owner) + "." + f.name;
  }

  /** A number, a flag, a string, or a boxed one: a value, not a collaborator. */
  private static boolean plainValue(Type t) {
    return t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY
        || t.getInternalName().equals("java/lang/String") || BOXES.contains(t.getInternalName());
  }

  private static final Set<String> BOXES = Set.of("java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
      "java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double");

  /**
   * Library calls that only say how the code is written: building strings, boxing and unboxing,
   * and the null checks javac adds on its own.
   */
  private static boolean asWritten(MethodInsnNode m) {
    if (m.owner.equals("java/lang/StringBuilder") || m.owner.equals("java/lang/StringBuffer") || m.owner.startsWith("java/lang/invoke/")) return true;
    if (m.owner.equals("java/util/Objects") && m.name.equals("requireNonNull")) return true;
    if (!BOXES.contains(m.owner)) return false;
    Type[] args = Type.getArgumentTypes(m.desc);
    return m.name.equals("valueOf") && args.length == 1 && args[0].getSort() != Type.OBJECT || m.name.endsWith("Value") && args.length == 0;
  }

  /** A call as this version makes it: its types, and what each argument is and how it is computed. */
  private static Call call(String owner, MethodNode mn, Map<Integer, String> locals, MethodInsnNode m, Frame<Dep>[] frames,
      Frame<SourceValue>[] sources) {
    Type[] args = Type.getArgumentTypes(m.desc);
    int at = mn.instructions.indexOf(m);
    Frame<Dep> f = frames[at];
    Frame<SourceValue> s = sources[at];
    List<String> takes = new ArrayList<>();
    List<String> given = new ArrayList<>();
    List<String> code = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      takes.add(typeName(args[i]));
      int slot = f.getStackSize() - args.length + i;
      given.add(slot < 0 ? null : said(owner, mn, locals, s.getStack(slot), sources));
      code.add(slot < 0 ? "" : text(mn, f.getStack(slot).deps));
    }
    return new Call(takes, typeName(Type.getReturnType(m.desc)), given, code);
  }

  private static String typeName(Type t) {
    return t.getSort() == Type.ARRAY ? typeName(t.getElementType()) + "[]".repeat(t.getDimensions())
        : t.getSort() == Type.OBJECT ? Behaviour.simple(t.getInternalName()) : t.getClassName();
  }

  /**
   * What a value is, in words, when one instruction made it and the bytecode says: a local's name,
   * a constant, a field, the result of a call, a new object. Null otherwise.
   */
  private static String said(String owner, MethodNode mn, Map<Integer, String> locals, SourceValue v, Frame<SourceValue>[] sources) {
    for (int depth = 0; depth < 8; depth++) {
      if (v == null || v.insns.size() != 1) return null;
      AbstractInsnNode insn = v.insns.iterator().next();
      int op = insn.getOpcode();
      if (op == Opcodes.DUP || op == Opcodes.CHECKCAST) {
        // A copy, or the same object seen as another type: what it was copied from.
        Frame<SourceValue> f = sources[mn.instructions.indexOf(insn)];
        v = f == null || f.getStackSize() == 0 ? null : f.getStack(f.getStackSize() - 1);
        continue;
      }
      if (insn instanceof VarInsnNode var) return locals.get(var.var);
      if (op == Opcodes.ACONST_NULL) return "null";
      String constant = value(insn);
      if (constant != null) return constant;
      if (insn instanceof FieldInsnNode fi && (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC)) {
        return op == Opcodes.GETSTATIC ? Behaviour.simple(fi.owner) + "." + fi.name : field(owner, fi);
      }
      if (insn instanceof MethodInsnNode mi && asWritten(mi) && Type.getArgumentTypes(mi.desc).length <= 1) {
        // Boxed or unboxed, it is still what it was.
        Frame<SourceValue> f = sources[mn.instructions.indexOf(insn)];
        v = f == null || f.getStackSize() == 0 ? null : f.getStack(f.getStackSize() - 1);
        continue;
      }
      if (insn instanceof MethodInsnNode mi) return mi.name.equals("<init>") ? null : Behaviour.simple(mi.owner) + "." + mi.name;
      if (insn instanceof TypeInsnNode t && op == Opcodes.NEW) return "new " + Behaviour.simple(t.desc);
      return null;
    }
    return null;
  }

  /** Whether a target can be reached from here without passing the branch again; a call that never returns ends the path. */
  private static boolean reaches(AbstractInsnNode from, List<AbstractInsnNode> targets,
      Map<AbstractInsnNode, Set<AbstractInsnNode>> next, AbstractInsnNode branch, Predicate<AbstractInsnNode> stops) {
    Set<AbstractInsnNode> seen = new HashSet<>(Set.of(branch));
    List<AbstractInsnNode> work = new ArrayList<>(List.of(from));
    while (!work.isEmpty()) {
      AbstractInsnNode n = work.remove(work.size() - 1);
      if (!seen.add(n)) continue;
      if (targets.contains(n)) return true;
      if (stops.test(n)) continue;
      work.addAll(next.getOrDefault(n, Set.of()));
    }
    return false;
  }

  /** The objects a call is made on: none for a static call or anything else. */
  private static Set<Object> receiver(AbstractInsnNode insn, Frame<Dep> f) {
    if (!(insn instanceof MethodInsnNode m) || m.getOpcode() == Opcodes.INVOKESTATIC) return Set.of();
    int at = f.getStackSize() - Type.getArgumentTypes(m.desc).length - 1;
    return at < 0 ? Set.of() : f.getStack(at).origins;
  }

  /** The values an instruction takes from the stack. */
  private static List<Dep> inputs(AbstractInsnNode insn, Frame<Dep> f) {
    int n = switch (insn.getType()) {
      case AbstractInsnNode.METHOD_INSN -> {
        MethodInsnNode m = (MethodInsnNode) insn;
        yield Type.getArgumentTypes(m.desc).length + (m.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
      }
      case AbstractInsnNode.INVOKE_DYNAMIC_INSN -> Type.getArgumentTypes(((InvokeDynamicInsnNode) insn).desc).length;
      case AbstractInsnNode.JUMP_INSN -> switch (insn.getOpcode()) {
        case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
            Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> 2;
        default -> 1;
      };
      case AbstractInsnNode.TABLESWITCH_INSN, AbstractInsnNode.LOOKUPSWITCH_INSN -> 1;
      case AbstractInsnNode.FIELD_INSN -> insn.getOpcode() == Opcodes.PUTFIELD ? 2 : insn.getOpcode() == Opcodes.PUTSTATIC ? 1 : 0;
      case AbstractInsnNode.INSN -> switch (insn.getOpcode()) {
        case Opcodes.ATHROW, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN,
            Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> 1;
        case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
            Opcodes.CASTORE, Opcodes.SASTORE -> 3;
        default -> 0;
      };
      default -> 0;
    };
    List<Dep> out = new ArrayList<>();
    for (int i = 0; i < n && i < f.getStackSize(); i++) out.add(f.getStack(f.getStackSize() - 1 - i));
    return out;
  }

  private static final Pattern LOCAL = Pattern.compile("(LOAD|STORE|IINC) (\\d+)");
  private static final Pattern PLUMBING = Pattern.compile("(?m)^[ILFDA](?:LOAD|STORE) v\\d+\\n");
  private static final Pattern LAMBDA = Pattern.compile("lambda\\$([\\w$]*?)\\$\\d+");
  private static final Pattern PACKAGE = Pattern.compile("(?:[a-z_][\\w$]*/)+(?=[A-Za-z_$])");
  private static final Pattern INVOKE = Pattern.compile("^INVOKE(VIRTUAL|SPECIAL|INTERFACE)");
  private static final Pattern ACCESSOR = Pattern.compile("access\\$\\d+");
  private static final Pattern HANDLE = Pattern.compile("handle kind 0x[579] : INVOKE(VIRTUAL|SPECIAL|INTERFACE)");

  /** The instructions in program order, with locals renumbered, so that moving code around them does not count. */
  private static String text(MethodNode mn, Set<AbstractInsnNode> keep) {
    StringBuilder sb = new StringBuilder();
    for (AbstractInsnNode insn : mn.instructions) {
      if (!keep.contains(insn)) continue;
      Textifier t = new Textifier();
      insn.accept(new TraceMethodVisitor(t));
      StringBuilder one = new StringBuilder();
      for (Object o : t.getText()) one.append(o);
      // Calling a method of the class directly or through its type is the same call: a private
      // method made visible to a proxy, or a class made an interface, has not changed what is asked.
      sb.append(INVOKE.matcher(one.toString().strip()).replaceAll("INVOKE").replace(" (itf)", "")).append('\n');
    }
    // A parameter is known by its type and its place among the parameters of that type, so that
    // passing one instead of another is seen and adding a parameter of another type changes
    // nothing; other locals are numbered by first use.
    Map<Integer, String> params = new HashMap<>();
    int slot = 0;
    if ((mn.access & Opcodes.ACC_STATIC) == 0) params.put(slot++, "this");
    Map<String, Integer> ofType = new HashMap<>();
    for (Type t : Type.getArgumentTypes(mn.desc)) {
      String type = typeName(t);
      params.put(slot, type + "#" + ofType.merge(type, 1, Integer::sum));
      slot += t.getSize();
    }
    Map<String, Integer> locals = new HashMap<>();
    Matcher m = LOCAL.matcher(sb);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = params.getOrDefault(Integer.parseInt(m.group(2)), null);
      if (name == null) name = "v" + locals.computeIfAbsent(m.group(2), k -> locals.size());
      m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + " " + name));
    }
    m.appendTail(out);
    // A local is a name for a value, not a step: putting a value in one, or passing it through a
    // second, changes nothing about what reaches the effect. Parameters stay, since which one is
    // passed is the point.
    out = new StringBuilder(PLUMBING.matcher(out).replaceAll(""));
    // A class is known by its simple name: a library that moved a class to another package, or a
    // class of the program that moved, has not changed what the method does.
    String plain = HANDLE.matcher(LAMBDA.matcher(out).replaceAll("lambda\\$$1\\$")).replaceAll("handle kind INVOKE");
    return PACKAGE.matcher(plain).replaceAll("");
  }

  /**
   * The constants among the instructions, in program order, as a person would write them: 90,
   * 0.9, "PENDING". Zero, one and minus one are kept, for a reader to drop where they say nothing
   * (see {@link #small}).
   */
  private static List<String> values(MethodNode mn, Set<AbstractInsnNode> keep) {
    Set<String> out = new LinkedHashSet<>();
    for (AbstractInsnNode insn : mn.instructions) {
      if (!keep.contains(insn)) continue;
      String v = value(insn);
      if (v != null) out.add(v);
      // A string joined by invokedynamic keeps its constant pieces in a recipe, where code compiled
      // for an older Java has them as constants of their own: "Order " + id + " failed".
      if (insn instanceof InvokeDynamicInsnNode indy && indy.name.equals("makeConcatWithConstants") && indy.bsmArgs.length > 0
          && indy.bsmArgs[0] instanceof String recipe) {
        for (String piece : recipe.split("[\u0001\u0002]")) if (!piece.isEmpty()) out.add(value(new LdcInsnNode(piece)));
      }
    }
    return new ArrayList<>(out);
  }

  private static String value(AbstractInsnNode insn) {
    int op = insn.getOpcode();
    if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return String.valueOf(op - Opcodes.ICONST_0);
    if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return String.valueOf(op - Opcodes.LCONST_0);
    if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (op - Opcodes.FCONST_0) + ".0";
    if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (op - Opcodes.DCONST_0) + ".0";
    if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return String.valueOf(((org.objectweb.asm.tree.IntInsnNode) insn).operand);
    if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
      Object c = ldc.cst;
      if (c instanceof Number n) return c instanceof Double || c instanceof Float ? String.valueOf(n.doubleValue()) : String.valueOf(n.longValue());
      if (c instanceof String str) return "\"" + (str.length() > 30 ? str.substring(0, 29) + "…" : str) + "\"";
    }
    return null;
  }

  /** Zero, one and minus one: they count loops and stand for true and false more often than they mean a value. */
  static boolean small(String value) {
    return value.equals("0") || value.equals("1") || value.equals("-1") || value.equals("0.0") || value.equals("1.0");
  }

  private static boolean core(String owner) {
    return owner.startsWith("java/") || owner.startsWith("kotlin/") || owner.startsWith("scala/");
  }

  /** A target an invokedynamic hands its arguments to: the lambda or method reference it creates. */
  static MethodKey lambdaTarget(InvokeDynamicInsnNode indy) {
    for (Object a : indy.bsmArgs) if (a instanceof Handle h) return new MethodKey(h.getOwner(), h.getName(), h.getDesc());
    return null;
  }

  // ---- The dependency interpreter --------------------------------------------------------------

  /** A value, the instructions it was computed from (all the way back), and the instructions that made the object it is. */
  static final class Dep implements Value {
    final int size;
    final Set<AbstractInsnNode> deps;
    final Set<Object> origins;

    Dep(int size, Set<AbstractInsnNode> deps, Set<Object> origins) {
      this.size = size;
      this.deps = deps;
      this.origins = origins;
    }

    @Override
    public int getSize() {
      return size;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Dep d && d.size == size && d.deps.equals(deps) && d.origins.equals(origins);
    }

    @Override
    public int hashCode() {
      return deps.hashCode() * 31 + origins.hashCode() + size;
    }
  }

  private static boolean isRef(Type t) {
    return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY;
  }

  /**
   * Whether work can be done on a value of this type before it is handed on: an object, but not
   * a string or a box, which cannot change, so a call made on one earlier is not work on the
   * data going into an effect.
   */
  private static boolean identity(Type t) {
    return isRef(t) && !plainValue(t);
  }

  private static final class Deps extends Interpreter<Dep> {
    private final SourceInterpreter sizes = new SourceInterpreter();
    private int params;

    Deps() {
      super(Opcodes.ASM9);
    }

    private static Set<AbstractInsnNode> with(Set<AbstractInsnNode> a, AbstractInsnNode insn) {
      Set<AbstractInsnNode> s = new HashSet<>(a);
      s.add(insn);
      return s;
    }

    @Override
    public Dep newValue(Type type) {
      if (type == Type.VOID_TYPE) return null;
      return new Dep(type == null ? 1 : type.getSize(), Set.of(), Set.of());
    }

    @Override
    public Dep newParameterValue(boolean isInstanceMethod, int local, Type type) {
      // Each parameter is an object of its own: work done on it is work done on what was passed in.
      // A number or a flag has no identity: passing the same one to two calls joins nothing.
      Object me = "param" + (params++);
      return new Dep(type.getSize(), Set.of(), identity(type) ? Set.of(me) : Set.of());
    }

    @Override
    public Dep newOperation(AbstractInsnNode insn) {
      int size = sizes.newOperation(insn).getSize();
      boolean ref = switch (insn.getOpcode()) {
        case Opcodes.ACONST_NULL, Opcodes.NEW -> true;
        case Opcodes.LDC -> ((org.objectweb.asm.tree.LdcInsnNode) insn).cst instanceof Type;
        case Opcodes.GETSTATIC -> identity(Type.getType(((FieldInsnNode) insn).desc));
        default -> false;
      };
      return new Dep(size, Set.of(insn), ref ? Set.of(insn) : Set.of());
    }

    @Override
    public Dep copyOperation(AbstractInsnNode insn, Dep value) {
      return new Dep(value.size, with(value.deps, insn), value.origins);
    }

    @Override
    public Dep unaryOperation(AbstractInsnNode insn, Dep value) {
      SourceValue s = sizes.unaryOperation(insn, new SourceValue(value.size));
      boolean ref = switch (insn.getOpcode()) {
        case Opcodes.GETFIELD -> identity(Type.getType(((FieldInsnNode) insn).desc));
        case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> true;
        default -> false;
      };
      Set<Object> origins = insn.getOpcode() == Opcodes.CHECKCAST ? value.origins : ref ? Set.of(insn) : Set.of();
      return new Dep(s.getSize(), with(value.deps, insn), origins);
    }

    @Override
    public Dep binaryOperation(AbstractInsnNode insn, Dep a, Dep b) {
      SourceValue s = sizes.binaryOperation(insn, new SourceValue(a.size), new SourceValue(b.size));
      Set<AbstractInsnNode> d = with(a.deps, insn);
      d.addAll(b.deps);
      return new Dep(s.getSize(), d, insn.getOpcode() == Opcodes.AALOAD ? Set.of(insn) : Set.of());
    }

    @Override
    public Dep ternaryOperation(AbstractInsnNode insn, Dep a, Dep b, Dep c) {
      Set<AbstractInsnNode> d = with(a.deps, insn);
      d.addAll(b.deps);
      d.addAll(c.deps);
      return new Dep(1, d, Set.of());
    }

    @Override
    public Dep naryOperation(AbstractInsnNode insn, List<? extends Dep> values) {
      List<SourceValue> in = new ArrayList<>();
      for (Dep v : values) in.add(new SourceValue(v.size));
      SourceValue s = sizes.naryOperation(insn, in);
      Set<AbstractInsnNode> d = new HashSet<>(Set.of(insn));
      for (Dep v : values) d.addAll(v.deps);
      boolean ref = insn instanceof MethodInsnNode m ? identity(Type.getReturnType(m.desc))
          : insn instanceof InvokeDynamicInsnNode i ? identity(Type.getReturnType(i.desc))
          : insn.getOpcode() == Opcodes.MULTIANEWARRAY;
      return new Dep(s.getSize(), d, ref ? Set.of(insn) : Set.of());
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, Dep value, Dep expected) {}

    @Override
    public Dep merge(Dep a, Dep b) {
      if (a.size == b.size && a.deps.containsAll(b.deps) && a.origins.containsAll(b.origins)) return a;
      Set<AbstractInsnNode> d = new HashSet<>(a.deps);
      d.addAll(b.deps);
      Set<Object> o = new HashSet<>(a.origins);
      o.addAll(b.origins);
      return new Dep(Math.min(a.size, b.size), d, o);
    }
  }
}
