package dev.skimit.analyzer;

import dev.skimit.analyzer.Analysis.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks the static verdicts against what a real run did.
 *
 * <p>The agent records every flow that reached a sink. Two things must then hold:
 *
 * <ul>
 *   <li>a method that was on the call stack when a sink was called must be {@code ON_PATH}, unless
 *       the analysis already says it cannot follow a call that method makes;
 *   <li>a method that ran earlier in the same flow must be {@code ON_PATH} or {@code INFLUENCES},
 *       or {@code UNPROVEN}, which makes no claim.
 * </ul>
 *
 * Anything else is a miss: the run shows the method taking part in a critical flow and the
 * analysis said it did not. A clean result is evidence, not proof: it covers only what the run
 * exercised.
 */
public final class Verifier {

  public record Miss(MethodKey method, Verdict verdict, String observed, MethodKey flowRoot) {}

  public final List<Miss> misses = new ArrayList<>();
  /** Methods seen in critical flows, by verdict. */
  public final Map<Verdict, Integer> checked = new TreeMap<>();
  public final Set<MethodKey> ranAfterSinkIsolated = new TreeSet<>();
  public long hits;
  public long flows;
  public long criticalFlows;
  public int ran;
  public int unknown;

  public Verifier(Analysis a, List<Path> traces) throws IOException {
    Set<MethodKey> seen = new TreeSet<>();
    Set<String> missed = new TreeSet<>();
    Set<String> ranKeys = new TreeSet<>();
    List<String> lines = new ArrayList<>();
    for (Path trace : traces) {
      List<String> one = Files.readAllLines(trace);
      if (one.isEmpty() || !one.get(0).startsWith("skimit-trace")) throw new IOException(trace + " is not a skimit trace");
      lines.addAll(one);
    }
    for (Verdict v : Verdict.values()) checked.put(v, 0);
    for (String line : lines) {
      String[] t = line.split(" ");
      switch (t[0]) {
        case "hits" -> hits += Long.parseLong(t[1]);
        case "flows" -> flows += Long.parseLong(t[1]);
        case "critical-flows" -> criticalFlows += Long.parseLong(t[1]);
        case "ran" -> { if (ranKeys.add(t[1])) ran++; }
        case "stack", "before", "after" -> {
          MethodKey m = key(t[1]);
          if (!a.verdicts.containsKey(m)) {
            unknown++;
            continue;
          }
          Verdict v = a.verdict(m);
          MethodKey root = t.length > 2 ? key(t[2]) : null;
          if (t[0].equals("after")) {
            if (v == Verdict.ISOLATED) ranAfterSinkIsolated.add(m);
            continue;
          }
          if (seen.add(m)) checked.merge(v, 1, Integer::sum);
          // A method that can reach an opaque call is one the analysis already says it cannot
          // follow; being found on the stack there contradicts nothing.
          if (t[0].equals("stack") && (v == Verdict.ISOLATED || (v == Verdict.INFLUENCES && !a.reachesOpaque(m)))
              && missed.add(t[1])) {
            misses.add(new Miss(m, v, "was on the call stack when a sink was called", root));
          } else if (t[0].equals("before") && v == Verdict.ISOLATED && missed.add(t[1])) {
            misses.add(new Miss(m, v, "ran before a sink was called, in the same flow", root));
          }
        }
        default -> { }
      }
    }
    misses.sort((x, y) -> x.method().compareTo(y.method()));
  }

  private static MethodKey key(String s) {
    String[] p = s.split("#", 3);
    return new MethodKey(p[0], p[1], p[2]);
  }

  public String report() {
    StringBuilder sb = new StringBuilder("# skimit verify\n\n");
    sb.append("The run called a sink ").append(hits).append(" times, in ").append(criticalFlows).append(" of ")
        .append(flows).append(" flows. ").append(ran).append(" application methods ran.\n\n");
    int total = checked.values().stream().mapToInt(Integer::intValue).sum();
    sb.append(total).append(" methods took part in a flow up to the point it reached a sink:\n\n");
    for (Map.Entry<Verdict, Integer> e : checked.entrySet()) {
      sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
    }
    if (hits == 0) {
      sb.append("\nNo sink was called, so nothing was checked. Run tests that exercise the critical code.\n");
    } else if (misses.isEmpty()) {
      sb.append("\n**No misses.** Every one of them was flagged, or the analysis made no claim about it.\n");
    } else {
      sb.append("\n**").append(misses.size()).append(" misses.** The run shows these taking part in a critical flow:\n\n");
      for (Miss m : misses) {
        sb.append("- `").append(m.method().pretty()).append("` is ").append(m.verdict()).append(" but ").append(m.observed());
        if (m.flowRoot() != null && !m.flowRoot().equals(m.method())) sb.append(" (flow started at `").append(m.flowRoot().pretty()).append("`)");
        sb.append('\n');
      }
    }
    if (!ranAfterSinkIsolated.isEmpty()) {
      sb.append("\nFor information: ").append(ranAfterSinkIsolated.size())
          .append(" isolated methods ran only after the sink had been called, in the same flow. They could not have")
          .append(" changed what the sink was asked to do, though an exception there could still undo it.\n");
    }
    return sb.toString();
  }
}
