package dev.skimit.analyzer;

import dev.skimit.analyzer.Differ.Bucket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {
  private static final String USAGE = """
      skimit: deterministic review manifests from bytecode

      Usage:
        skimit analyze <classes-dir|jar>... [--policy FILE] [--json FILE] [--html FILE] [--list]
        skimit diff --base <classes-dir|jar>... --head <classes-dir|jar>...
                      [--policy FILE] [--json FILE] [--html FILE] [--exit-code]
        skimit verify <classes-dir|jar>... --trace FILE [--policy FILE]

      Options:
        --policy FILE     your sinks, opaque calls and stores (see docs/usage.md)
        --no-default-sinks  do not fall back on the built-in rule (every write through a
                          repository, mapper or DAO is critical) when no sink is declared
        --root-ratio R    share of classes that must depend on a class to call it ROOT (default 0.10)
        --json FILE       also write the result as JSON
        --html FILE       also write an interactive map of the result as one HTML page
        --list            analyze: print every method with its verdict and witness
        --exit-code       diff: exit 1 when anything lands in "must review"
        --agent-config F  analyze: write the configuration for skimit-agent.jar, which records a
                          real run (your tests, say) for `skimit verify`
        --trace FILE      verify: a trace skimit-agent.jar wrote (repeat for several); exits 1 if
                          the run contradicts a verdict
        --calls-only      experimental: spread influence along calls only (flags less, misses
                          influence through shared state and data types)
      """;

  public static void main(String[] args) {
    try {
      System.exit(run(args));
    } catch (IOException | IllegalArgumentException e) {
      System.err.println("skimit: " + e.getMessage());
      System.exit(2);
    }
  }

  static int run(String[] args) throws IOException {
    if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
      System.out.print(USAGE);
      return args.length == 0 ? 2 : 0;
    }
    List<Path> inputs = new ArrayList<>();
    List<Path> base = new ArrayList<>();
    List<Path> head = new ArrayList<>();
    List<Path> current = inputs;
    Path policyFile = null;
    Path json = null;
    Path html = null;
    Path agentConfig = null;
    List<Path> traces = new ArrayList<>();
    double rootRatio = 0.10;
    boolean list = false;
    boolean exitCode = false;
    boolean callsOnly = false;
    boolean defaultSinks = true;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--base" -> current = base;
        case "--head" -> current = head;
        case "--policy" -> policyFile = Path.of(value(args, ++i));
        case "--json" -> json = Path.of(value(args, ++i));
        case "--html" -> html = Path.of(value(args, ++i));
        case "--agent-config" -> agentConfig = Path.of(value(args, ++i));
        case "--trace" -> traces.add(Path.of(value(args, ++i)));
        case "--root-ratio" -> rootRatio = Double.parseDouble(value(args, ++i));
        case "--list" -> list = true;
        case "--exit-code" -> exitCode = true;
        case "--calls-only" -> callsOnly = true;
        case "--no-default-sinks" -> defaultSinks = false;
        default -> {
          if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
          current.add(Path.of(args[i]));
        }
      }
    }
    Policy policy = policyFile == null ? Policy.defaults() : Policy.load(policyFile);
    policy.defaultSinks = defaultSinks;
    switch (args[0]) {
      case "analyze" -> {
        if (inputs.isEmpty()) throw new IllegalArgumentException("analyze needs at least one class directory or jar");
        Analysis a = new Analysis(Program.load(inputs), policy, rootRatio, callsOnly);
        warnIfNoSinks(a);
        System.out.print(Report.summary(a, list));
        if (json != null) Files.writeString(json, Report.json(a));
        if (html != null) Files.writeString(html, Report.html(null, a, null));
        if (agentConfig != null) Files.writeString(agentConfig, agentConfig(a));
        return 0;
      }
      case "verify" -> {
        if (inputs.isEmpty() || traces.isEmpty()) throw new IllegalArgumentException("verify needs classes and --trace FILE");
        Analysis a = new Analysis(Program.load(inputs), policy, rootRatio, callsOnly);
        warnIfNoSinks(a);
        Verifier v = new Verifier(a, traces);
        System.out.print(v.report());
        return v.misses.isEmpty() && v.hits > 0 ? 0 : 1;
      }
      case "diff" -> {
        if (base.isEmpty() || head.isEmpty()) throw new IllegalArgumentException("diff needs --base and --head");
        Analysis before = new Analysis(Program.load(base), policy, rootRatio, callsOnly);
        Analysis after = new Analysis(Program.load(head), policy, rootRatio, callsOnly);
        Differ d = new Differ(before, after);
        warnIfNoSinks(after);
        System.out.print(Report.markdown(d));
        if (json != null) Files.writeString(json, Report.json(d));
        if (html != null) Files.writeString(html, Report.html(before, after, d));
        return exitCode && (d.count(Bucket.MUST) > 0 || !d.policy.isEmpty()) ? 1 : 0;
      }
      default -> throw new IllegalArgumentException("unknown command " + args[0] + " (try --help)");
    }
  }

  /** What the agent needs: the classes to instrument, the sinks, and where a flow begins. */
  private static String agentConfig(Analysis a) {
    StringBuilder sb = new StringBuilder();
    for (String cls : a.graph.program.classes.keySet()) sb.append("class ").append(cls).append('\n');
    java.util.Set<String> sinks = new java.util.TreeSet<>();
    for (MethodKey s : a.sinks.keySet()) {
      sinks.add(s.owner() + "#" + s.name() + "#" + s.desc());
      // A call written against a subtype that inherits the sink is still a call to the sink.
      for (String sub : a.graph.subtypesOf(s.owner())) sinks.add(sub + "#" + s.name() + "#" + s.desc());
    }
    for (String s : sinks) sb.append("sink ").append(s).append('\n');
    // One request is one flow, whatever the framework calls along the way.
    for (String b : new String[] {
        "org/springframework/web/servlet/DispatcherServlet#doDispatch",
        "jakarta/servlet/http/HttpServlet#service", "javax/servlet/http/HttpServlet#service"}) {
      sb.append("boundary ").append(b).append('\n');
    }
    return sb.toString();
  }

  /** Without sinks every method is isolated, which reads as "all clear" and means nothing. */
  private static void warnIfNoSinks(Analysis a) {
    if (a.sinks.isEmpty()) {
      System.err.println("skimit: warning: no sinks found, so nothing is critical and every method is isolated."
          + " Declare what is critical with --policy or @Critical.");
    }
  }

  private static String value(String[] args, int i) {
    if (i >= args.length) throw new IllegalArgumentException(args[i - 1] + " needs a value");
    return args[i];
  }
}
