package dev.skimit.analyzer;

import dev.skimit.analyzer.Analysis.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Compares two analyses and sorts every changed method into a review bucket with its reason. */
public final class Differ {

  public enum Bucket { MUST, SHOULD, SKIP }

  public record Change(
      MethodKey method,
      String kind,
      Bucket bucket,
      Verdict verdict,
      Set<String> domains,
      Set<String> newDomains,
      String reason,
      List<MethodKey> witness,
      String explanation) {}

  public final List<Change> changes = new ArrayList<>();
  /** Sinks added or removed. Changing what counts as critical is itself always reviewed. */
  public final List<String> policy = new ArrayList<>();
  /** Structure changes: API surface, new dependencies between classes, position moves. */
  public final List<String> shape = new ArrayList<>();
  /** Untouched methods that can now reach a sink because of something they call. */
  public final List<MethodKey> ripple = new ArrayList<>();

  public Differ(Analysis base, Analysis head) {
    for (MethodKey m : head.verdicts.keySet()) {
      boolean existed = base.verdicts.containsKey(m);
      boolean same = existed && base.hashes.get(m).equals(head.hashes.get(m));
      Verdict hv = head.verdict(m);
      Verdict bv = existed ? base.verdict(m) : Verdict.ISOLATED;
      Set<String> baseDomains = bv == Verdict.ON_PATH ? base.domains(m) : Set.of();
      if (same) {
        if (hv == Verdict.ON_PATH && bv != Verdict.ON_PATH) ripple.add(m);
        continue;
      }
      String kind = existed ? "changed" : "added";
      if (hv == Verdict.ON_PATH) {
        Set<String> fresh = new TreeSet<>(head.domains(m));
        fresh.removeAll(baseDomains);
        add(m, kind, Bucket.MUST, head, existed ? fresh : Set.of(), null);
      } else if (bv == Verdict.ON_PATH) {
        add(m, kind, Bucket.MUST, base, Set.of(), "could reach a critical sink before this change and no longer can");
      } else if (hv == Verdict.INFLUENCES || hv == Verdict.UNPROVEN) {
        add(m, kind, Bucket.SHOULD, head, Set.of(), null);
      } else if (bv == Verdict.INFLUENCES || bv == Verdict.UNPROVEN) {
        add(m, kind, Bucket.SHOULD, base, Set.of(), "before this change, " + base.reason(m));
      } else {
        add(m, kind, Bucket.SKIP, head, Set.of(), null);
      }
    }
    for (MethodKey m : base.verdicts.keySet()) {
      if (head.verdicts.containsKey(m)) continue;
      Verdict bv = base.verdict(m);
      Bucket b = bv == Verdict.ON_PATH ? Bucket.MUST : bv == Verdict.ISOLATED ? Bucket.SKIP : Bucket.SHOULD;
      add(m, "removed", b, base, Set.of(), bv == Verdict.ISOLATED ? null : "before removal, " + base.reason(m));
    }

    for (Map.Entry<MethodKey, Set<String>> e : head.sinks.entrySet()) {
      Set<String> before = base.sinks.get(e.getKey());
      if (before == null) policy.add("sink added: " + e.getKey().pretty() + " " + e.getValue());
      else if (!before.equals(e.getValue())) policy.add("sink domains changed: " + e.getKey().pretty() + " " + before + " -> " + e.getValue());
    }
    for (Map.Entry<MethodKey, Set<String>> e : base.sinks.entrySet()) {
      if (!head.sinks.containsKey(e.getKey())) policy.add("sink removed: " + e.getKey().pretty() + " " + e.getValue());
    }

    shape(base, head);
  }

  private void add(MethodKey m, String kind, Bucket bucket, Analysis from, Set<String> newDomains, String reason) {
    Verdict v = from.verdict(m);
    changes.add(new Change(m, kind, bucket, v, v == Verdict.UNPROVEN ? Set.of() : from.domains(m), newDomains,
        reason != null ? reason : from.reason(m), from.witness(m), from.explain(m)));
  }

  public long count(Bucket b) {
    return changes.stream().filter(c -> c.bucket() == b).count();
  }

  private void shape(Analysis base, Analysis head) {
    Map<String, ClassNode> bc = base.graph.program.classes;
    Map<String, ClassNode> hc = head.graph.program.classes;
    for (ClassNode cn : hc.values()) {
      ClassNode old = bc.get(cn.name);
      if (old == null) {
        if (exposed(cn.access)) shape.add("new public type " + dotted(cn.name));
        continue;
      }
      Set<String> before = api(old);
      Set<String> after = api(cn);
      for (String s : after) if (!before.contains(s)) shape.add("new public method " + s);
      for (String s : before) if (!after.contains(s)) shape.add("public method removed " + s);
    }
    for (ClassNode cn : bc.values()) {
      if (!hc.containsKey(cn.name) && exposed(cn.access)) shape.add("public type removed " + dotted(cn.name));
    }
    for (Map.Entry<String, Set<String>> e : head.graph.classDeps.entrySet()) {
      Set<String> before = base.graph.classDeps.get(e.getKey());
      if (before == null) continue;
      for (String dep : e.getValue()) {
        if (!before.contains(dep)) {
          shape.add("new dependency " + dotted(e.getKey()) + " -> " + dotted(dep)
              + " (" + head.positions.get(dep) + ", " + head.dependents.get(dep) + " dependents)");
        }
      }
    }
    for (Map.Entry<String, Analysis.Position> e : head.positions.entrySet()) {
      Analysis.Position before = base.positions.get(e.getKey());
      if (before != null && before != e.getValue()) {
        shape.add("position " + dotted(e.getKey()) + ": " + before + " -> " + e.getValue()
            + " (" + base.dependents.get(e.getKey()) + " -> " + head.dependents.get(e.getKey()) + " dependents)");
      }
    }
  }

  private static boolean exposed(int access) {
    return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0;
  }

  private static Set<String> api(ClassNode cn) {
    Set<String> out = new TreeSet<>();
    if (!exposed(cn.access)) return out;
    for (MethodNode mn : cn.methods) {
      if (exposed(mn.access) && !mn.name.equals("<clinit>")) out.add(new MethodKey(cn.name, mn.name, mn.desc).pretty());
    }
    return out;
  }

  private static String dotted(String internal) {
    return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
  }
}
