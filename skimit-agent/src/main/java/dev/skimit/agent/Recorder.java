package dev.skimit.agent;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Called from instrumented code. Tracks, per thread, the application methods currently running,
 * and whenever a sink is called remembers which of them it was called under: every method on the
 * stack at that moment reaches that sink.
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
  /** Pairs of a method on the stack and the sink it was calling into, packed as frame id and sink id. */
  private static final Set<Long> REACHES = ConcurrentHashMap.newKeySet();
  private static final List<String> SINKS = new ArrayList<>();
  private static final Map<String, Integer> SINK_IDS = new ConcurrentHashMap<>();
  private static final AtomicLong HITS = new AtomicLong();
  private static final AtomicLong FLOWS = new AtomicLong();
  private static final AtomicLong CRITICAL_FLOWS = new AtomicLong();

  private static final class Flow {
    int[] stack = new int[64];
    int depth;
    boolean hit;
    boolean callPending;
    /** While an effect is being caused, the stack depth from which frames run inside it; -1 when none is. */
    int open = -1;
  }

  private static final ThreadLocal<Flow> FLOW = ThreadLocal.withInitial(Flow::new);

  static synchronized int register(String key) {
    METHODS.add(key);
    return METHODS.size() - 1;
  }

  /** The id of a sink, by owner#name#desc. */
  static synchronized int sink(String key) {
    return SINK_IDS.computeIfAbsent(key, k -> {
      SINKS.add(k);
      return SINKS.size() - 1;
    });
  }

  public static void enter(int id) {
    Flow f = FLOW.get();
    if (f.depth == 0) {
      f.hit = false;
      f.open = -1;
      FLOWS.incrementAndGet();
    }
    f.callPending = false;
    if (f.depth == f.stack.length) f.stack = java.util.Arrays.copyOf(f.stack, f.depth * 2);
    f.stack[f.depth++] = id;
    if (id == BOUNDARY) return;
    EXECUTED.add(id);
  }

  public static void exit(int id) {
    Flow f = FLOW.get();
    // Pop through anything left behind by a constructor that threw.
    while (f.depth > 0 && f.stack[--f.depth] != id) { }
    if (f.open > f.depth) f.open = -1;
    if (f.depth == 0 && f.hit) {
      CRITICAL_FLOWS.incrementAndGet();
      f.hit = false;
    }
  }

  /** A sink is about to be called. */
  public static void hit(int sink) {
    Flow f = FLOW.get();
    f.callPending = true;
    if (inside(f)) return;
    record(f, sink);
    f.open = f.depth;
  }

  /** A call to a sink has returned. */
  public static void done(int sink) {
    Flow f = FLOW.get();
    if (f.open == f.depth) f.open = -1;
  }

  /** A sink with a body is being entered; not counted again if the call was just seen. */
  public static void hitEntry(int sink) {
    Flow f = FLOW.get();
    if (f.callPending) {
      f.callPending = false;
      return;
    }
    if (inside(f)) return;
    record(f, sink);
    // The sink's own frame is pushed next; what runs in it is inside the effect.
    f.open = f.depth + 1;
  }

  /**
   * Whether an effect is already being caused further up the stack. The map stops at the first
   * effect on a path, so one caused inside another (the EntityManager a repository's save uses) is
   * the same effect seen deeper, and is not recorded again.
   */
  private static boolean inside(Flow f) {
    return f.open >= 0 && f.depth >= f.open;
  }

  private static void record(Flow f, int sink) {
    HITS.incrementAndGet();
    f.hit = true;
    // Frames before a framework boundary dispatched to it (a test calling MockMvc, a servlet
    // container): no call in the code leads from them to the effect, so they are not on its way.
    int from = 0;
    for (int i = 0; i < f.depth; i++) if (f.stack[i] == BOUNDARY) from = i + 1;
    for (int i = from; i < f.depth; i++) REACHES.add(((long) f.stack[i] << 32) | sink);
  }

  static void dump(Path out) {
    List<String> methods;
    List<String> sinks;
    synchronized (Recorder.class) {
      methods = new ArrayList<>(METHODS);
      sinks = new ArrayList<>(SINKS);
    }
    try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
      w.println("skimit-trace 2");
      w.println("hits " + HITS.get());
      w.println("flows " + FLOWS.get());
      w.println("critical-flows " + CRITICAL_FLOWS.get());
      for (Integer id : EXECUTED) w.println("ran " + methods.get(id));
      for (long r : REACHES) w.println("reaches " + methods.get((int) (r >>> 32)) + " " + sinks.get((int) r));
    } catch (IOException e) {
      System.err.println("skimit-agent: could not write " + out + ": " + e);
    }
  }
}
