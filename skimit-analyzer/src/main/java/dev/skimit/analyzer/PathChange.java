package dev.skimit.analyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
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
   * The two slices of one version of a method: as comparable text, and as what they name (the calls
   * they make) and the values they use (the constants in them, in program order).
   */
  record Slices(String data, String checks, Set<String> dataNames, Set<String> checkNames, List<String> dataValues,
      List<String> checkValues, Ops dataOps, Ops checkOps, boolean reaches, Set<AbstractInsnNode> dataInsns,
      Set<AbstractInsnNode> checkInsns) {
    static final Slices NONE = new Slices("", "", Set.of(), Set.of(), List.of(), List.of(), Ops.NONE, Ops.NONE, false, Set.of(),
        Set.of());
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

  /** What one part of a method gained and lost: the calls it names and the values it uses. */
  record Delta(Set<String> newNames, Set<String> goneNames, List<String> newValues, List<String> goneValues,
      boolean comparisons, boolean arithmetic) {
    static Delta between(Set<String> namesBefore, Set<String> namesAfter, List<String> valuesBefore, List<String> valuesAfter,
        Ops opsBefore, Ops opsAfter) {
      Set<String> newNames = new TreeSet<>(namesAfter);
      newNames.removeAll(namesBefore);
      Set<String> goneNames = new TreeSet<>(namesBefore);
      goneNames.removeAll(namesAfter);
      List<String> newValues = new ArrayList<>(valuesAfter);
      newValues.removeAll(valuesBefore);
      List<String> goneValues = new ArrayList<>(valuesBefore);
      goneValues.removeAll(valuesAfter);
      return new Delta(newNames, goneNames, newValues, goneValues, !opsBefore.comparisons().equals(opsAfter.comparisons()),
          !opsBefore.arithmetic().equals(opsAfter.arithmetic()));
    }
  }

  /** What changed for the effect: in the data going into it, in the checks before it, or in whether this method reaches it at all. */
  record Change(boolean what, boolean when, boolean reach, Delta data, Delta check) {
    boolean any() {
      return what || when || reach;
    }
  }

  static Change compare(String ownerBefore, MethodNode before, Predicate<AbstractInsnNode> towardBefore,
      String ownerAfter, MethodNode after, Predicate<AbstractInsnNode> towardAfter) {
    Slices a = slices(ownerBefore, before, towardBefore);
    Slices b = slices(ownerAfter, after, towardAfter);
    return new Change(a.reaches() && b.reaches() && !a.data().equals(b.data()),
        a.reaches() && b.reaches() && !a.checks().equals(b.checks()), a.reaches() != b.reaches(),
        Delta.between(a.dataNames(), b.dataNames(), a.dataValues(), b.dataValues(), a.dataOps(), b.dataOps()),
        Delta.between(a.checkNames(), b.checkNames(), a.checkValues(), b.checkValues(), a.checkOps(), b.checkOps()));
  }

  /**
   * A whole method as a reader of its result needs it: what it names and the values it uses, and
   * which of them its branches test. Logging and the exceptions it throws are left out.
   */
  record Body(String text, Set<String> checkNames, List<String> checkValues, Ops checkOps, Set<String> names, List<String> values,
      Ops ops) {
    static final Body NONE = new Body("", Set.of(), List.of(), Ops.NONE, Set.of(), List.of(), Ops.NONE);
  }

  private static final Pattern LOGGING = Pattern.compile("(?i).*(/log|logger|logging|/slf4j/|/log4j|PrintStream).*");

  static Body body(String owner, MethodNode mn) {
    if (mn == null || mn.instructions.size() == 0) return Body.NONE;
    Frame<Dep>[] frames;
    try {
      frames = new Analyzer<>(new Deps()).analyze(owner, mn);
    } catch (AnalyzerException e) {
      return Body.NONE;
    }
    Set<AbstractInsnNode> all = new HashSet<>();
    Set<AbstractInsnNode> checks = new HashSet<>();
    Set<AbstractInsnNode> aside = new HashSet<>();
    for (AbstractInsnNode insn : mn.instructions) {
      Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
      if (f == null) continue;
      all.add(insn);
      boolean logs = insn instanceof MethodInsnNode m && LOGGING.matcher(m.owner).matches();
      if (logs || insn.getOpcode() == Opcodes.ATHROW) {
        aside.add(insn);
        for (Dep d : inputs(insn, f)) aside.addAll(d.deps);
      }
      if (isBranch(insn)) {
        checks.add(insn);
        for (Dep d : inputs(insn, f)) checks.addAll(d.deps);
      }
    }
    all.removeAll(aside);
    checks.removeAll(aside);
    Set<String> named = new TreeSet<>(names(all));
    // The fields it reads say how it gets at things: userService, or userServiceSupplier.
    for (AbstractInsnNode insn : all) {
      if (insn instanceof FieldInsnNode f && insn.getOpcode() == Opcodes.GETFIELD && !core(f.owner)) {
        named.add(f.owner.equals(owner) ? f.name : Behaviour.simple(f.owner) + "." + f.name);
      }
    }
    return new Body(text(mn, all), names(checks), values(mn, checks), Ops.of(checks), named, values(mn, all), Ops.of(all));
  }

  private static boolean isBranch(AbstractInsnNode insn) {
    return insn instanceof JumpInsnNode && insn.getOpcode() != Opcodes.GOTO && insn.getOpcode() != Opcodes.JSR
        || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
  }

  static Slices slices(String owner, MethodNode mn, Predicate<AbstractInsnNode> toward) {
    if (mn == null || mn.instructions.size() == 0) return Slices.NONE;
    Map<AbstractInsnNode, Set<AbstractInsnNode>> next = new HashMap<>();
    Analyzer<Dep> analyzer = new Analyzer<>(new Deps()) {
      @Override
      protected void newControlFlowEdge(int from, int to) {
        next.computeIfAbsent(mn.instructions.get(from), k -> new LinkedHashSet<>()).add(mn.instructions.get(to));
      }
    };
    Frame<Dep>[] frames;
    try {
      frames = analyzer.analyze(owner, mn);
    } catch (AnalyzerException e) {
      return new Slices("?", "?", Set.of(), Set.of(), List.of(), List.of(), Ops.NONE, Ops.NONE, false, Set.of(), Set.of());
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
    for (AbstractInsnNode insn : mn.instructions) {
      if (!(insn instanceof MethodInsnNode) && insn.getOpcode() != Opcodes.PUTFIELD) continue;
      Frame<Dep> f = frames[mn.instructions.indexOf(insn)];
      if (f == null || targets.contains(insn)) continue;
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
      for (AbstractInsnNode s : next.getOrDefault(insn, Set.of())) outcomes.add(reaches(s, targets, next, insn));
      if (outcomes.size() < 2) continue;
      checks.add(insn);
      for (Dep d : inputs(insn, f)) checks.addAll(d.deps);
    }
    return new Slices(text(mn, data), text(mn, checks), names(data), names(checks), values(mn, data), values(mn, checks),
        Ops.of(data), Ops.of(checks), true, data, checks);
  }

  private static boolean reaches(AbstractInsnNode from, List<AbstractInsnNode> targets,
      Map<AbstractInsnNode, Set<AbstractInsnNode>> next, AbstractInsnNode branch) {
    Set<AbstractInsnNode> seen = new HashSet<>(Set.of(branch));
    List<AbstractInsnNode> work = new ArrayList<>(List.of(from));
    while (!work.isEmpty()) {
      AbstractInsnNode n = work.remove(work.size() - 1);
      if (!seen.add(n)) continue;
      if (targets.contains(n)) return true;
      work.addAll(next.getOrDefault(n, Set.of()));
    }
    return false;
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
      default -> 0;
    };
    List<Dep> out = new ArrayList<>();
    for (int i = 0; i < n && i < f.getStackSize(); i++) out.add(f.getStack(f.getStackSize() - 1 - i));
    return out;
  }

  private static final Pattern LOCAL = Pattern.compile("(LOAD|STORE|IINC) (\\d+)");
  private static final Pattern LAMBDA = Pattern.compile("lambda\\$([\\w$]*?)\\$\\d+");
  private static final Pattern PACKAGE = Pattern.compile("(?:[a-z_][\\w$]*/)+(?=[A-Za-z_$])");

  /** The instructions in program order, with locals renumbered by first use, so that moving code around them does not count. */
  private static String text(MethodNode mn, Set<AbstractInsnNode> keep) {
    StringBuilder sb = new StringBuilder();
    for (AbstractInsnNode insn : mn.instructions) {
      if (!keep.contains(insn)) continue;
      Textifier t = new Textifier();
      insn.accept(new TraceMethodVisitor(t));
      StringBuilder one = new StringBuilder();
      for (Object o : t.getText()) one.append(o);
      sb.append(one.toString().strip()).append('\n');
    }
    Map<String, Integer> locals = new HashMap<>();
    Matcher m = LOCAL.matcher(sb);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      int n = locals.computeIfAbsent(m.group(2), k -> locals.size());
      m.appendReplacement(out, m.group(1) + " v" + n);
    }
    m.appendTail(out);
    // A class is known by its simple name: a library that moved a class to another package, or a
    // class of the program that moved, has not changed what the method does.
    return PACKAGE.matcher(LAMBDA.matcher(out).replaceAll("lambda\\$$1\\$")).replaceAll("");
  }

  /**
   * The methods a slice calls or builds, as people would name them: ClinicService.findOwnerById,
   * new Owner. The language's own library is left out (boxing, string building, collection
   * helpers): it says how the code is written, not what it asks of the system.
   */
  private static Set<String> names(Set<AbstractInsnNode> insns) {
    Set<String> out = new TreeSet<>();
    for (AbstractInsnNode insn : insns) {
      if (insn instanceof MethodInsnNode m && !m.name.equals("<init>") && !core(m.owner)) out.add(Behaviour.simple(m.owner) + "." + m.name);
      if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && !core(t.desc)) out.add("new " + Behaviour.simple(t.desc));
      if (insn instanceof FieldInsnNode f && insn.getOpcode() == Opcodes.GETSTATIC && !core(f.owner)) out.add(Behaviour.simple(f.owner) + "." + f.name);
    }
    return out;
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
      return new Dep(type.getSize(), Set.of(), isRef(type) ? Set.of(me) : Set.of());
    }

    @Override
    public Dep newOperation(AbstractInsnNode insn) {
      int size = sizes.newOperation(insn).getSize();
      boolean ref = switch (insn.getOpcode()) {
        case Opcodes.ACONST_NULL, Opcodes.NEW -> true;
        case Opcodes.LDC -> !(((org.objectweb.asm.tree.LdcInsnNode) insn).cst instanceof Number);
        case Opcodes.GETSTATIC -> isRef(Type.getType(((FieldInsnNode) insn).desc));
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
        case Opcodes.GETFIELD -> isRef(Type.getType(((FieldInsnNode) insn).desc));
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
      boolean ref = insn instanceof MethodInsnNode m ? isRef(Type.getReturnType(m.desc))
          : insn instanceof InvokeDynamicInsnNode i ? isRef(Type.getReturnType(i.desc))
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
