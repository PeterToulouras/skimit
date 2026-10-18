package dev.skimit.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class Main {
  private static final String USAGE = """
      skimit: what your code does, and what a change did to it, read from bytecode

      Usage:
        skimit map <classes-dir|jar>... [--policy FILE] [--json FILE] [--html FILE] [--agent-config FILE]
        skimit diff --base <classes-dir|jar>... --head <classes-dir|jar>...
                      [--policy FILE] [--json FILE] [--html FILE] [--diff FILE] [--exit-code]
        skimit verify <classes-dir|jar>... --trace FILE [--policy FILE]

      map     the ways into the program (routes, jobs, listeners, threads) and the effects each
              can cause: data written or deleted, services called, messages sent
      diff    what a change did to that: new effects, new ways in, broken rules, and how it
              changed something a way in already did
      verify  checks the map against a real run recorded by skimit-agent.jar

      Options:
        --policy FILE     sinks and rules (see docs/usage.md)
        --no-default-sinks  leave out the built-in rule (data writes through repositories, mappers,
                          DAOs and database clients); only declared sinks, calls out and messages count
        --json FILE       also write the result as JSON
        --html FILE       also write it as one HTML page
        --diff FILE       diff: the source diff (git diff base head), so the page can show the
                          code of a changed method on request; results never depend on it
        --exit-code       diff: exit 1 when the change breaks a rule or needs a person under one
        --agent-config F  map: write the configuration for skimit-agent.jar
        --trace FILE      verify: a trace skimit-agent.jar wrote (repeat for several); exits 1 if
                          the run did something the map says it cannot
      """;

  public static void main(String[] args) {
    // The output is Markdown with arrows in it; write it as UTF-8 whatever the locale says.
    System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
        java.nio.charset.StandardCharsets.UTF_8));
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
    Path sourceDiff = null;
    Path agentConfig = null;
    List<Path> traces = new ArrayList<>();
    boolean exitCode = false;
    boolean defaultSinks = true;
    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--base" -> current = base;
        case "--head" -> current = head;
        case "--policy" -> policyFile = Path.of(value(args, ++i));
        case "--json" -> json = Path.of(value(args, ++i));
        case "--html" -> html = Path.of(value(args, ++i));
        case "--diff" -> sourceDiff = Path.of(value(args, ++i));
        case "--agent-config" -> agentConfig = Path.of(value(args, ++i));
        case "--trace" -> traces.add(Path.of(value(args, ++i)));
        case "--exit-code" -> exitCode = true;
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
      case "map" -> {
        if (inputs.isEmpty()) throw new IllegalArgumentException("map needs at least one class directory or jar");
        Behaviour b = new Behaviour(analyze(inputs, policy));
        System.out.print(Output.map(b));
        if (json != null) Files.writeString(json, Output.json(b));
        if (html != null) Files.writeString(html, Output.page(b, null, null));
        if (agentConfig != null) Files.writeString(agentConfig, agentConfig(b));
        return 0;
      }
      case "diff" -> {
        if (base.isEmpty() || head.isEmpty()) throw new IllegalArgumentException("diff needs --base and --head");
        Analysis before = new Analysis(Program.load(base), policy);
        Analysis after = analyze(head, policy);
        Differ d = new Differ(before, after);
        if (sourceDiff != null) d.attach(SourceDiff.parse(Files.readString(sourceDiff)), before, after);
        Behaviour now = new Behaviour(after);
        Feed f = new Feed(new Behaviour(before), now, d, policy.rules);
        System.out.print(Output.feed(f, now));
        if (json != null) Files.writeString(json, Output.json(f, d));
        if (html != null) Files.writeString(html, Output.page(now, f, d));
        return exitCode && f.broken() > 0 ? 1 : 0;
      }
      case "verify" -> {
        if (inputs.isEmpty() || traces.isEmpty()) throw new IllegalArgumentException("verify needs classes and --trace FILE");
        Verifier v = new Verifier(new Behaviour(analyze(inputs, policy)), traces);
        System.out.print(v.report());
        return v.misses.isEmpty() && v.hits > 0 ? 0 : 1;
      }
      default -> throw new IllegalArgumentException("unknown command " + args[0] + " (try --help)");
    }
  }

  /** Loads and reads the classes, warning when nothing in them changes the world. */
  private static Analysis analyze(List<Path> inputs, Policy policy) throws IOException {
    Analysis a = new Analysis(Program.load(inputs), policy);
    if (a.sinks.isEmpty()) {
      System.err.println("skimit: warning: found no data writes and no declared sinks, so only calls out and messages"
          + " count as effects. Declare what changes the world with a sink line in --policy or @Critical.");
    }
    return a;
  }

  /** What the agent needs: the classes to instrument, the calls that cause effects, and where a flow begins. */
  private static String agentConfig(Behaviour b) {
    StringBuilder sb = new StringBuilder();
    for (String cls : b.analysis.graph.program.classes.keySet()) sb.append("class ").append(cls).append('\n');
    Set<String> calls = new TreeSet<>();
    for (MethodKey s : b.effectCalls()) {
      calls.add(s.owner() + "#" + s.name() + "#" + s.desc());
      // A call written against a subtype that inherits the method is still a call to it.
      for (String sub : b.analysis.graph.subtypesOf(s.owner())) calls.add(sub + "#" + s.name() + "#" + s.desc());
    }
    for (String s : calls) sb.append("sink ").append(s).append('\n');
    // One request is one flow, whatever the framework calls along the way.
    for (String boundary : new String[] {
        "org/springframework/web/servlet/DispatcherServlet#doDispatch",
        "jakarta/servlet/http/HttpServlet#service", "javax/servlet/http/HttpServlet#service"}) {
      sb.append("boundary ").append(boundary).append('\n');
    }
    return sb.toString();
  }

  private static String value(String[] args, int i) {
    if (i >= args.length) throw new IllegalArgumentException(args[i - 1] + " needs a value");
    return args[i];
  }
}
