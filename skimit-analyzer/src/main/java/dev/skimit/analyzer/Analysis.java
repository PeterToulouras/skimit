package dev.skimit.analyzer;

import java.util.ArrayList;
import java.util.HashSet;
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
 * The facts read from one version of the program: its call graph, the methods that change the
 * world when called (sinks: declared, annotated, or found by the built-in rule for data writes),
 * and a digest of each method body so that two versions can be compared.
 */
public final class Analysis {

  public static final String CRITICAL_DESC = "Ldev/skimit/Critical;";

  public final CallGraph graph;
  /** Sink method to its domains. May include library methods named by policy rules. */
  public final Map<MethodKey, Set<String>> sinks = new TreeMap<>();
  /** True when the built-in rule (data writes) found every sink, nothing being declared: the output says so. */
  public boolean usingDefaultSinks;
  public final Map<MethodKey, String> hashes = new TreeMap<>();

  public Analysis(Program program, Policy policy) {
    this.graph = new CallGraph(program);
    findSinks(policy);
    for (MethodKey m : graph.methods.keySet()) hashes.put(m, graph.bodyHash(m));
  }

  /** Methods with a body, each once. */
  public Set<MethodKey> methods() {
    return graph.nodesOf.keySet();
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
    // The built-in rule adds data writes to what is declared. A method declared as a sink keeps
    // the name it was declared with.
    if (policy.defaultSinks) {
      boolean declared = !sinks.isEmpty() || !policy.sinks.isEmpty();
      for (MethodKey m : candidates) {
        if (graph.converter(m.owner()) || sinks.containsKey(m)) continue;
        for (Policy.Rule r : Policy.DEFAULT_SINKS) {
          if (r.matches(m.owner(), m.name())) addSink(m, Set.of(r.domain()));
        }
      }
      usingDefaultSinks = !declared && !sinks.isEmpty();
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
}
