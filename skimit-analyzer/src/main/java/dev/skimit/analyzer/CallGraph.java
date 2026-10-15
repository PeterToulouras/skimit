package dev.skimit.analyzer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Whole-program call graph over the application classes.
 *
 * <p>An instance method is analysed once per concrete class it can run on. A call on {@code this}
 * then binds to that one class, so a base-class method that calls an abstract hook reaches only
 * the hook of the subclass it is running in, not every subclass's hook. A call on any other
 * object is resolved by class hierarchy: it may land on any concrete subtype of the static type.
 * That is what makes interface-typed dependency injection visible without knowing the wiring.
 *
 * <p>Constructing an object whose class extends a library type adds edges to its overridable
 * methods, because the library may call back into them.
 */
public final class CallGraph {

  /**
   * A method as it runs on one concrete receiver class. The receiver is null for static methods,
   * for library and abstract methods, and for classes with no concrete form in the program.
   */
  public record Node(MethodKey method, String receiver) implements Comparable<Node> {
    @Override
    public int compareTo(Node o) {
      int c = method.compareTo(o.method);
      if (c != 0) return c;
      return (receiver == null ? "" : receiver).compareTo(o.receiver == null ? "" : o.receiver);
    }

    /** Short human form; names the receiver when the method is inherited. */
    public String pretty() {
      if (receiver == null || receiver.equals(method.owner())) return method.pretty();
      return method.pretty() + " on " + receiver.substring(receiver.lastIndexOf('/') + 1);
    }
  }

  public final Program program;
  /** Application methods as declared, including abstract ones. */
  public final Map<MethodKey, MethodNode> methods = new TreeMap<>();
  /** Every declared method with a body, to the receivers it is analysed on. */
  public final Map<MethodKey, List<Node>> nodesOf = new TreeMap<>();
  public final Map<Node, Set<Node>> callees = new HashMap<>();
  public final Map<Node, Set<Node>> callers = new HashMap<>();
  /** Opaque calls made directly by each node. */
  public final Map<Node, Set<String>> opaqueCalls = new TreeMap<>();
  /** Every method that appears as a call target, application or library. */
  public final Set<MethodKey> targets = new HashSet<>();
  /** Top-level class to the top-level application classes it references. */
  public final Map<String, Set<String>> classDeps = new TreeMap<>();
  /**
   * Application classes whose instances each method handles as data: types in its own signature,
   * in the signatures of what it calls, in field accesses, casts and allocations. A class it
   * only calls methods on is a collaborator, not data, and is left out.
   */
  public final Map<MethodKey, Set<String>> dataTypes = new HashMap<>();
  /** Per node, one set of possible targets for each call instruction. */
  public final Map<Node, List<Set<Node>>> sites = new HashMap<>();
  /** Lambda or method-reference target to the nodes that create it, for application interfaces. */
  public final Map<Node, Set<Node>> creators = new HashMap<>();
  /** Methods a framework calls alongside the other methods of the same object, to the annotation. */
  public final Map<MethodKey, String> classParticipants = new TreeMap<>();
  /** Methods a framework may call inside any flow, to the annotation. */
  public final Map<MethodKey, String> globalParticipants = new TreeMap<>();
  /** Fields each node reads, as {@code owner#name@receiver} with {@code *} for an unknown receiver. */
  public final Map<Node, Set<String>> fieldReads = new HashMap<>();
  /** Application classes that application code constructs somewhere. */
  public final Set<String> instantiated = new HashSet<>();

  private final Map<String, Set<String>> subtypes = new HashMap<>();
  private final Map<String, List<String>> receiverCache = new HashMap<>();
  private final Map<String, Shape> shapes = new HashMap<>();
  private final Policy policy;
  private Set<String> currentRefs;
  private final Map<String, List<Node>> writersExact = new HashMap<>();
  private final Map<String, List<Node>> writersUnknown = new HashMap<>();
  private final Map<String, List<Node>> writersAll = new HashMap<>();
  private final Map<String, List<Lambda>> lambdas = new HashMap<>();
  private final List<Pending> pending = new ArrayList<>();

  private record Lambda(String method, List<Node> targets) {}

  private record Pending(Node from, Set<Node> site, String type, String method) {}

  public CallGraph(Program program, Policy policy) {
    this.program = program;
    this.policy = policy;
    indexHierarchy();
    for (ClassNode cn : program.classes.values()) {
      for (MethodNode mn : cn.methods) {
        MethodKey key = new MethodKey(cn.name, mn.name, mn.desc);
        methods.put(key, mn);
        if (!hasBody(key)) continue;
        participant(cn, mn, key);
        List<Node> nodes = new ArrayList<>();
        if ((mn.access & Opcodes.ACC_STATIC) == 0) {
          for (String r : receivers(cn.name)) nodes.add(new Node(key, r));
        }
        if (nodes.isEmpty()) nodes.add(new Node(key, null));
        nodesOf.put(key, nodes);
      }
    }
    for (ClassNode cn : program.classes.values()) scan(cn);
    // A lambda typed as an application interface is called wherever that interface's method is
    // called, like any other implementation of it.
    for (Pending p : pending) {
      List<String> types = new ArrayList<>(List.of(p.type()));
      types.addAll(subtypesOf(p.type()));
      for (String t : types) {
        for (Lambda l : lambdas.getOrDefault(t, List.of())) {
          if (l.method().equals(p.method())) for (Node target : l.targets()) edge(p.from(), target, p.site());
        }
      }
    }
    callbacks();
  }

  // --- Callbacks handed in through a parameter ---------------------------------------------
  //
  // A lambda or object passed to a library type is charged to the method that creates it. A
  // wrapper such as withMetrics(Supplier<T> s) that receives it and runs it (s.get()), or a
  // parse(File, ContentHandler handler) that hands it on to a library (setContentHandler), is part
  // of the same flow, so it calls what its callers pass in too: the lambda, or the methods of the
  // application class that implement the parameter's library type. Only callbacks that arrive
  // through a parameter count: a Supplier.get on a field or a local is not linked to anything.

  private static boolean functional(String type) {
    return type.startsWith("java/util/function/") || type.equals("java/lang/Runnable")
        || type.equals("java/util/concurrent/Callable");
  }

