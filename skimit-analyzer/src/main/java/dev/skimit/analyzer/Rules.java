package dev.skimit.analyzer;

import dev.skimit.analyzer.Behaviour.Effect;
import dev.skimit.analyzer.CallGraph.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What people decide about the system's behaviour, checked on every change. A rule is about ways
 * in and effects, never about code:
 *
 * <pre>
 * never  "GET *"      writes *      # a read route never writes
 * never  *            deletes Account
 * only   OrderService writes Order  # every way to write Order goes through OrderService
 * review calls *                    # a new way to call out needs a person
 * </pre>
 *
 * A change breaks a rule when it makes the rule false where it was true before; something that
 * already broke it before the change is reported, not held against the change.
 */
public final class Rules {

  /** One rule as written: {@code kind} is never, only or review. */
  public record Rule(String kind, String subject, String verb, String target, String source) {
    boolean effect(Effect e) {
      return glob(verb).matcher(e.verb()).matches() && glob(target).matcher(e.target()).matches();
    }
  }

  private Rules() {}

  /** Parses a rule line after its first word, quotes allowed around a word with spaces in it. */
  static Rule parse(String kind, String rest, String source) {
    List<String> t = words(rest);
    if (kind.equals("review")) {
      if (t.size() != 2) throw new IllegalArgumentException("expected 'review VERB TARGET'");
      return new Rule(kind, "*", t.get(0), t.get(1), source);
    }
    if (t.size() != 3) throw new IllegalArgumentException("expected '" + kind + " " + (kind.equals("only") ? "CLASS" : "WAY-IN")
        + " VERB TARGET'");
    return new Rule(kind, t.get(0), t.get(1), t.get(2), source);
  }

  private static List<String> words(String s) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '"') {
        int end = s.indexOf('"', i + 1);
        if (end < 0) throw new IllegalArgumentException("unclosed quote");
        out.add(s.substring(i + 1, end));
        i = end + 1;
      } else {
        int end = i;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        out.add(s.substring(i, end));
        i = end;
      }
    }
    return out;
  }

  /** Every way the program does not keep the never and only rules. Review rules are about changes, not states. */
  static Set<String> breaches(List<Rule> rules, Behaviour b) {
    Set<String> out = new TreeSet<>();
    for (Rule r : rules) {
      if (r.kind().equals("never")) {
        Pattern way = glob(r.subject());
        b.effects.forEach((id, es) -> {
          if (!b.entries.get(id).kind().equals("uncalled") && way.matcher(id).matches()) {
            for (Effect e : es.keySet()) if (r.effect(e)) out.add(key(r, id, e));
          }
        });
      } else if (r.kind().equals("only")) {
        Pattern owner = glob(r.subject());
        for (Effect e : b.causes().keySet()) {
          if (!r.effect(e)) continue;
          Set<MethodKey> around = bypass(b, e, owner);
          b.effects.forEach((id, es) -> {
            if (es.containsKey(e) && around.contains(b.entries.get(id).method()) && !b.entries.get(id).kind().equals("uncalled")) {
              out.add(key(r, id, e));
            }
          });
        }
      }
    }
    return out;
  }

  /** The methods that reach the effect without passing through a class the rule allows. */
  private static Set<MethodKey> bypass(Behaviour b, Effect e, Pattern allowed) {
    Set<MethodKey> out = new HashSet<>();
    Deque<Node> work = new ArrayDeque<>(b.nodesOf(e));
    Set<Node> seen = new HashSet<>(work);
    while (!work.isEmpty()) {
      Node n = work.poll();
      if (allowed.matcher(Behaviour.simple(CallGraph.topLevel(n.method().owner()))).matches()) continue;
      out.add(n.method());
      for (Node c : b.analysis.graph.callersOf(n)) if (seen.add(c)) work.add(c);
    }
    return out;
  }

  static String key(Rule r, String wayIn, Effect e) {
    return r.source() + "\u0000" + wayIn + "\u0000" + e.verb() + "\u0000" + e.target();
  }

  static Pattern glob(String g) {
    StringBuilder sb = new StringBuilder();
    for (String part : g.split("\\*", -1)) {
      if (sb.length() > 0) sb.append(".*");
      sb.append(Pattern.quote(part));
    }
    return Pattern.compile(sb.toString());
  }

}
