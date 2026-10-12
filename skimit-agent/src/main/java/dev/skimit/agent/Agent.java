package dev.skimit.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Java agent: {@code -javaagent:skimit-agent.jar=config=FILE,out=FILE}. A {@code %p} in the output
 * name is replaced by the process id.
 *
 * <p>The config file is written by {@code skimit analyze --agent-config}. It lists the application
 * classes to instrument ({@code class a/b/C}), the sinks ({@code sink owner#name#desc}) and the
 * framework methods that hold a flow open ({@code boundary owner#name}). The trace is written when
 * the JVM exits and is read by {@code skimit verify}.
 */
public final class Agent {
  private Agent() {}

  private static final String RECORDER = "dev/skimit/agent/Recorder";

  public static void premain(String args, Instrumentation inst) throws IOException {
    Path config = null;
    Path out = Path.of("skimit-trace.txt");
    for (String part : (args == null ? "" : args).split(",")) {
      if (part.startsWith("config=")) config = Path.of(part.substring(7));
      if (part.startsWith("out=")) out = Path.of(part.substring(4));
    }
    if (config == null) {
      System.err.println("skimit-agent: no config=FILE given; nothing will be recorded");
      return;
    }
    Set<String> classes = new HashSet<>();
    Set<String> sinks = new HashSet<>();
    Set<String> boundaries = new HashSet<>();
    for (String line : Files.readAllLines(config)) {
      if (line.startsWith("class ")) classes.add(line.substring(6));
      else if (line.startsWith("sink ")) sinks.add(line.substring(5));
      else if (line.startsWith("boundary ")) boundaries.add(line.substring(9));
    }
    Set<String> boundaryOwners = new HashSet<>();
    for (String b : boundaries) boundaryOwners.add(b.substring(0, b.indexOf('#')));
    // %p in the file name becomes the process id, so forked test JVMs do not overwrite each other.
    Path target = Path.of(out.toString().replace("%p", Long.toString(ProcessHandle.current().pid()))).toAbsolutePath();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> Recorder.dump(target), "skimit-agent-dump"));

    inst.addTransformer(new ClassFileTransformer() {
      @Override
      public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (name == null) return null;
        boolean app = classes.contains(name);
        if (!app && !boundaryOwners.contains(name)) return null;
        try {
          return instrument(bytes, app, sinks, boundaries);
        } catch (RuntimeException e) {
          System.err.println("skimit-agent: left " + name + " alone: " + e);
          return null;
        }
      }
    });
  }

  static byte[] instrument(byte[] bytes, boolean app, Set<String> sinks, Set<String> boundaries) {
    ClassNode cn = new ClassNode();
    new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
    if ((cn.access & Opcodes.ACC_INTERFACE) != 0 && cn.version < Opcodes.V1_8) return null;
    boolean changed = false;
    for (MethodNode mn : cn.methods) {
      if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || mn.instructions.size() == 0) continue;
      if (mn.name.equals("<clinit>")) continue;
      String key = cn.name + "#" + mn.name + "#" + mn.desc;
      int id;
      if (app) id = Recorder.register(key);
      else if (boundaries.contains(cn.name + "#" + mn.name)) id = Recorder.BOUNDARY;
      else continue;
      wrap(cn, mn, id, app && sinks.contains(key), app ? sinks : Set.of());
      changed = true;
    }
    if (!changed) return null;
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cn.accept(cw);
    return cw.toByteArray();
  }

  private static void wrap(ClassNode cn, MethodNode mn, int id, boolean isSink, Set<String> sinks) {
    // A hit before every call that lands on a sink.
    for (AbstractInsnNode insn : mn.instructions.toArray()) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode mi = (MethodInsnNode) insn;
        if (sinks.contains(mi.owner + "#" + mi.name + "#" + mi.desc)) {
          mn.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "hit", "()V", false));
        }
      }
    }
    // exit before every return
    for (AbstractInsnNode insn : mn.instructions.toArray()) {
      int op = insn.getOpcode();
      if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) mn.instructions.insertBefore(insn, call("exit", id));
    }
    InsnList head = new InsnList();
    if (isSink) head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "hitEntry", "()V", false));
    head.add(call("enter", id));
    LabelNode start = new LabelNode();
    head.add(start);
    mn.instructions.insert(head);
    // A constructor cannot be wrapped: `this` is not initialised for part of its body.
    if (mn.name.equals("<init>")) return;
    LabelNode end = new LabelNode();
    LabelNode handler = new LabelNode();
    mn.instructions.add(end);
    mn.instructions.add(handler);
    List<Object> locals = new ArrayList<>();
    if ((mn.access & Opcodes.ACC_STATIC) == 0) locals.add(cn.name);
    for (Type t : Type.getArgumentTypes(mn.desc)) {
      switch (t.getSort()) {
        case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT: locals.add(Opcodes.INTEGER); break;
        case Type.FLOAT: locals.add(Opcodes.FLOAT); break;
        case Type.LONG: locals.add(Opcodes.LONG); break;
        case Type.DOUBLE: locals.add(Opcodes.DOUBLE); break;
        default: locals.add(t.getInternalName());
      }
    }
    mn.instructions.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[] {"java/lang/Throwable"}));
    mn.instructions.add(call("exit", id));
    mn.instructions.add(new InsnNode(Opcodes.ATHROW));
    mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
  }

  private static InsnList call(String method, int id) {
    InsnList l = new InsnList();
    l.add(new LdcInsnNode(id));
    l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, method, "(I)V", false));
    return l;
  }
}
