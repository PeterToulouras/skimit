package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the map against what a real run did. The agent records, for every effect the run caused,
 * which methods were on the call stack at that moment; like the map, it stops at the first effect,
 * so the EntityManager a repository's save uses is not an effect of its own. Two things must then hold: a way in on the
 * stack is one the map says can cause that effect, and any other method on the stack is one the
 * map has a path from. Anything else is a miss: the run did something the map says it cannot. A
 * clean result covers what the run exercised and no more.
 */
public final class Verifier {

  public record Miss(String who, Effect effect, String why) {}

  public final List<Miss> misses = new ArrayList<>();
  /** What the run did through a call that picks its target by name, which the map does not follow: not a miss, but said. */
  public final List<Miss> byName = new ArrayList<>();
  /** Effects the run caused, and the ways in it caused them from. */
  public final Set<String> effectsSeen = new TreeSet<>();
  public final Set<String> waysSeen = new TreeSet<>();
  public long hits;
  public long flows;
  public long criticalFlows;
  public int ran;
  /** Calls the trace names that this version of the code does not have, or that cause no known effect. */
  public int unknown;

  public Verifier(Behaviour b, List<Path> traces) throws IOException {
    List<String> lines = new ArrayList<>();
    for (Path trace : traces) {
      List<String> one = Files.readAllLines(trace);
      if (one.isEmpty() || !one.get(0).startsWith("skimit-trace")) throw new IOException(trace + " is not a skimit trace");
      if (!one.get(0).equals("skimit-trace 2")) {
        throw new IOException(trace + " was written by an older skimit-agent; record the run again with this release's agent");
      }
      lines.addAll(one);
    }
    Set<String> reported = new TreeSet<>();
    for (String line : lines) {
      String[] t = line.split(" ");
      switch (t[0]) {
        case "hits" -> hits += Long.parseLong(t[1]);
        case "flows" -> flows += Long.parseLong(t[1]);
        case "critical-flows" -> criticalFlows += Long.parseLong(t[1]);
        case "ran" -> ran++;
        case "reaches" -> {
          MethodKey m = key(t[1]);
          Effect e = b.effectFor(key(t[2]));
          if (e == null || !b.analysis.graph.methods.containsKey(m)) {
            unknown++;
            continue;
          }
          effectsSeen.add(e.toString());
          List<Behaviour.Entry> ways = b.entries.values().stream().filter(x -> x.method().equals(m)).toList();
          if (!ways.isEmpty()) {
            ways.forEach(w -> waysSeen.add(w.id()));
            if (ways.stream().noneMatch(w -> b.effects.get(w.id()).containsKey(e)) && reported.add(t[1] + e)) {
              (dispatchesByName(b, m) ? byName : misses).add(new Miss(ways.get(0).id(), e, "the map says this way in cannot cause it"));
            }
          } else if (!b.leadsTo.getOrDefault(e, Set.of()).contains(m) && reported.add(t[1] + e)) {
            (dispatchesByName(b, m) ? byName : misses).add(new Miss(m.pretty(), e, "the map has no path from this method to it"));
          }
        }
        default -> { }
      }
    }
  }

  private final java.util.Map<MethodKey, Boolean> byNameCache = new java.util.HashMap<>();

  /** Whether the method, or anything it calls, calls something by name: from there the map does not follow. */
  private boolean dispatchesByName(Behaviour b, MethodKey m) {
    return byNameCache.computeIfAbsent(m, k -> {
      CallGraph g = b.analysis.graph;
      java.util.Deque<CallGraph.Node> work = new java.util.ArrayDeque<>(g.nodesOf.getOrDefault(k, List.of()));
      Set<CallGraph.Node> seen = new java.util.HashSet<>(work);
      while (!work.isEmpty()) {
        for (CallGraph.Node n : g.calleesOf(work.poll())) {
          if (Policy.byName(n.method())) return true;
          if (seen.add(n)) work.add(n);
        }
      }
      return false;
    });
  }

  private static MethodKey key(String s) {
    String[] p = s.split("#", 3);
    return new MethodKey(p[0], p[1], p[2]);
  }

  public String report() {
    StringBuilder sb = new StringBuilder("## skimit verify\n\n");
    sb.append("The run caused an effect ").append(hits).append(hits == 1 ? " time" : " times").append(", in ").append(criticalFlows)
        .append(" of ").append(flows).append(" flows; ").append(ran).append(" application methods ran.\n\n");
    sb.append(effectsSeen.size()).append(effectsSeen.size() == 1 ? " effect" : " effects").append(" seen, from ")
        .append(waysSeen.size()).append(waysSeen.size() == 1 ? " way in.\n" : " ways in.\n");
    if (hits == 0) {
      sb.append("\nNothing the map knows as an effect happened, so nothing was checked. Run tests that write, delete or call out.\n");
    } else if (misses.isEmpty()) {
      sb.append("\n**No misses.** Everything the run did is in the map.\n");
    } else {
      sb.append("\n**").append(misses.size()).append(misses.size() == 1 ? " miss.** " : " misses.** ")
          .append("The run did these, and the map says it cannot:\n\n");
      for (Miss m : misses) sb.append("- `").append(m.who()).append("` ").append(m.effect()).append(": ").append(m.why()).append('\n');
    }
    if (!byName.isEmpty()) {
      sb.append("\n").append(byName.size()).append(byName.size() == 1 ? " thing" : " things")
          .append(" the run did went through reflection, which the map does not follow, so they are not misses:\n\n");
      for (Miss m : byName) sb.append("- `").append(m.who()).append("` ").append(m.effect()).append('\n');
    }
    if (unknown > 0) {
      sb.append("\n").append(unknown).append(unknown == 1 ? " recorded call does" : " recorded calls do")
          .append(" not match these classes; record a run of the same build you check.\n");
    }
    return sb.toString();
  }
}