  /**
   * Where a value on the stack came from: a parameter of the method, a lambda made there, or an
   * object of an application class made there.
   */
  private record Origin(int param, InvokeDynamicInsnNode lambda, String object) {}

  /** A library type a callback can arrive as: not an application class, and not Object or String. */
  private boolean libraryType(Type t) {
    if (t.getSort() != Type.OBJECT) return false;
    String n = t.getInternalName();
    return !program.classes.containsKey(n) && !n.equals("java/lang/Object") && !n.equals("java/lang/String");
  }

  /** A parameter, or a callback's way into another method: (method, parameter index). */
  private record Slot(MethodKey method, int param) {}

  private void callbacks() {
    Map<Slot, Set<Node>> bound = new HashMap<>();
    Map<Slot, Set<String>> boundObjects = new HashMap<>();
    Map<Slot, Set<Slot>> flows = new HashMap<>();
    Set<Slot> used = new HashSet<>();
    for (ClassNode cn : program.classes.values()) {
      for (MethodNode mn : cn.methods) {
        MethodKey key = new MethodKey(cn.name, mn.name, mn.desc);
        if (!nodesOf.containsKey(key) || !relevant(mn)) continue;
        Frame<SourceValue>[] frames;
        try {
          frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, mn);
        } catch (AnalyzerException | RuntimeException e) {
          continue;
        }
        int[] paramOfSlot = paramSlots(mn);
        Node from = nodesOf.get(key).get(0);
        for (int i = 0; i < mn.instructions.size(); i++) {
          AbstractInsnNode insn = mn.instructions.get(i);
          Frame<SourceValue> f = frames[i];
          if (f == null) continue;
          if (insn instanceof MethodInsnNode mi) {
            Type[] args = Type.getArgumentTypes(mi.desc);
            boolean hasThis = mi.getOpcode() != Opcodes.INVOKESTATIC;
            int base = f.getStackSize() - args.length;
            if (base - (hasThis ? 1 : 0) < 0) continue;
            boolean library = !program.classes.containsKey(mi.owner);
            if (hasThis && library) {
              for (Origin o : origins(f.getStack(base - 1), frames, mn, paramOfSlot, new HashSet<>())) {
                if (o.param() >= 0) used.add(new Slot(key, o.param()));
              }
            }
            List<MethodKey> callees = null;
            for (int j = 0; j < args.length; j++) {
              if (!libraryType(args[j])) continue;
              for (Origin o : origins(f.getStack(base + j), frames, mn, paramOfSlot, new HashSet<>())) {
                if (library) {
                  if (o.param() >= 0) used.add(new Slot(key, o.param()));
                  continue;
                }
                if (callees == null) {
                  int kind = mi.getOpcode() == Opcodes.INVOKESTATIC ? STATIC
                      : mi.getOpcode() == Opcodes.INVOKESPECIAL ? SPECIAL : VIRTUAL;
                  callees = new ArrayList<>();
                  for (Node n : targets(from, kind, false, mi.owner, mi.name, mi.desc)) {
                    if (nodesOf.containsKey(n.method())) callees.add(n.method());
                  }
                }
                for (MethodKey callee : callees) {
                  Slot to = new Slot(callee, j);
                  if (o.param() >= 0) flows.computeIfAbsent(new Slot(key, o.param()), k -> new HashSet<>()).add(to);
                  else if (o.lambda() != null) bound.computeIfAbsent(to, k -> new LinkedHashSet<>()).addAll(lambdaTargets(from, o.lambda()));
                  else boundObjects.computeIfAbsent(to, k -> new TreeSet<>()).add(o.object());
                }
              }
            }
          } else if (insn instanceof InvokeDynamicInsnNode indy) {
            // A lambda that captures a callback parameter: inside the lambda's body the callback
            // arrives as one of its own parameters.
            Handle impl = implementation(indy);
            if (impl == null || !program.classes.containsKey(impl.getOwner())) continue;
            Type[] captured = Type.getArgumentTypes(indy.desc);
            int base = f.getStackSize() - captured.length;
            if (base < 0) continue;
            boolean instance = impl.getTag() != Opcodes.H_INVOKESTATIC;
            MethodKey body = new MethodKey(impl.getOwner(), impl.getName(), impl.getDesc());
            for (int k = 0; k < captured.length; k++) {
              int param = instance ? k - 1 : k;
              if (param < 0) continue;
              for (Origin o : origins(f.getStack(base + k), frames, mn, paramOfSlot, new HashSet<>())) {
                if (o.param() >= 0) flows.computeIfAbsent(new Slot(key, o.param()), x -> new HashSet<>()).add(new Slot(body, param));
              }
            }
          }
        }
      }
    }
    // What reaches each parameter, through any number of wrappers.
    Deque<Slot> work = new ArrayDeque<>(bound.keySet());
    while (!work.isEmpty()) {
      Slot s = work.poll();
      Set<Node> here = bound.get(s);
      for (Slot next : flows.getOrDefault(s, Set.of())) {
        if (bound.computeIfAbsent(next, k -> new LinkedHashSet<>()).addAll(here)) work.add(next);
      }
    }
    work = new ArrayDeque<>(boundObjects.keySet());
    while (!work.isEmpty()) {
      Slot s = work.poll();
      Set<String> here = boundObjects.get(s);
      for (Slot next : flows.getOrDefault(s, Set.of())) {
        if (boundObjects.computeIfAbsent(next, k -> new TreeSet<>()).addAll(here)) work.add(next);
      }
    }
    for (Slot s : used) {
      Set<Node> targets = new LinkedHashSet<>(bound.getOrDefault(s, Set.of()));
      Set<String> objects = boundObjects.getOrDefault(s, Set.of());
      if (!objects.isEmpty()) {
        Type[] params = Type.getArgumentTypes(s.method().desc());
        Set<String> wanted = s.param() < params.length ? libraryMethods(params[s.param()].getInternalName()) : null;
        for (String cls : objects) {
          for (MethodKey cb : shape(cls).callbacks()) {
            if (wanted == null || wanted.contains(cb.name() + cb.desc())) targets.addAll(nodesFor(cb, cls));
          }
        }
      }
      if (targets.isEmpty()) continue;
      for (Node from : nodesOf.getOrDefault(s.method(), List.of())) {
        Set<Node> site = newSite(from);
        for (Node t : targets) edge(from, t, site);
      }
    }
  }

  private final Map<String, Set<String>> libraryMethodCache = new HashMap<>();

  /**
   * The methods (name and descriptor) a library type and its supertypes declare, read from the
   * running JDK when it has the type. Null when it does not: then every method a passed class
   * overrides from its library parents is taken.
   */
  private Set<String> libraryMethods(String type) {
    if (libraryMethodCache.containsKey(type)) return libraryMethodCache.get(type);
    Set<String> out = new HashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(type));
    Set<String> seen = new HashSet<>();
    boolean found = false;
    while (!todo.isEmpty()) {
      String t = todo.poll();
      if (!seen.add(t) || t.equals("java/lang/Object")) continue;
      try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(t + ".class")) {
        if (in == null) continue;
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(in).accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE);
        found |= t.equals(type);
        for (MethodNode mn : cn.methods) if (!mn.name.startsWith("<")) out.add(mn.name + mn.desc);
        if (cn.superName != null) todo.add(cn.superName);
        todo.addAll(cn.interfaces);
      } catch (java.io.IOException e) {
        // not readable: treated as unknown below
      }
    }
    Set<String> result = found ? out : null;
    libraryMethodCache.put(type, result);
    return result;
  }

  private boolean relevant(MethodNode mn) {
    for (Type t : Type.getArgumentTypes(mn.desc)) if (libraryType(t)) return true;
    for (AbstractInsnNode insn : mn.instructions) {
      if (insn instanceof InvokeDynamicInsnNode indy) {
        Type made = Type.getReturnType(indy.desc);
        if (made.getSort() == Type.OBJECT && functional(made.getInternalName())) return true;
      } else if (insn.getOpcode() == Opcodes.NEW && program.classes.containsKey(((TypeInsnNode) insn).desc)) {
        return true;
      }
    }
    return false;
  }

  /** Parameter index for each local slot that holds a parameter on entry, else -1. */
  private static int[] paramSlots(MethodNode mn) {
    int[] out = new int[Math.max(mn.maxLocals, 1) + 1];
    java.util.Arrays.fill(out, -1);
    int slot = (mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
    Type[] args = Type.getArgumentTypes(mn.desc);
    for (int i = 0; i < args.length && slot < out.length; i++) {
      out[slot] = i;
      slot += args[i].getSize();
    }
    return out;
  }

  private static Handle implementation(InvokeDynamicInsnNode indy) {
    if (!indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) return null;
    for (Object arg : indy.bsmArgs) if (arg instanceof Handle h && h.getTag() >= Opcodes.H_INVOKEVIRTUAL) return h;
    return null;
  }

  private List<Node> lambdaTargets(Node from, InvokeDynamicInsnNode indy) {
    Handle h = implementation(indy);
    if (h == null) return List.of();
    int kind = h.getTag() == Opcodes.H_INVOKESTATIC ? STATIC
        : h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE ? VIRTUAL : SPECIAL;
    return targets(from, kind, false, h.getOwner(), h.getName(), h.getDesc());
  }

  /** Follows a value back through local variables to the parameters and lambdas it came from. */
  private Set<Origin> origins(SourceValue v, Frame<SourceValue>[] frames, MethodNode mn, int[] paramOfSlot,
      Set<AbstractInsnNode> seen) {
    Set<Origin> out = new HashSet<>();
    for (AbstractInsnNode src : v.insns) {
      if (!seen.add(src)) continue;
      if (src instanceof InvokeDynamicInsnNode indy && implementation(indy) != null) {
        out.add(new Origin(-1, indy, null));
      } else if (src.getOpcode() == Opcodes.NEW && program.classes.containsKey(((TypeInsnNode) src).desc)) {
        out.add(new Origin(-1, null, ((TypeInsnNode) src).desc));
      } else if (src instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
        Frame<SourceValue> at = frames[mn.instructions.indexOf(src)];
        if (at == null) continue;
        SourceValue local = at.getLocal(load.var);
        if (local.insns.isEmpty()) {
          if (load.var < paramOfSlot.length && paramOfSlot[load.var] >= 0) out.add(new Origin(paramOfSlot[load.var], null, null));
        } else {
          for (AbstractInsnNode store : local.insns) {
            Frame<SourceValue> before = frames[mn.instructions.indexOf(store)];
            if (store instanceof VarInsnNode && before != null && before.getStackSize() > 0) {
              out.addAll(origins(before.getStack(before.getStackSize() - 1), frames, mn, paramOfSlot, seen));
            }
          }
        }
      } else if (src.getOpcode() == Opcodes.CHECKCAST || src.getOpcode() == Opcodes.DUP) {
        Frame<SourceValue> at = frames[mn.instructions.indexOf(src)];
        if (at != null && at.getStackSize() > 0) out.addAll(origins(at.getStack(at.getStackSize() - 1), frames, mn, paramOfSlot, seen));
      }
    }
    return out;
  }

  public List<Set<Node>> sitesOf(Node n) {
    return sites.getOrDefault(n, List.of());
  }

  /** Nodes that may have written a field this read sees. */
  public List<Node> writersOf(String read) {
    int at = read.lastIndexOf('@');
    String field = read.substring(0, at);
    if (read.endsWith("@*")) return writersAll.getOrDefault(field, List.of());
    List<Node> out = new ArrayList<>(writersExact.getOrDefault(read, List.of()));
    out.addAll(writersUnknown.getOrDefault(field, List.of()));
    return out;
  }

  private void participant(ClassNode cn, MethodNode mn, MethodKey key) {
    List<org.objectweb.asm.tree.AnnotationNode> all = new ArrayList<>();
    if (mn.visibleAnnotations != null) all.addAll(mn.visibleAnnotations);
    if (mn.invisibleAnnotations != null) all.addAll(mn.invisibleAnnotations);
    boolean advice = false;
    if (cn.visibleAnnotations != null) {
      for (org.objectweb.asm.tree.AnnotationNode an : cn.visibleAnnotations) advice |= Policy.ADVICE_CLASS_ANNOTATIONS.contains(an.desc);
    }
    for (org.objectweb.asm.tree.AnnotationNode an : all) {
      for (Policy.Participant p : policy.participants) {
        if (!p.matches(an.desc)) continue;
        String type = an.desc.substring(1, an.desc.length() - 1);
        String name = "@" + type.substring(type.lastIndexOf('/') + 1);
        if (p.global() || advice) globalParticipants.putIfAbsent(key, name);
        else classParticipants.putIfAbsent(key, name);
      }
    }
  }

  private static final Set<String> CONVERTER_ANNOTATIONS = Set.of("Lorg/mapstruct/Mapper;", "Lorg/mapstruct/MapperConfig;");
  private final Map<String, Boolean> converterCache = new HashMap<>();

  /** True for a MapStruct mapper or its generated implementation: an object converter, not a store. */
  public boolean converter(String type) {
    Boolean cached = converterCache.get(type);
    if (cached != null) return cached;
    boolean found = false;
    Deque<String> work = new ArrayDeque<>(List.of(type));
    Set<String> seen = new HashSet<>();
    while (!work.isEmpty() && !found) {
      ClassNode cn = program.classes.get(work.poll());
      if (cn == null || !seen.add(cn.name)) continue;
      for (List<org.objectweb.asm.tree.AnnotationNode> list : java.util.Arrays.asList(cn.visibleAnnotations, cn.invisibleAnnotations)) {
        if (list != null) for (org.objectweb.asm.tree.AnnotationNode an : list) found |= CONVERTER_ANNOTATIONS.contains(an.desc);
      }
      if (cn.superName != null) work.add(cn.superName);
      work.addAll(cn.interfaces);
    }
    converterCache.put(type, found);
    return found;
  }

  /** True when a framework constructs this class itself (a stereotype annotation says so). */
  public boolean managed(String cls) {
    ClassNode cn = program.classes.get(cls);
    if (cn == null) return false;
    for (List<org.objectweb.asm.tree.AnnotationNode> list : List.of(
        cn.visibleAnnotations == null ? List.<org.objectweb.asm.tree.AnnotationNode>of() : cn.visibleAnnotations,
        cn.invisibleAnnotations == null ? List.<org.objectweb.asm.tree.AnnotationNode>of() : cn.invisibleAnnotations)) {
      for (org.objectweb.asm.tree.AnnotationNode an : list) if (Policy.MANAGED_CLASS_ANNOTATIONS.contains(an.desc)) return true;
    }
    return false;
  }

  /** Application classes an object of this class holds in its fields, generic arguments included. */
  public Set<String> fieldTypes(String cls) {
    Set<String> out = new TreeSet<>();
    ClassNode cn = program.classes.get(cls);
    if (cn == null) return out;
    java.util.regex.Pattern name = java.util.regex.Pattern.compile("L([^;<]+)[;<]");
    for (FieldNode fn : cn.fields) {
      if ((fn.access & Opcodes.ACC_STATIC) != 0) continue;
      java.util.regex.Matcher m = name.matcher(fn.signature != null ? fn.signature : fn.desc);
      while (m.find()) if (program.classes.containsKey(m.group(1))) out.add(m.group(1));
    }
    return out;
  }

  public Set<Node> calleesOf(Node n) {
    return callees.getOrDefault(n, Collections.emptySet());
  }

  public Set<Node> callersOf(Node n) {
    return callers.getOrDefault(n, Collections.emptySet());
  }

  /** Callees of a declared method across all its receivers. */
  public Set<MethodKey> calleesOf(MethodKey m) {
    Set<MethodKey> out = new TreeSet<>();
    for (Node n : nodesOf.getOrDefault(m, List.of())) {
      for (Node c : calleesOf(n)) out.add(c.method());
    }
    return out;
  }

  public boolean hasBody(MethodKey m) {
    MethodNode mn = methods.get(m);
    return mn != null && (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
  }

  /** Application subtypes (transitive) of an application or library type. */
  public Set<String> subtypesOf(String type) {
    return subtypes.getOrDefault(type, Collections.emptySet());
  }

  public boolean concrete(String cls) {
    ClassNode cn = program.classes.get(cls);
    return cn != null && (cn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0;
  }

  /** The concrete application classes an object of this static type can be. */
  public List<String> receivers(String type) {
    return receiverCache.computeIfAbsent(type, t -> {
      List<String> out = new ArrayList<>();
      if (concrete(t)) out.add(t);
      for (String s : subtypesOf(t)) if (concrete(s)) out.add(s);
      return out;
    });
  }

  public static String topLevel(String internalName) {
    int slash = internalName.lastIndexOf('/');
    int dollar = internalName.indexOf('$', slash + 1);
    return dollar < 0 ? internalName : internalName.substring(0, dollar);
  }

  /** Stable digest of a method's signature and instructions; debug info does not participate. */
  public String bodyHash(MethodKey key) {
    MethodNode mn = methods.get(key);
    Textifier t = new Textifier();
    mn.accept(new TraceMethodVisitor(t));
    StringBuilder text = new StringBuilder();
    for (Object o : t.getText()) text.append(o);
    StringBuilder sb = new StringBuilder().append(mn.access).append('|').append(mn.exceptions).append('|')
        .append(stableLambdaNames(key.owner(), text.toString()));
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Lambda identity -------------------------------------------------------------------------
  //
  // javac numbers lambda bodies per class (lambda$countLong$1), so adding a lambda to one method
  // renumbers the lambdas of every method after it. A lambda is known instead by its enclosing
  // method and its place among that method's lambdas of the same type; that is what a reference
  // to it hashes as, so an untouched method stays untouched.

  private static final java.util.regex.Pattern JAVAC_LAMBDA = java.util.regex.Pattern.compile("lambda\\$(.+)\\$(\\d+)");
  private static final java.util.regex.Pattern LAMBDA_TOKEN = java.util.regex.Pattern.compile("lambda\\$[\\w$]*?\\$\\d+(?=\\()");
  private Map<String, Map<String, String>> lambdaNames;

  /** The method a javac lambda body was written in, or null if this is not one. */
  static String enclosing(MethodKey m) {
    java.util.regex.Matcher x = JAVAC_LAMBDA.matcher(m.name());
    return x.matches() ? x.group(1) : null;
  }

  static int lambdaIndex(MethodKey m) {
    java.util.regex.Matcher x = JAVAC_LAMBDA.matcher(m.name());
    return x.matches() ? Integer.parseInt(x.group(2)) : -1;
  }

  /** A stable stand-in for each javac lambda of the class: enclosing method, type and place. */
  public String lambdaName(MethodKey m) {
    if (lambdaNames == null) {
      lambdaNames = new HashMap<>();
      Map<String, List<MethodKey>> groups = new TreeMap<>();
      for (MethodKey k : methods.keySet()) {
        String enc = enclosing(k);
        if (enc != null) groups.computeIfAbsent(k.owner() + "#" + enc + k.desc(), x -> new ArrayList<>()).add(k);
      }
      for (List<MethodKey> g : groups.values()) {
        g.sort(java.util.Comparator.comparingInt(CallGraph::lambdaIndex));
        for (int i = 0; i < g.size(); i++) {
          MethodKey k = g.get(i);
          lambdaNames.computeIfAbsent(k.owner(), x -> new HashMap<>())
              .put(k.name(), "lambda:" + enclosing(k) + ":" + k.desc() + ":" + i);
        }
      }
    }
    return lambdaNames.getOrDefault(m.owner(), Map.of()).get(m.name());
  }

  private String stableLambdaNames(String owner, String text) {
    java.util.regex.Matcher x = LAMBDA_TOKEN.matcher(text);
    StringBuilder out = new StringBuilder();
    while (x.find()) {
      String stable = lambdaName(new MethodKey(owner, x.group(), ""));
      x.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(stable != null ? stable : x.group()));
    }
    x.appendTail(out);
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------

  private void indexHierarchy() {
    Map<String, Set<String>> direct = new HashMap<>();
    for (ClassNode cn : program.classes.values()) {
      if (cn.superName != null) direct.computeIfAbsent(cn.superName, k -> new HashSet<>()).add(cn.name);
      for (String itf : cn.interfaces) direct.computeIfAbsent(itf, k -> new HashSet<>()).add(cn.name);
    }
    for (String type : direct.keySet()) {
      Set<String> all = new TreeSet<>();
      Deque<String> work = new ArrayDeque<>(direct.get(type));
      while (!work.isEmpty()) {
        String s = work.poll();
        if (all.add(s)) work.addAll(direct.getOrDefault(s, Collections.emptySet()));
      }
      subtypes.put(type, all);
    }
  }

  private void scan(ClassNode cn) {
    String self = topLevel(cn.name);
    Set<String> deps = classDeps.computeIfAbsent(self, k -> new TreeSet<>());
    dep(deps, self, cn.superName);
    for (String itf : cn.interfaces) dep(deps, self, itf);
    for (FieldNode fn : cn.fields) depType(deps, self, Type.getType(fn.desc));
    for (MethodNode mn : cn.methods) {
      MethodKey key = new MethodKey(cn.name, mn.name, mn.desc);
      Set<String> data = dataTypes.computeIfAbsent(key, k -> new TreeSet<>());
      currentRefs = data;
      depType(deps, self, Type.getMethodType(mn.desc));
      for (AbstractInsnNode insn : mn.instructions) {
        if (insn instanceof MethodInsnNode mi) {
          currentRefs = null;
          dep(deps, self, mi.owner);
          currentRefs = data;
          depType(deps, self, Type.getMethodType(mi.desc));
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
          currentRefs = null;
          for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) dep(deps, self, h.getOwner());
          currentRefs = data;
        } else if (insn instanceof FieldInsnNode fi) {
          dep(deps, self, fi.owner);
        } else if (insn instanceof TypeInsnNode ti) {
          depType(deps, self, Type.getObjectType(ti.desc));
        } else if (insn instanceof MultiANewArrayInsnNode ma) {
          depType(deps, self, Type.getType(ma.desc));
        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) {
          depType(deps, self, t);
        }
      }
      data.remove(cn.name);
      currentRefs = null;

      List<Node> nodes = nodesOf.get(key);
      if (nodes == null) continue;
      Set<AbstractInsnNode> onThis = onThis(cn.name, mn);
      List<String[]> expressionCalls = mn.name.startsWith("<") ? List.<String[]>of() : expressionCalls(cn, mn);
      for (Node from : nodes) {
        for (String[] call : expressionCalls) expressionCall(from, call[0], call[1]);
        for (AbstractInsnNode insn : mn.instructions) {
          if (insn instanceof MethodInsnNode mi) {
            int kind = mi.getOpcode() == Opcodes.INVOKESTATIC ? STATIC
                : mi.getOpcode() == Opcodes.INVOKESPECIAL ? SPECIAL : VIRTUAL;
            call(from, kind, onThis.contains(insn), mi.owner, mi.name, mi.desc);
          } else if (insn instanceof InvokeDynamicInsnNode indy) {
            lambda(from, indy, onThis.contains(insn));
          } else if (insn instanceof FieldInsnNode fi) {
            field(from, fi, onThis.contains(insn));
          }
        }
      }
    }
  }

  private static final java.util.regex.Pattern BEAN_CALL = java.util.regex.Pattern.compile("@(\\w+)\\??\\.(\\w+)\\s*\\(");
  private Map<String, Set<String>> beans;

  /**
   * Bean method calls written inside annotation strings on a method or its class, such as
   * {@code @PreAuthorize("@permissionValidator.canEdit(#id)")}: a framework evaluates the expression
   * around the annotated method, so the annotated method in effect calls the bean method.
   */
  private List<String[]> expressionCalls(ClassNode cn, MethodNode mn) {
    List<String> strings = new ArrayList<>();
    for (List<org.objectweb.asm.tree.AnnotationNode> list : java.util.Arrays.asList(
        mn.visibleAnnotations, mn.invisibleAnnotations, cn.visibleAnnotations, cn.invisibleAnnotations)) {
      if (list != null) for (org.objectweb.asm.tree.AnnotationNode an : list) annotationStrings(an, strings);
    }
    List<String[]> out = new ArrayList<>();
    for (String s : strings) {
      java.util.regex.Matcher m = BEAN_CALL.matcher(s);
      while (m.find()) out.add(new String[] {m.group(1), m.group(2)});
    }
    return out;
  }

  private static void annotationStrings(Object value, List<String> out) {
    if (value instanceof String s) out.add(s);
    else if (value instanceof List<?> l) for (Object o : l) annotationStrings(o, out);
    else if (value instanceof org.objectweb.asm.tree.AnnotationNode an && an.values != null) {
      for (int i = 1; i < an.values.size(); i += 2) annotationStrings(an.values.get(i), out);
    }
  }

  /** Application classes a bean name may stand for; every class when the name cannot be resolved. */
  private Set<String> beanClasses(String bean) {
    if (beans == null) {
      beans = new HashMap<>();
      for (ClassNode cn : program.classes.values()) {
        String simple = cn.name.substring(Math.max(cn.name.lastIndexOf('/'), cn.name.lastIndexOf('$')) + 1);
        if (!simple.isEmpty()) {
          beans.computeIfAbsent(Character.toLowerCase(simple.charAt(0)) + simple.substring(1), k -> new TreeSet<>()).add(cn.name);
        }
        List<String> names = new ArrayList<>();
        if (cn.visibleAnnotations != null) {
          for (org.objectweb.asm.tree.AnnotationNode an : cn.visibleAnnotations) {
            if (Policy.MANAGED_CLASS_ANNOTATIONS.contains(an.desc)) annotationStrings(an, names);
          }
        }
        for (String n : names) beans.computeIfAbsent(n, k -> new TreeSet<>()).add(cn.name);
        // A factory method names a bean after itself (or after its annotation's value).
        for (MethodNode mn : cn.methods) {
          if (mn.visibleAnnotations == null) continue;
          for (org.objectweb.asm.tree.AnnotationNode an : mn.visibleAnnotations) {
            if (!an.desc.endsWith("/Bean;")) continue;
            Type ret = Type.getReturnType(mn.desc);
            if (ret.getSort() != Type.OBJECT) continue;
            List<String> beanNames = new ArrayList<>(List.of(mn.name));
            annotationStrings(an, beanNames);
            List<String> types = new ArrayList<>(List.of(ret.getInternalName()));
            types.addAll(subtypesOf(ret.getInternalName()));
            for (String n : beanNames) for (String t : types) {
              if (program.classes.containsKey(t)) beans.computeIfAbsent(n, k -> new TreeSet<>()).add(t);
            }
          }
        }
      }
    }
    Set<String> found = beans.get(bean);
    return found != null ? found : program.classes.keySet();
  }

  private void expressionCall(Node from, String bean, String name) {
    for (String type : beanClasses(bean)) {
      List<String> owners = new ArrayList<>(receivers(type));
      if (owners.isEmpty()) owners.add(type);
      for (String r : owners) {
        Set<String> seen = new HashSet<>();
        for (String c = r; c != null && program.classes.containsKey(c); c = program.classes.get(c).superName) {
          for (MethodNode mn : program.classes.get(c).methods) {
            MethodKey target = new MethodKey(c, mn.name, mn.desc);
            if (!mn.name.equals(name) || !hasBody(target) || !seen.add(mn.desc)) continue;
            Set<Node> site = newSite(from);
            for (Node n : nodesFor(target, r)) edge(from, n, site);
          }
        }
      }
    }
  }

  private void call(Node from, int kind, boolean onThis, String owner, String name, String desc) {
    if (owner.startsWith("[")) owner = "java/lang/Object";
    for (Policy.Rule r : policy.opaque) {
      if (r.matches(owner, name)) {
        opaqueCalls.computeIfAbsent(from, k -> new TreeSet<>())
            .add(owner.substring(owner.lastIndexOf('/') + 1) + "." + name);
      }
    }
    store(from, owner, name);
    Set<Node> site = newSite(from);
    for (Node target : targets(from, kind, onThis, owner, name, desc)) edge(from, target, site);
    if (kind == VIRTUAL && !(onThis && from.receiver() != null)) pending.add(new Pending(from, site, owner, name));
    if (kind == SPECIAL && name.equals("<init>") && !onThis && program.classes.containsKey(owner)) {
      constructed(from, owner);
    }
  }

  /** new X(...): X now exists, and a library it extends may call its overridable methods. */
  private void constructed(Node from, String owner) {
    if (!from.method().owner().equals(owner)) instantiated.add(owner);
    for (MethodKey cb : shape(owner).callbacks()) {
      Set<Node> site = newSite(from);
      for (Node target : nodesFor(cb, owner)) edge(from, target, site);
    }
  }

  private void lambda(Node from, InvokeDynamicInsnNode indy, boolean firstCaptureIsThis) {
    Type made = Type.getReturnType(indy.desc);
    String iface = made.getSort() == Type.OBJECT ? made.getInternalName() : null;
    boolean appInterface = indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
        && iface != null && program.classes.containsKey(iface);
    for (Object arg : indy.bsmArgs) {
      if (!(arg instanceof Handle h) || h.getTag() < Opcodes.H_INVOKEVIRTUAL) continue;
      boolean isNew = h.getTag() == Opcodes.H_NEWINVOKESPECIAL;
      int kind = h.getTag() == Opcodes.H_INVOKESTATIC ? STATIC
          : h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE ? VIRTUAL : SPECIAL;
      List<Node> targets = targets(from, kind, firstCaptureIsThis && !isNew, h.getOwner(), h.getName(), h.getDesc());
      if (appInterface) {
        lambdas.computeIfAbsent(iface, k -> new ArrayList<>()).add(new Lambda(indy.name, targets));
        for (Node t : targets) creators.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(from);
      }
      // A library interface, or one that extends a library interface: the library may call it,
      // so the creating method is charged with the target.
      if (!appInterface || shape(iface).libraryParent() != null) {
        Set<Node> site = newSite(from);
        for (Node target : targets) edge(from, target, site);
        if (isNew && program.classes.containsKey(h.getOwner())) constructed(from, h.getOwner());
      }
    }
  }

  private void field(Node from, FieldInsnNode fi, boolean onThis) {
    String declaring = fi.owner;
    FieldNode decl = null;
    while (decl == null && declaring != null && program.classes.containsKey(declaring)) {
      ClassNode c = program.classes.get(declaring);
      for (FieldNode fn : c.fields) if (fn.name.equals(fi.name)) decl = fn;
      if (decl == null) declaring = c.superName;
    }
    if (decl == null) return;
    String field = declaring + "#" + fi.name;
    boolean isStatic = fi.getOpcode() == Opcodes.GETSTATIC || fi.getOpcode() == Opcodes.PUTSTATIC;
    String receiver = !isStatic && onThis && from.receiver() != null ? from.receiver() : "*";
    boolean write = fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC;
    if (!write) fieldReads.computeIfAbsent(from, k -> new TreeSet<>()).add(field + "@" + receiver);
    // Reading a collection is enough to change it, so such a read counts as a write as well;
    // except in toString, hashCode and equals, which by contract only look.
    if (write || (mutableContainer(decl.desc) && !objectMethod(from.method()))) {
      writersAll.computeIfAbsent(field, k -> new ArrayList<>()).add(from);
      if (receiver.equals("*")) writersUnknown.computeIfAbsent(field, k -> new ArrayList<>()).add(from);
      else writersExact.computeIfAbsent(field + "@" + receiver, k -> new ArrayList<>()).add(from);
    }
  }

  private static final java.util.regex.Pattern STORE_NAME =
      java.util.regex.Pattern.compile(".*(Repository|Repo|Mapper|Dao|DAO|Store)(Impl)?");
  private static final Set<String> DATA_CLIENTS = Set.of(Policy.DATA_CLIENTS);
  private final Map<String, String> storeCache = new HashMap<>();

  /**
   * The store a type stands for, or null: a repository, mapper or DAO of the application (named
   * for the topmost such type, so an interface and its implementation are one store), or a
   * database client from a library.
   */
  public String storeOf(String type) {
    if (storeCache.containsKey(type)) return storeCache.get(type);
    // A MapStruct mapper converts objects; despite its name it stores nothing.
    if (converter(type)) {
      storeCache.put(type, null);
      return null;
    }
    String found = null;
    Deque<String> work = new ArrayDeque<>(List.of(type));
    Set<String> seen = new HashSet<>();
    while (!work.isEmpty()) {
      String t = work.poll();
      if (!seen.add(t)) continue;
      ClassNode cn = program.classes.get(t);
      if (cn == null) {
        if (DATA_CLIENTS.contains(t)) found = t;
        // An application interface that extends a Spring Data repository is a store itself.
        else if (t.startsWith("org/springframework/data/") && !t.equals(type)) found = found == null ? type : found;
        continue;
      }
      if (STORE_NAME.matcher(t.substring(t.lastIndexOf('/') + 1)).matches()) found = t;
      if (cn.superName != null) work.add(cn.superName);
      work.addAll(cn.interfaces);
    }
    storeCache.put(type, found);
    return found;
  }

  /** Records a call that reads or writes a store, so that writers reach the readers they affect. */
  private void store(Node from, String owner, String name) {
    if (name.startsWith("<")) return;
    Set<String> written = new TreeSet<>();
    Set<String> read = new TreeSet<>();
    for (Policy.Rule r : policy.storeWrites) if (r.matches(owner, name)) written.add(r.domain());
    for (Policy.Rule r : policy.storeReads) if (r.matches(owner, name)) read.add(r.domain());
    if (written.isEmpty() && read.isEmpty()) {
      String auto = storeOf(owner);
      if (auto != null && !from.method().owner().equals(owner)) {
        String label = auto.substring(auto.lastIndexOf('/') + 1);
        if (Policy.writes(name)) written.add(label);
        if (Policy.reads(name)) read.add(label);
      }
    }
    for (String s : written) {
      writersAll.computeIfAbsent("store:" + s, k -> new ArrayList<>()).add(from);
    }
    for (String s : read) fieldReads.computeIfAbsent(from, k -> new TreeSet<>()).add("store:" + s + "@*");
  }

  /** toString(), hashCode() or equals(Object): methods whose contract is to look, not change. */
  static boolean objectMethod(MethodKey m) {
    return (m.name().equals("toString") && m.desc().equals("()Ljava/lang/String;"))
        || (m.name().equals("hashCode") && m.desc().equals("()I"))
        || (m.name().equals("equals") && m.desc().equals("(Ljava/lang/Object;)Z"));
  }

  private static boolean mutableContainer(String desc) {
    return desc.startsWith("[") || desc.startsWith("Ljava/util/") || desc.equals("Ljava/lang/StringBuilder;")
        || desc.equals("Ljava/lang/StringBuffer;");
  }

  private Set<Node> newSite(Node from) {
    Set<Node> site = new LinkedHashSet<>();
    sites.computeIfAbsent(from, k -> new ArrayList<>()).add(site);
    return site;
  }

  /**
   * The call and field instructions whose receiver is certainly {@code this}; for a lambda or
   * method reference, those whose first captured value is {@code this}.
   */
  private Set<AbstractInsnNode> onThis(String owner, MethodNode mn) {
    Set<AbstractInsnNode> out = new HashSet<>();
    if ((mn.access & Opcodes.ACC_STATIC) != 0) return out;
    for (AbstractInsnNode insn : mn.instructions) {
      if (insn instanceof VarInsnNode v && v.var == 0 && v.getOpcode() == Opcodes.ASTORE) return out;
    }
    Frame<SourceValue>[] frames;
    try {
      frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, mn);
    } catch (AnalyzerException | RuntimeException e) {
      return out;
    }
    for (int i = 0; i < mn.instructions.size(); i++) {
      AbstractInsnNode insn = mn.instructions.get(i);
      Frame<SourceValue> f = frames[i];
      if (f == null) continue;
      int below;
      if (insn instanceof MethodInsnNode mi && mi.getOpcode() != Opcodes.INVOKESTATIC) {
        below = Type.getArgumentTypes(mi.desc).length + 1;
      } else if (insn instanceof InvokeDynamicInsnNode indy && Type.getArgumentTypes(indy.desc).length > 0) {
        below = Type.getArgumentTypes(indy.desc).length;
      } else if (insn.getOpcode() == Opcodes.GETFIELD) {
        below = 1;
      } else if (insn.getOpcode() == Opcodes.PUTFIELD) {
        below = 2;
      } else {
        continue;
      }
      if (f.getStackSize() < below) continue;
      SourceValue receiver = f.getStack(f.getStackSize() - below);
      boolean isThis = !receiver.insns.isEmpty();
      for (AbstractInsnNode src : receiver.insns) {
        isThis &= src instanceof VarInsnNode v && v.var == 0 && v.getOpcode() == Opcodes.ALOAD;
      }
      if (isThis) out.add(insn);
    }
    return out;
  }

  private void dep(Set<String> deps, String self, String internalName) {
    if (internalName == null) return;
    if (internalName.startsWith("[")) {
      depType(deps, self, Type.getType(internalName));
      return;
    }
    if (!program.classes.containsKey(internalName)) return;
    if (currentRefs != null) currentRefs.add(internalName);
    String top = topLevel(internalName);
    if (!top.equals(self)) deps.add(top);
  }

  private void depType(Set<String> deps, String self, Type t) {
    switch (t.getSort()) {
      case Type.ARRAY -> depType(deps, self, t.getElementType());
      case Type.OBJECT -> dep(deps, self, t.getInternalName());
      case Type.METHOD -> {
        depType(deps, self, t.getReturnType());
        for (Type a : t.getArgumentTypes()) depType(deps, self, a);
      }
      default -> { }
    }
  }

  private static final int STATIC = 0;
  private static final int SPECIAL = 1;
  private static final int VIRTUAL = 2;

  /** The nodes a call may land on. */
  private List<Node> targets(Node from, int kind, boolean onThis, String owner, String name, String desc) {
    if (owner.startsWith("[")) owner = "java/lang/Object";
    MethodKey declared = resolveUp(owner, name, desc);
    MethodKey symbolic = declared != null ? declared : new MethodKey(owner, name, desc);
    String self = from.receiver();
    if (kind == STATIC) return nodesFor(symbolic, null);
    // new X(...): the receiver is exactly X.
    if (kind == SPECIAL && name.equals("<init>") && !onThis) return nodesFor(symbolic, owner);
    // super call, private call or this(...): same object when the receiver is this.
    if (kind == SPECIAL) return nodesFor(symbolic, onThis ? self : null);
    if (onThis && self != null) {
      MethodKey bound = resolveUp(self, name, desc);
      return nodesFor(bound != null ? bound : symbolic, self);
    }
    // toString, hashCode or equals called on a plain Object: every class in the program would be a
    // candidate. As with string concatenation (which reaches toString inside the library), these
    // calls are not followed into the application.
    if (owner.equals("java/lang/Object") && objectMethod(symbolic)) return List.of(new Node(symbolic, null));
    List<Node> out = new ArrayList<>();
    for (String r : receivers(owner)) {
      MethodKey bound = resolveUp(r, name, desc);
      if (bound != null && hasBody(bound)) out.add(new Node(bound, r));
    }
    if (!hasBody(symbolic)) out.add(new Node(symbolic, null));
    else if (out.isEmpty()) out.addAll(nodesFor(symbolic, null));
    return out;
  }

  /** A method on a known receiver, or on all its receivers when the receiver is unknown. */
  private List<Node> nodesFor(MethodKey target, String receiver) {
    List<Node> nodes = nodesOf.get(target);
    if (nodes == null) return List.of(new Node(target, null));
    if (receiver != null) {
      Node exact = new Node(target, receiver);
      if (nodes.contains(exact)) return List.of(exact);
    }
    return nodes;
  }

  private void edge(Node from, Node to, Set<Node> site) {
    site.add(to);
    targets.add(to.method());
    callees.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
    callers.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
  }

  /** The application method a call would bind to, searching the owner and its supertypes. */
  MethodKey resolveUp(String owner, String name, String desc) {
    Deque<String> work = new ArrayDeque<>(List.of(owner));
    Set<String> seen = new HashSet<>();
    while (!work.isEmpty()) {
      String c = work.poll();
      ClassNode cn = program.classes.get(c);
      if (cn == null || !seen.add(c)) continue;
      for (MethodNode mn : cn.methods) {
        if (mn.name.equals(name) && mn.desc.equals(desc)) return new MethodKey(c, name, desc);
      }
      if (cn.superName != null) work.add(cn.superName);
      work.addAll(cn.interfaces);
    }
    return null;
  }

  private static final Set<String> BENIGN_SUPERS = Set.of(
      "java/lang/Object", "java/lang/Record", "java/lang/Enum", "java/lang/annotation/Annotation",
      "java/io/Serializable", "java/lang/Cloneable");

  /**
   * What a class inherits.
   *
   * @param superclasses application superclasses, nearest first
   * @param libraryParent a library type it extends or implements, or null
   * @param callbacks methods a library could call on an instance, if there is a library parent
   */
  public record Shape(List<String> superclasses, String libraryParent, List<MethodKey> callbacks) {}

  public Shape shape(String owner) {
    Shape cached = shapes.get(owner);
    if (cached != null) return cached;
    List<ClassNode> chain = new ArrayList<>();
    String libraryParent = null;
    Deque<String> work = new ArrayDeque<>(List.of(owner));
    Set<String> seen = new HashSet<>();
    while (!work.isEmpty()) {
      String c = work.poll();
      if (!seen.add(c)) continue;
      ClassNode cn = program.classes.get(c);
      if (cn == null) {
        if (!BENIGN_SUPERS.contains(c) && libraryParent == null) libraryParent = c;
        continue;
      }
      chain.add(cn);
      if (cn.superName != null) work.add(cn.superName);
      work.addAll(cn.interfaces);
    }
    List<MethodKey> callbacks = new ArrayList<>();
    if (libraryParent != null) {
      for (ClassNode cn : chain) {
        for (MethodNode mn : cn.methods) {
          boolean skip = (mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) != 0;
          MethodKey key = new MethodKey(cn.name, mn.name, mn.desc);
          // Only the version this class would actually run, not ones it overrides.
          if (!skip && !mn.name.startsWith("<") && key.equals(resolveUp(owner, mn.name, mn.desc))) callbacks.add(key);
        }
      }
    }
    List<String> supers = new ArrayList<>();
    ClassNode cn = program.classes.get(owner);
    while (cn != null && cn.superName != null && program.classes.containsKey(cn.superName)) {
      supers.add(cn.superName);
      cn = program.classes.get(cn.superName);
    }
    Shape shape = new Shape(supers, libraryParent, callbacks);
    shapes.put(owner, shape);
    return shape;
  }
}
