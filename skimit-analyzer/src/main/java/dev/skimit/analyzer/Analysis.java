package dev.skimit.analyzer;

import dev.skimit.analyzer.CallGraph.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The derived facts for one version of the program.
 *
 * <p>Every application method with a body gets exactly one verdict, and every verdict other than
 * {@link Verdict#ISOLATED} comes with a witness: a concrete chain that justifies it. A method that
 * runs on several concrete classes takes the strongest verdict among them.
 */
public final class Analysis {

  public enum Verdict {
    /** Can reach a critical sink. A change here can change what the sink is asked to do. */
    ON_PATH,
    /**
     * Takes part in a critical flow without reaching the sink itself: it is called by an on-path
     * method, shares object state with one, or belongs to a data type one handles.
     */
    INFLUENCES,
    /**
     * No claim is made: it can reach a call the analysis cannot follow, or a framework calls it
     * at a time the analysis cannot see.
     */
    UNPROVEN,
    /** Nothing above applies: no chain of calls, shared state or handled types leads to a sink. */
    ISOLATED
  }

  /** How one step of a witness follows from the previous one. */
  public enum Link {
    CALLS("calls"),
    READS_FIELD("reads a field written by"),
    HANDLES("handles the type of"),
    WIRED_BY("is wired up by"),
    JOINED_BY("runs alongside"),
    READS_STORE("reads a store written by");

    public final String words;

    Link(String words) {
      this.words = words;
    }
  }

  public enum Position { ROOT, TRUNK, LEAF }

  public static final String CRITICAL_DESC = "Ldev/skimit/Critical;";

  public final CallGraph graph;
  public final double rootRatio;
  /** Sink method to its domains. May include library methods named by policy rules. */
  public final Map<MethodKey, Set<String>> sinks = new TreeMap<>();
  public final Map<MethodKey, Verdict> verdicts = new TreeMap<>();
  public final Map<MethodKey, String> hashes = new TreeMap<>();
  /** Top-level class to its position and its number of transitive dependents. */
  public final Map<String, Position> positions = new TreeMap<>();
  public final Map<String, Integer> dependents = new TreeMap<>();

  private final boolean callsOnly;
  private final Map<String, Map<Node, Node>> towardSink = new TreeMap<>();
  private final Set<Node> onPath = new HashSet<>();
  private final Map<Node, Node> influencedBy = new HashMap<>();
  private final Map<Node, Link> influenceLink = new HashMap<>();
  private final Map<Node, Node> towardOpaque = new HashMap<>();
  private final Map<Node, Node> frameworkInvoked = new HashMap<>();
  private final Set<String> sharedGroups = new HashSet<>();
  private final Set<String> joined = new HashSet<>();
  private final Map<Node, String> calledBecause = new HashMap<>();
  private final Map<String, List<Node>> byReceiver = new HashMap<>();
  private final Map<String, List<Node>> unboundByOwner = new HashMap<>();
  private final Map<String, List<Node>> allByOwner = new HashMap<>();
  /** The node that justifies each method's verdict. */
  private final Map<MethodKey, Node> chosen = new HashMap<>();

  public Analysis(Program program, Policy policy, double rootRatio) {
    this(program, policy, rootRatio, false);
  }

  /**
   * @param callsOnly spread influence along calls only, ignoring shared object state and handled
   *     types. Flags far less and misses real influence; meant for comparing the two.
   */
  public Analysis(Program program, Policy policy, double rootRatio, boolean callsOnly) {
    this.callsOnly = callsOnly;
    this.graph = new CallGraph(program, policy);
    this.rootRatio = rootRatio;
    for (List<Node> nodes : graph.nodesOf.values()) {
      for (Node n : nodes) {
        allByOwner.computeIfAbsent(n.method().owner(), k -> new ArrayList<>()).add(n);
        if (n.receiver() != null) byReceiver.computeIfAbsent(n.receiver(), k -> new ArrayList<>()).add(n);
        else unboundByOwner.computeIfAbsent(n.method().owner(), k -> new ArrayList<>()).add(n);
      }
    }
    findSinks(policy);
    propagate();
    for (MethodKey m : graph.methods.keySet()) hashes.put(m, graph.bodyHash(m));
    computePositions();
  }

  public Verdict verdict(MethodKey m) {
    return verdicts.getOrDefault(m, Verdict.ISOLATED);
  }

  /** Domains whose sinks this method can reach, or whose flows it takes part in. */
  public Set<String> domains(MethodKey m) {
    Set<String> out = new TreeSet<>();
    List<Node> anchors = new ArrayList<>();
    if (verdict(m) == Verdict.ON_PATH) {
      anchors.addAll(graph.nodesOf.getOrDefault(m, List.of(new Node(m, null))));
    } else if (verdict(m) == Verdict.INFLUENCES) {
      anchors.add(anchor(chosen.get(m)));
    }
    for (Map.Entry<String, Map<Node, Node>> e : towardSink.entrySet()) {
      for (Node a : anchors) if (e.getValue().containsKey(a)) out.add(e.getKey());
    }
    return out;
  }

  /** The chain that justifies the verdict; empty for isolated methods. */
  public List<MethodKey> witness(MethodKey m) {
    return path(m).stream().map(Node::method).toList();
  }

  /** For each step of the witness after the first, the words that connect it to the previous step. */
  public List<String> links(MethodKey m) {
    List<Node> path = path(m);
    boolean influence = verdict(m) == Verdict.INFLUENCES;
    List<String> out = new ArrayList<>();
    for (int i = 1; i < path.size(); i++) {
      out.add((influence ? influenceLink.getOrDefault(path.get(i), Link.CALLS) : Link.CALLS).words);
    }
    return out;
  }

  /** The witness as a sentence, naming how each step follows from the one before. */
  public String explain(MethodKey m) {
    List<Node> path = path(m);
    if (path.size() < 2) return "";
    List<String> links = links(m);
    StringBuilder sb = new StringBuilder("`" + path.get(0).pretty() + "`");
    for (int i = 1; i < path.size(); i++) {
      sb.append(' ').append(links.get(i - 1)).append(" `").append(path.get(i).pretty()).append('`');
    }
    return sb.toString();
  }

  /** True when the method can reach a call the analysis cannot follow, whatever its verdict. */
  public boolean reachesOpaque(MethodKey m) {
    for (Node n : graph.nodesOf.getOrDefault(m, List.of())) if (towardOpaque.containsKey(n)) return true;
    return false;
  }

  /** Why the method got its verdict, in one clause. */
  public String reason(MethodKey m) {
    Node n = chosen.get(m);
    switch (verdict(m)) {
      case ON_PATH:
        return sinks.containsKey(m) ? "is a critical sink" : "can reach a critical sink";
      case INFLUENCES:
        String from = influencedBy.get(n).pretty();
        return switch (influenceLink.get(n)) {
          case CALLS -> "is called within a critical flow that starts at " + anchor(n).pretty();
          case READS_FIELD -> "writes a field read by " + from + ", which is part of a critical flow";
          case HANDLES -> "belongs to a type that " + from + " handles on a critical path";
          case WIRED_BY -> "creates the reference through which " + from + " is called on a critical path";
          case READS_STORE -> "writes to a store that " + from + " reads, and that method is part of a critical flow";
          case JOINED_BY -> "is run by the framework (" + graph.classParticipants.get(m) + ") alongside " + from
              + ", which is on a critical path";
        };
      case UNPROVEN:
        List<Node> w = path(m);
        if (towardOpaque.containsKey(n)) {
          return "can reach a call the analysis cannot follow: "
              + String.join(", ", graph.opaqueCalls.getOrDefault(w.get(w.size() - 1), Set.of()));
        }
        Node entry = w.get(0);
        String how = calledBecause.get(entry);
        if (how == null) {
          String cls = entry.receiver() != null ? entry.receiver() : entry.method().owner();
          String lib = graph.shape(cls).libraryParent();
          how = lib.substring(lib.lastIndexOf('/') + 1);
        }
        return "runs when a framework or library calls " + entry.method().pretty() + " through "
            + how + ", at a time the analysis cannot see";
      default:
        return "nothing connects it to any sink";
    }
  }

  // ---------------------------------------------------------------------------------------------

  private Node anchor(Node n) {
    while (influencedBy.containsKey(n)) n = influencedBy.get(n);
    return n;
  }

  private List<Node> path(MethodKey m) {
    List<Node> path = new ArrayList<>();
    Node n = chosen.get(m);
    if (n == null) return path;
    switch (verdict(m)) {
      case ON_PATH -> {
        for (Map<Node, Node> next : towardSink.values()) {
          if (!next.containsKey(n)) continue;
          for (Node c = n; c != null; c = next.get(c)) path.add(c);
          break;
        }
      }
      case INFLUENCES -> {
        for (Node c = n; c != null; c = influencedBy.get(c)) path.add(c);
        Collections.reverse(path);
      }
      case UNPROVEN -> {
        if (towardOpaque.containsKey(n)) {
          for (Node c = n; c != null; c = towardOpaque.get(c)) path.add(c);
        } else {
          for (Node c = n; c != null; c = frameworkInvoked.get(c)) path.add(c);
          Collections.reverse(path);
        }
      }
      default -> { }
    }
    return path;
  }

  private void findSinks(Policy policy) {
    for (ClassNode cn : graph.program.classes.values()) {
      Set<String> onClass = criticalDomains(cn.invisibleAnnotations, cn.visibleAnnotations);
      for (MethodNode mn : cn.methods) {
        Set<String> d = new TreeSet<>(onClass);
        d.addAll(criticalDomains(mn.invisibleAnnotations, mn.visibleAnnotations));
        if (!d.isEmpty()) addSink(new MethodKey(cn.name, mn.name, mn.desc), d);
      }
    }
    Set<MethodKey> candidates = new HashSet<>(graph.methods.keySet());
    candidates.addAll(graph.targets);
    for (MethodKey m : candidates) {
      for (Policy.Rule r : policy.sinks) {
        if (r.matches(m.owner(), m.name())) addSink(m, Set.of(r.domain()));
      }
    }
    // An override of a sink is a sink: a call through the supertype may land on it.
    for (Map.Entry<MethodKey, Set<String>> e : new ArrayList<>(sinks.entrySet())) {
      MethodKey s = e.getKey();
      if (s.name().startsWith("<")) continue;
      for (String sub : graph.subtypesOf(s.owner())) {
        MethodKey o = new MethodKey(sub, s.name(), s.desc());
        MethodNode mn = graph.methods.get(o);
        if (mn != null && (mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) addSink(o, e.getValue());
      }
    }
  }

  private void addSink(MethodKey m, Set<String> domains) {
    sinks.computeIfAbsent(m, k -> new TreeSet<>()).addAll(domains);
  }

  @SafeVarargs
  private static Set<String> criticalDomains(List<AnnotationNode>... lists) {
    Set<String> out = new TreeSet<>();
    for (List<AnnotationNode> list : lists) {
      if (list == null) continue;
      for (AnnotationNode an : list) {
        if (!an.desc.equals(CRITICAL_DESC)) continue;
        boolean any = false;
        if (an.values != null) {
          for (int i = 0; i + 1 < an.values.size(); i += 2) {
            if (an.values.get(i).equals("value") && an.values.get(i + 1) instanceof List<?> vals) {
              for (Object v : vals) {
                out.add(String.valueOf(v));
                any = true;
              }
            }
          }
        }
        if (!any) out.add("CRITICAL");
      }
    }
    return out;
  }

  private void propagate() {
    // 1. Backward from the sinks, one domain at a time, remembering the next hop toward the sink.
    Set<String> allDomains = new TreeSet<>();
    sinks.values().forEach(allDomains::addAll);
    for (String domain : allDomains) {
      Map<Node, Node> next = new HashMap<>();
      Deque<Node> work = new ArrayDeque<>();
      for (Map.Entry<MethodKey, Set<String>> e : sinks.entrySet()) {
        if (!e.getValue().contains(domain)) continue;
        List<Node> nodes = new ArrayList<>(graph.nodesOf.getOrDefault(e.getKey(), List.of()));
        nodes.add(new Node(e.getKey(), null));
        for (Node n : nodes) {
          if (next.containsKey(n)) continue;
          next.put(n, null);
          work.add(n);
        }
      }
      while (!work.isEmpty()) {
        Node n = work.poll();
        for (Node caller : graph.callersOf(n)) {
          if (!next.containsKey(caller)) {
            next.put(caller, n);
            work.add(caller);
          }
        }
      }
      towardSink.put(domain, next);
      onPath.addAll(next.keySet());
    }

    // 2. Spread influence from everything on a path until nothing new is found:
    //    - what a flagged method calls can feed it a value or throw into it. At a call that can
    //      land on several methods, one of them on a path, the others are alternatives to the
    //      critical branch, not part of it, and are left alone;
    //    - whatever writes a field or a store a flagged method reads decides what it sees;
    //    - the types handled by a sink and by its direct callers are the data that reaches it;
    //    - whatever creates the lambda or method reference an on-path method is called through
    //      decides how it is wired in.
    Deque<Node> work = new ArrayDeque<>(new TreeSet<>(onPath));
    while (!work.isEmpty()) {
      Node n = work.poll();
      boolean critical = onPath.contains(n);
      for (Set<Node> site : graph.sitesOf(n)) {
        if (critical && site.stream().anyMatch(onPath::contains)) continue;
        for (Node callee : site) influence(callee, n, Link.CALLS, work);
      }
      if (callsOnly) continue;
      for (String read : graph.fieldReads.getOrDefault(n, Set.of())) {
        Link link = read.startsWith("store:") ? Link.READS_STORE : Link.READS_FIELD;
        for (Node writer : graph.writersOf(read)) influence(writer, n, link, work);
      }
      if (!critical) continue;
      for (Node creator : graph.creators.getOrDefault(n, Set.of())) influence(creator, n, Link.WIRED_BY, work);
      // What the framework runs on the same object around this method: model attributes and
      // binders before a request handler, lifecycle callbacks, injected setters.
      List<Node> siblings = n.receiver() != null ? byReceiver.get(n.receiver()) : allByOwner.get(n.method().owner());
      if (siblings != null && joined.add(n.receiver() != null ? n.receiver() : n.method().owner())) {
        for (Node sib : siblings) {
          if (graph.classParticipants.containsKey(sib.method())) influence(sib, n, Link.JOINED_BY, work);
        }
      }
      // Only where the sink's arguments are put together: the sink itself and its direct
      // callers. Methods further up a path pass far more through their hands than reaches it.
      boolean atSink = sinks.containsKey(n.method())
          || graph.calleesOf(n).stream().anyMatch(c -> sinks.containsKey(c.method()));
      String owner = n.method().owner();
      if (!atSink || !graph.program.classes.containsKey(owner)) continue;
      String self = n.receiver() != null ? n.receiver() : owner;
      Set<String> ownHierarchy = new HashSet<>(graph.shape(self).superclasses());
      ownHierarchy.add(self);
      // The data that reaches the sink is the handled objects and everything they hold.
      Deque<String> types = new ArrayDeque<>(graph.dataTypes.getOrDefault(n.method(), Set.of()));
      Set<String> seenTypes = new HashSet<>(ownHierarchy);
      while (!types.isEmpty()) {
        String type = types.poll();
        if (!seenTypes.add(type)) continue;
        List<String> receivers = graph.receivers(type);
        for (String r : receivers) {
          shareObject(r, n, Link.HANDLES, work);
          types.addAll(graph.fieldTypes(r));
          types.addAll(graph.shape(r).superclasses());
        }
        if (receivers.isEmpty()) shareGroup("owner:" + type, allByOwner.get(type), n, Link.HANDLES, work);
        shareStatics(type, n, Link.HANDLES, work);
        types.addAll(graph.fieldTypes(type));
      }
    }

    // 3. Backward from opaque calls.
    work = new ArrayDeque<>(graph.opaqueCalls.keySet());
    for (Node n : work) towardOpaque.put(n, null);
    while (!work.isEmpty()) {
      Node n = work.poll();
      for (Node caller : graph.callersOf(n)) {
        if (!towardOpaque.containsKey(caller)) {
          towardOpaque.put(caller, n);
          work.add(caller);
        }
      }
    }

    // 4. Forward from classes that extend a library type but that no application code ever
    //    constructs, or that carry a stereotype annotation (filters, formatters, configurers,
    //    components): a framework builds them and calls them, possibly in the middle of a
    //    critical flow.
    work = new ArrayDeque<>();
    for (String cls : graph.program.classes.keySet()) {
      if (graph.instantiated.contains(cls) && !graph.managed(cls)) continue;
      String receiver = graph.concrete(cls) ? cls : null;
      Node first = null;
      for (MethodKey cb : graph.shape(cls).callbacks()) {
        if (receiver == null && !cb.owner().equals(cls)) continue;
        Node n = new Node(cb, receiver);
        if (!graph.nodesOf.getOrDefault(cb, List.of()).contains(n) || frameworkInvoked.containsKey(n)) continue;
        // An entry point that is itself on a critical path is already accounted for: the
        // analysis has followed it, and what it calls is judged by the influence rules.
        if (onPath.contains(n)) continue;
        frameworkInvoked.put(n, null);
        work.add(n);
        if (first == null) first = n;
      }
      if (first == null) continue;
      // Its constructors set up the state those callbacks run on.
      for (MethodNode mn : graph.program.classes.get(cls).methods) {
        Node init = new Node(new MethodKey(cls, mn.name, mn.desc), mn.name.equals("<init>") ? receiver : null);
        if (mn.name.startsWith("<") && !frameworkInvoked.containsKey(init) && !onPath.contains(init)) {
          frameworkInvoked.put(init, first);
          work.add(init);
        }
      }
    }
    for (Map.Entry<MethodKey, String> e : graph.globalParticipants.entrySet()) {
      for (Node n : graph.nodesOf.getOrDefault(e.getKey(), List.of())) {
        if (frameworkInvoked.containsKey(n) || onPath.contains(n)) continue;
        frameworkInvoked.put(n, null);
        calledBecause.put(n, e.getValue());
        work.add(n);
      }
    }
    while (!work.isEmpty()) {
      Node n = work.poll();
      for (Node callee : graph.calleesOf(n)) {
        if (!frameworkInvoked.containsKey(callee)) {
          frameworkInvoked.put(callee, n);
          work.add(callee);
        }
      }
    }

    // A method takes the strongest verdict among the receivers it runs on.
    for (Map.Entry<MethodKey, List<Node>> e : graph.nodesOf.entrySet()) {
      Verdict best = Verdict.ISOLATED;
      Node pick = e.getValue().get(0);
      for (Node n : e.getValue()) {
        Verdict v = onPath.contains(n) ? Verdict.ON_PATH
            : influencedBy.containsKey(n) ? Verdict.INFLUENCES
            : towardOpaque.containsKey(n) || frameworkInvoked.containsKey(n) ? Verdict.UNPROVEN
            : Verdict.ISOLATED;
        if (v.ordinal() < best.ordinal()) {
          best = v;
          pick = n;
        }
      }
      verdicts.put(e.getKey(), best);
      chosen.put(e.getKey(), pick);
    }
  }

  private void influence(Node target, Node from, Link link, Deque<Node> work) {
    if (onPath.contains(target) || influencedBy.containsKey(target)) return;
    if (!graph.nodesOf.containsKey(target.method())) return;
    influencedBy.put(target, from);
    influenceLink.put(target, link);
    work.add(target);
  }

  /** Everything that runs on an object of this exact class, plus the static state it can see. */
  private void shareObject(String receiver, Node from, Link link, Deque<Node> work) {
    shareGroup("object:" + receiver, byReceiver.get(receiver), from, link, work);
    shareStatics(receiver, from, link, work);
  }

  private void shareStatics(String cls, Node from, Link link, Deque<Node> work) {
    List<String> all = new ArrayList<>(List.of(cls));
    all.addAll(graph.shape(cls).superclasses());
    for (String c : all) shareGroup("static:" + c, unboundByOwner.get(c), from, link, work);
  }

  private void shareGroup(String id, List<Node> members, Node from, Link link, Deque<Node> work) {
    if (members == null || !sharedGroups.add(id)) return;
    for (Node n : members) influence(n, from, link, work);
  }

  private void computePositions() {
    Map<String, Set<String>> reverse = new HashMap<>();
    for (Map.Entry<String, Set<String>> e : graph.classDeps.entrySet()) {
      for (String dep : e.getValue()) reverse.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
    }
    int n = graph.classDeps.size();
    int rootAt = Math.max(3, (int) Math.ceil(n * rootRatio));
    for (String cls : graph.classDeps.keySet()) {
      Set<String> seen = new HashSet<>();
      Deque<String> work = new ArrayDeque<>(reverse.getOrDefault(cls, Set.of()));
      while (!work.isEmpty()) {
        String c = work.poll();
        if (!c.equals(cls) && seen.add(c)) work.addAll(reverse.getOrDefault(c, Set.of()));
      }
      dependents.put(cls, seen.size());
      positions.put(cls, seen.isEmpty() ? Position.LEAF : seen.size() >= rootAt ? Position.ROOT : Position.TRUNK);
    }
  }

  /** Method counts and instruction counts per verdict, over methods with a body. */
  public Map<Verdict, int[]> totals() {
    Map<Verdict, int[]> out = new LinkedHashMap<>();
    for (Verdict v : Verdict.values()) out.put(v, new int[2]);
    for (Map.Entry<MethodKey, Verdict> e : verdicts.entrySet()) {
      int[] t = out.get(e.getValue());
      t[0]++;
      t[1] += graph.methods.get(e.getKey()).instructions.size();
    }
    return out;
  }
}
