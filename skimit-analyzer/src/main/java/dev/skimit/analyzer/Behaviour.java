package dev.skimit.analyzer;

import dev.skimit.analyzer.CallGraph.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What one version of the program does, as a reader who never opens the code needs it: the ways
 * in (HTTP routes, scheduled jobs, listeners, threads) and the effects each can cause (writing or
 * deleting a store's data, calling another service, sending a message). Everything in between is
 * implementation and is left out on purpose.
 */
public final class Behaviour {

  /** A way into the program. {@code id} is what stays the same when its code moves: a route, a job name, a method. */
  public record Entry(String id, String kind, String label, MethodKey method) {}

  /** Something the program does to the world: {@code verb} is writes, deletes, calls or sends. */
  public record Effect(String verb, String target) implements Comparable<Effect> {
    @Override
    public int compareTo(Effect o) {
      int r = Integer.compare(rank(), o.rank());
      return r != 0 ? r : toString().compareTo(o.toString());
    }

    /** Lower is more consequential: losing data first, then changing it, then talking to others. */
    int rank() {
      return switch (verb) {
        case "deletes" -> 0;
        case "writes" -> 1;
        case "sends" -> 2;
        default -> 3;
      };
    }

    @Override
    public String toString() {
      return verb + " " + target;
    }
  }

  public final Analysis analysis;
  /** Entry points that cause at least one effect, by id. */
  public final Map<String, Entry> entries = new TreeMap<>();
  /** For each entry point, each effect it can cause and one chain of calls that shows it. */
  public final Map<String, Map<Effect, List<MethodKey>>> effects = new TreeMap<>();
  /** For each effect, the methods that can lead to it: a change in one changes how the effect happens. */
  final Map<Effect, Set<MethodKey>> leadsTo = new HashMap<>();
  /** For each entry point, every method its calls can run before reaching an effect. */
  final Map<String, Set<MethodKey>> runs = new HashMap<>();

  public Behaviour(Analysis analysis) {
    this.analysis = analysis;
    CallGraph g = analysis.graph;
    moduleClosure();
    Map<Node, Effect> effectAt = new HashMap<>();
    for (Set<Node> callees : new ArrayList<>(g.callees.values())) {
      for (Node n : callees) {
        Effect e = effectOf(n.method());
        if (e != null) effectAt.put(n, e);
      }
    }
    // Requests, jobs and listeners own what they cause, including work they hand to a pool. What
    // starts at startup is different: it starts long-running threads, and each thread is a way in
    // of its own, so startup stops at them and they are walked in turn.
    Deque<Map.Entry<Entry, List<Node>>> work = new ArrayDeque<>();
    for (Map.Entry<MethodKey, List<Node>> e : g.nodesOf.entrySet()) {
      Entry entry = entryOf(e.getKey(), e.getValue());
      if (entry != null) work.add(Map.entry(entry, e.getValue()));
    }
    Set<MethodKey> threadsSeen = new HashSet<>();
    while (!work.isEmpty()) {
      Map.Entry<Entry, List<Node>> next = work.poll();
      Entry entry = next.getKey();
      boolean startsThreads = Set.of("startup", "callback", "uncalled", "thread").contains(entry.kind());
      Map<Effect, List<MethodKey>> found = new TreeMap<>();
      Set<MethodKey> seen = new HashSet<>();
      List<Node> threads = new ArrayList<>();
      walk(next.getValue(), effectAt, found, seen, startsThreads ? threads : null);
      for (Node t : threads) {
        if (threadsSeen.add(t.method())) work.add(Map.entry(threadEntry(t.method()), g.nodesOf.getOrDefault(t.method(), List.of(t))));
      }
      if (found.isEmpty()) continue;
      // Two methods can carry the same name (an override and its interface, two threads of one class).
      String id = entry.id();
      for (int i = 2; entries.containsKey(id); i++) id = entry.id() + " (" + i + ")";
      entry = new Entry(id, entry.kind(), id, entry.method());
      entries.put(id, entry);
      effects.put(id, found);
      runs.put(id, seen);
    }
    // Backward from each effect: the methods whose code decides how it happens.
    Map<Effect, List<Node>> byEffect = new HashMap<>();
    effectAt.forEach((n, e) -> byEffect.computeIfAbsent(e, k -> new ArrayList<>()).add(n));
    for (Map.Entry<Effect, List<Node>> e : byEffect.entrySet()) {
      Set<MethodKey> lead = new HashSet<>();
      Deque<Node> back = new ArrayDeque<>(e.getValue());
      Set<Node> visited = new HashSet<>(e.getValue());
      while (!back.isEmpty()) {
        Node n = back.poll();
        lead.add(n.method());
        for (Node caller : g.callersOf(n)) if (visited.add(caller)) back.add(caller);
      }
      leadsTo.put(e.getKey(), lead);
    }
  }

  /**
   * Forward from an entry point, stopping at the first effect on each path: what it does, and
   * through what. Code from a build the entry point's own build does not depend on never runs in
   * its process, so it is not followed (an executor calling an interface that only the server
   * implements). With {@code threads}, the bodies of threads are collected there instead of walked.
   */
  private void walk(List<Node> start, Map<Node, Effect> effectAt, Map<Effect, List<MethodKey>> found,
      Set<MethodKey> seen, List<Node> threads) {
    Set<Integer> modules = start.isEmpty() ? null : reachableModules(start.get(0).method().owner());
    Map<Node, Node> from = new HashMap<>();
    Deque<Node> work = new ArrayDeque<>();
    for (Node n : start) {
      from.put(n, null);
      work.add(n);
    }
    while (!work.isEmpty()) {
      Node n = work.poll();
      seen.add(n.method());
      Effect e = effectAt.get(n);
      if (e != null) {
        if (!found.containsKey(e)) {
          List<MethodKey> chain = new ArrayList<>();
          for (Node c = n; c != null; c = from.get(c)) chain.add(0, c.method());
          found.put(e, chain);
        }
        continue;
      }
      for (Node next : analysis.graph.calleesOf(n)) {
        if (from.containsKey(next)) continue;
        Integer module = analysis.graph.program.origin.get(next.method().owner());
        if (modules != null && module != null && !modules.contains(module)) continue;
        from.put(next, n);
        if (threads != null && isThreadBody(next.method())) threads.add(next);
        else work.add(next);
      }
    }
  }

  // ---- Builds ---------------------------------------------------------------------------------

  /** For each input, the inputs its classes refer to, directly or not, itself included. */
  private final Map<Integer, Set<Integer>> moduleReach = new HashMap<>();

  private void moduleClosure() {
    Map<String, Integer> origin = analysis.graph.program.origin;
    Map<Integer, Set<Integer>> direct = new HashMap<>();
    for (Map.Entry<String, Set<String>> e : analysis.graph.classDeps.entrySet()) {
      Integer from = origin.get(e.getKey());
      if (from == null) continue;
      Set<Integer> to = direct.computeIfAbsent(from, k -> new HashSet<>(Set.of(k)));
      for (String dep : e.getValue()) {
        Integer d = origin.get(dep);
        if (d != null) to.add(d);
      }
    }
    for (Integer m : direct.keySet()) {
      Set<Integer> all = new HashSet<>();
      Deque<Integer> work = new ArrayDeque<>(List.of(m));
      while (!work.isEmpty()) {
        Integer x = work.poll();
        if (all.add(x)) work.addAll(direct.getOrDefault(x, Set.of()));
      }
      moduleReach.put(m, all);
    }
  }

  private Set<Integer> reachableModules(String owner) {
    Integer m = analysis.graph.program.origin.get(owner);
    return m == null ? null : moduleReach.get(m);
  }

  // ---- Effects --------------------------------------------------------------------------------

  private static final Pattern DELETE = Pattern.compile("(delete|remove|clear|truncate|drop|evict|purge).*", Pattern.CASE_INSENSITIVE);
  private static final Pattern STORE_SUFFIX = Pattern.compile("(Repository|Repo|Mapper|Dao|DAO|Store)(Impl|Custom)?$");
  private static final Pattern STORE_PREFIX = Pattern.compile("^(Jdbc|Jpa|SpringData|MyBatis|Mybatis|Hibernate)(?=[A-Z])");

  /** Calls that reach another service or a message broker; the target is named by who makes the call. */
  private static final List<Policy.Rule> OUTBOUND = new ArrayList<>();
  private static final List<Policy.Rule> MESSAGES = new ArrayList<>();

  static {
    for (String s : new String[] {
        "org.springframework.web.client.RestTemplate#exchange", "org.springframework.web.client.RestTemplate#execute",
        "org.springframework.web.client.RestTemplate#*For*", "org.springframework.web.client.RestTemplate#put",
        "org.springframework.web.client.RestTemplate#delete", "org.springframework.web.client.RestClient*#retrieve",
        "org.springframework.web.client.RestClient*#exchange", "org.springframework.web.reactive.function.client.WebClient*#retrieve",
        "org.springframework.web.reactive.function.client.WebClient*#exchange*", "java.net.http.HttpClient#send*",
        "java.net.HttpURLConnection#connect", "java.net.HttpURLConnection#getOutputStream", "java.net.URLConnection#connect",
        "java.net.URLConnection#getOutputStream", "okhttp3.Call#execute", "okhttp3.Call#enqueue",
        "org.apache.http.client.HttpClient#execute", "org.apache.http.impl.client.CloseableHttpClient#execute",
        "org.apache.hc.client5.http.classic.HttpClient#execute", "org.apache.hc.client5.http.impl.classic.CloseableHttpClient#execute",
    }) OUTBOUND.add(Policy.rule("CALLS", s));
    for (String s : new String[] {
        "org.springframework.kafka.core.KafkaTemplate#send*", "org.apache.kafka.clients.producer.*Producer#send",
        "org.springframework.amqp.rabbit.core.RabbitTemplate#send", "org.springframework.amqp.rabbit.core.RabbitTemplate#convertAndSend",
        "org.springframework.amqp.core.AmqpTemplate#send", "org.springframework.amqp.core.AmqpTemplate#convertAndSend",
        "org.springframework.jms.core.JmsTemplate#send", "org.springframework.jms.core.JmsTemplate#convertAndSend",
        "software.amazon.awssdk.services.sqs.SqsClient#sendMessage*", "software.amazon.awssdk.services.sns.SnsClient#publish",
        "org.springframework.mail.javamail.JavaMailSender#send", "org.springframework.mail.MailSender#send",
        "jakarta.mail.Transport#send", "javax.mail.Transport#send",
    }) MESSAGES.add(Policy.rule("SENDS", s));
  }

  private Effect effectOf(MethodKey m) {
    Set<String> domains = analysis.sinks.get(m);
    if (domains != null) {
      String verb = DELETE.matcher(m.name()).matches() ? "deletes" : "writes";
      if (!domains.contains("DATA_WRITE")) return new Effect(verb, String.join(", ", domains) + " via " + simple(m.owner()));
      return new Effect(verb, storeName(m.owner()));
    }
    if (analysis.graph.program.classes.containsKey(m.owner())) return null;
    for (Policy.Rule r : OUTBOUND) if (r.matches(m.owner(), m.name())) return new Effect("calls", "out over HTTP");
    for (Policy.Rule r : MESSAGES) {
      if (r.matches(m.owner(), m.name())) {
        String what = m.owner().contains("mail") ? "email" : "a message";
        return new Effect("sends", what + " (" + simple(m.owner()) + ")");
      }
    }
    return null;
  }

  /** The data a store holds, by the name of its repository, mapper or DAO: OwnerRepository is Owner. */
  String storeName(String owner) {
    String store = analysis.graph.storeOf(owner);
    String name = simple(store != null ? store : owner);
    if (!analysis.graph.program.classes.containsKey(store != null ? store : owner)) return "the database (" + name + ")";
    String bare = STORE_SUFFIX.matcher(name).replaceFirst("");
    bare = STORE_PREFIX.matcher(bare).replaceFirst("");
    return bare.isEmpty() ? name : bare;
  }

  // ---- Entry points ---------------------------------------------------------------------------

  private static final String SPRING_WEB = "Lorg/springframework/web/bind/annotation/";
  private static final Set<String> HTTP_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
  private static final Map<String, String> TRIGGERS = new LinkedHashMap<>();

  static {
    TRIGGERS.put("Lorg/springframework/scheduling/annotation/Scheduled;", "schedule");
    TRIGGERS.put("Lcom/xxl/job/core/handler/annotation/XxlJob;", "job");
    TRIGGERS.put("Lorg/springframework/kafka/annotation/KafkaListener;", "listener");
    TRIGGERS.put("Lorg/springframework/amqp/rabbit/annotation/RabbitListener;", "listener");
    TRIGGERS.put("Lorg/springframework/jms/annotation/JmsListener;", "listener");
    TRIGGERS.put("Lio/awspring/cloud/sqs/annotation/SqsListener;", "listener");
    TRIGGERS.put("Lorg/springframework/context/event/EventListener;", "event");
    TRIGGERS.put("Lorg/springframework/transaction/event/TransactionalEventListener;", "event");
    TRIGGERS.put("Ljakarta/annotation/PostConstruct;", "startup");
    TRIGGERS.put("Ljavax/annotation/PostConstruct;", "startup");
  }

  /** The entry point a method is, or null: a trigger annotation, a main method, or code nothing in the program calls. */
  private Entry entryOf(MethodKey m, List<Node> nodes) {
    CallGraph g = analysis.graph;
    ClassNode cn = g.program.classes.get(m.owner());
    MethodNode mn = g.methods.get(m);
    if (cn == null || mn == null || (mn.access & Opcodes.ACC_SYNTHETIC) != 0 && CallGraph.enclosing(m) == null) return null;
    if (m.name().equals("<init>") || m.name().equals("<clinit>")) return null;

    String route = route(cn, mn);
    if (route != null) return new Entry(route, "route", route, m);
    for (AnnotationNode an : annotations(cn, mn)) {
      String kind = TRIGGERS.get(an.desc);
      if (kind == null) continue;
      String name = kind.equals("job") ? str(an, "value") : null;
      String label = switch (kind) {
        case "job" -> "job " + (name != null ? name : simple(m.owner()) + "." + m.name());
        case "schedule" -> "schedule " + simple(m.owner()) + "." + m.name();
        case "listener" -> "listener " + simple(m.owner()) + "." + m.name();
        case "event" -> "on event " + eventName(m);
        default -> "on startup " + simple(m.owner()) + "." + m.name();
      };
      return new Entry(label, kind, label, m);
    }
    if (m.name().equals("main") && m.desc().equals("([Ljava/lang/String;)V") && (mn.access & Opcodes.ACC_STATIC) != 0) {
      return new Entry("main " + simple(m.owner()), "startup", "main " + simple(m.owner()), m);
    }
    // Code that nothing in the program calls but that runs anyway: a thread's body, a callback a
    // library holds, a framework hook. Its name is where it lives.
    boolean called = false;
    for (Node n : nodes) called |= !g.callersOf(n).isEmpty();
    if (called || (mn.access & Opcodes.ACC_PRIVATE) != 0 && CallGraph.enclosing(m) == null) return null;
    if (isThreadBody(m)) return threadEntry(m);
    String lib = g.shape(m.owner()).libraryParent();
    String lifecycle = LIFECYCLE.get(m.name());
    if (lib != null && lifecycle != null) {
      String label = lifecycle + " " + outer(m.owner());
      return new Entry(label, "startup", label, m);
    }
    String where = CallGraph.enclosing(m) != null ? "code started in " + simple(m.owner()) + "." + CallGraph.enclosing(m)
        : lib != null ? simple(lib) + " callback " + outer(m.owner()) + "." + m.name()
        : "uncalled " + simple(m.owner()) + "." + m.name();
    String kind = CallGraph.enclosing(m) != null ? "thread" : lib != null ? "callback" : "uncalled";
    return new Entry(where, kind, where, m);
  }

  /** Framework hooks by name: when they run tells the reader more than the interface they belong to. */
  private static final Map<String, String> LIFECYCLE = Map.of(
      "afterPropertiesSet", "on startup", "afterSingletonsInstantiated", "on startup",
      "setApplicationContext", "on startup", "contextInitialized", "on startup",
      "destroy", "on shutdown", "contextDestroyed", "on shutdown",
      "onApplicationEvent", "on event in");

  /** The body of a thread or a task handed to one: run() of a Runnable, Thread or TimerTask, call() of a Callable. */
  private boolean isThreadBody(MethodKey m) {
    boolean shape = m.name().equals("run") && m.desc().equals("()V") || m.name().equals("call") && m.desc().startsWith("()");
    if (!shape || !analysis.graph.program.classes.containsKey(m.owner())) return false;
    String lib = analysis.graph.shape(m.owner()).libraryParent();
    return lib != null && isThread(lib);
  }

  /** A thread by where it is started: JobScheduleHelper$1 created in start() is "thread JobScheduleHelper.start". */
  private Entry threadEntry(MethodKey m) {
    ClassNode cn = analysis.graph.program.classes.get(m.owner());
    String label = cn != null && cn.outerMethod != null ? "thread " + outer(m.owner()) + "." + cn.outerMethod
        : "thread " + simple(m.owner());
    return new Entry(label, "thread", label, m);
  }

  private static boolean isThread(String lib) {
    return lib.equals("java/lang/Runnable") || lib.equals("java/lang/Thread") || lib.equals("java/util/concurrent/Callable")
        || lib.equals("java/util/TimerTask");
  }

  private String eventName(MethodKey m) {
    Type[] args = Type.getArgumentTypes(m.desc());
    return args.length > 0 ? simple(args[0].getInternalName()) : simple(m.owner()) + "." + m.name();
  }

  /** "POST /api/owners/{id}/pets" for a request handler, from its own annotations or the ones it implements. */
  private String route(ClassNode cn, MethodNode mn) {
    for (ClassNode c : withSupertypes(cn)) {
      MethodNode decl = find(c, mn.name, mn.desc);
      if (decl == null) continue;
      for (AnnotationNode an : list(decl)) {
        if (!an.desc.startsWith(SPRING_WEB) || !an.desc.endsWith("Mapping;")) continue;
        String kind = an.desc.substring(SPRING_WEB.length(), an.desc.length() - "Mapping;".length());
        String verb = kind.equals("Request") ? requestMethod(an) : kind.toUpperCase();
        String path = join(classPath(cn), first(an));
        return (verb == null ? "" : verb + " ") + path;
      }
      String verb = null;
      String path = null;
      for (AnnotationNode an : list(decl)) {
        String d = an.desc;
        if (d.startsWith("Ljakarta/ws/rs/") || d.startsWith("Ljavax/ws/rs/")) {
          String n = d.substring(d.lastIndexOf('/') + 1, d.length() - 1);
          if (HTTP_VERBS.contains(n)) verb = n;
          if (n.equals("Path")) path = str(an, "value");
        }
      }
      if (verb != null) return verb + " " + join(jaxrsClassPath(cn), path);
    }
    return null;
  }

  private String classPath(ClassNode cn) {
    for (ClassNode c : withSupertypes(cn)) {
      if (c.visibleAnnotations == null) continue;
      for (AnnotationNode an : c.visibleAnnotations) if (an.desc.equals(SPRING_WEB + "RequestMapping;")) return first(an);
    }
    return "";
  }

  private String jaxrsClassPath(ClassNode cn) {
    for (ClassNode c : withSupertypes(cn)) {
      if (c.visibleAnnotations == null) continue;
      for (AnnotationNode an : c.visibleAnnotations) {
        if (an.desc.equals("Ljakarta/ws/rs/Path;") || an.desc.equals("Ljavax/ws/rs/Path;")) return str(an, "value");
      }
    }
    return "";
  }

  private static String requestMethod(AnnotationNode an) {
    Object v = value(an, "method");
    if (v instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof String[] e) return e[1];
    return null;
  }

  private static String first(AnnotationNode an) {
    for (String k : new String[] {"value", "path"}) {
      Object v = value(an, k);
      if (v instanceof List<?> l && !l.isEmpty()) return String.valueOf(l.get(0));
      if (v instanceof String s) return s;
    }
    return "";
  }

  private static String str(AnnotationNode an, String key) {
    Object v = value(an, key);
    if (v instanceof List<?> l) return l.isEmpty() ? null : String.valueOf(l.get(0));
    return v == null ? null : String.valueOf(v);
  }

  private static Object value(AnnotationNode an, String key) {
    if (an.values == null) return null;
    for (int i = 0; i + 1 < an.values.size(); i += 2) if (an.values.get(i).equals(key)) return an.values.get(i + 1);
    return null;
  }

  private static String join(String a, String b) {
    String p = ("/" + (a == null ? "" : a) + "/" + (b == null ? "" : b)).replaceAll("/+", "/");
    return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
  }

  private List<AnnotationNode> annotations(ClassNode cn, MethodNode mn) {
    List<AnnotationNode> out = new ArrayList<>();
    for (ClassNode c : withSupertypes(cn)) {
      MethodNode decl = find(c, mn.name, mn.desc);
      if (decl != null) out.addAll(list(decl));
    }
    return out;
  }

  private static List<AnnotationNode> list(MethodNode mn) {
    List<AnnotationNode> out = new ArrayList<>();
    if (mn.visibleAnnotations != null) out.addAll(mn.visibleAnnotations);
    if (mn.invisibleAnnotations != null) out.addAll(mn.invisibleAnnotations);
    return out;
  }

  private static MethodNode find(ClassNode cn, String name, String desc) {
    for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
    return null;
  }

  /** The class and every application class or interface above it, nearest first. */
  private List<ClassNode> withSupertypes(ClassNode cn) {
    List<ClassNode> out = new ArrayList<>();
    Deque<String> work = new ArrayDeque<>(List.of(cn.name));
    Set<String> seen = new HashSet<>();
    while (!work.isEmpty()) {
      ClassNode c = analysis.graph.program.classes.get(work.poll());
      if (c == null || !seen.add(c.name)) continue;
      out.add(c);
      if (c.superName != null) work.add(c.superName);
      work.addAll(c.interfaces);
    }
    return out;
  }

  static String simple(String internal) {
    return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
  }

  /** An anonymous or nested class by the class it is written in: JobScheduleHelper$1 is JobScheduleHelper. */
  private static String outer(String internal) {
    return simple(CallGraph.topLevel(internal));
  }

  /** Every effect, with the entry points that can cause it. */
  public Map<Effect, Set<String>> causes() {
    Map<Effect, Set<String>> out = new TreeMap<>();
    effects.forEach((id, es) -> es.keySet().forEach(e -> out.computeIfAbsent(e, k -> new TreeSet<>()).add(id)));
    return out;
  }
}
