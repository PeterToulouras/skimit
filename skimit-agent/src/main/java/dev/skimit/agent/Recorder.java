package dev.skimit.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Called from instrumented code. Tracks, per thread, the application methods currently running
 * and the ones that have run in the current flow, and remembers what a flow contained whenever it
 * reached a sink.
 *
 * <p>A flow is everything that runs under the outermost instrumented frame on a thread: the
 * outermost application method, or a framework boundary such as a servlet dispatch when one is
 * configured, so that a whole request counts as one flow.
 */
public final class Recorder {
  private Recorder() {}

  /** Frame id used for framework boundaries: it holds a flow open but is not a method. */
  public static final int BOUNDARY = -1;

  private static final List<String> METHODS = new ArrayList<>();
  private static final Set<Integer> EXECUTED = ConcurrentHashMap.newKeySet();
  private static final Set<Integer> ON_STACK = ConcurrentHashMap.newKeySet();
  private static final Map<Integer, Integer> BEFORE = new ConcurrentHashMap<>();
  private static final Map<Integer, Integer> AFTER = new ConcurrentHashMap<>();
  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong FLOWS = new AtomicLong();
  private static final AtomicLong CRITICAL_FLOWS = new AtomicLong();

  private static final class Flow {
    int[] stack = new int[64];
    int depth;
    boolean hit;
    boolean callPending;
    int root = BOUNDARY;
    final Set<Integer> before = new LinkedHashSet<>();
    final Set<Integer> after = new LinkedHashSet<>();
  }

  private static final ThreadLocal<Flow> FLOW = ThreadLocal.withInitial(Flow::new);

  static synchronized int register(String key) {
    METHODS.add(key);
    return METHODS.size() - 1;
  }

  public static void enter(int id) {
    Flow f = FLOW.get();
    if (f.depth == 0) {
      f.hit = false;
      f.root = BOUNDARY;
      f.before.clear();
      f.after.clear();
      FLOWS.incrementAndGet();
    }
    f.callPending = false;
    if (f.depth == f.stack.length) f.stack = java.util.Arrays.copyOf(f.stack, f.depth * 2);
    f.stack[f.depth++] = id;
    if (id == BOUNDARY) return;
    EXECUTED.add(id);
    if (f.root == BOUNDARY) f.root = id;
    (f.hit ? f.after : f.before).add(id);
  }

  public static void exit(int id) {
    Flow f = FLOW.get();
    // Pop through anything left behind by a constructor that threw.
    while (f.depth > 0 && f.stack[--f.depth] != id) { }
    if (f.depth == 0 && f.hit) {
      CRITICAL_FLOWS.incrementAndGet();
      for (Integer m : f.before) BEFORE.putIfAbsent(m, f.root);
      for (Integer m : f.after) if (!f.before.contains(m)) AFTER.putIfAbsent(m, f.root);
      f.hit = false;
    }
  }

  /** A sink is about to be called. */
  public static void hit() {
    Flow f = FLOW.get();
    f.callPending = true;
    record(f);
  }

  /** A sink with a body is being entered; not counted again if the call was just seen. */
  public static void hitEntry() {
    Flow f = FLOW.get();
    if (f.callPending) f.callPending = false;
    else record(f);
  }

  private static void record(Flow f) {
    HITS.incrementAndGet();
    f.hit = true;
    for (int i = 0; i < f.depth; i++) if (f.stack[i] != BOUNDARY) ON_STACK.add(f.stack[i]);
  }

  static void dump(Path out) {
    List<String> methods;
    synchronized (Recorder.class) {
      methods = new ArrayList<>(METHODS);
    }
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      w.println("skimit-trace 1");
      w.println("hits " + HITS.get());
      w.println("flows " + FLOWS.get());
      w.println("critical-flows " + CRITICAL_FLOWS.get());
      for (Integer id : EXECUTED) w.println("ran " + methods.get(id));
      for (Integer id : ON_STACK) w.println("stack " + methods.get(id));
      for (Map.Entry<Integer, Integer> e : BEFORE.entrySet()) {
        w.println("before " + methods.get(e.getKey()) + " " + methods.get(e.getValue()));
      }
      for (Map.Entry<Integer, Integer> e : AFTER.entrySet()) {
        if (!BEFORE.containsKey(e.getKey())) w.println("after " + methods.get(e.getKey()) + " " + methods.get(e.getValue()));
      }
    } catch (IOException e) {
      System.err.println("skimit-agent: could not write " + out + ": " + e);
    }
  }
}
