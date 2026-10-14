package dev.skimit.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Human-declared facts the analysis cannot derive: which methods are critical sinks (in addition
 * to {@code @Critical} annotations) and which calls are opaque (the analysis cannot see where they
 * go, so anything that can reach one is never declared safe).
 *
 * <pre>
 * # comments start with a hash
 * sink   MONEY  com.acme.billing.Ledger#post*
 * sink   DATA   java.sql.*Statement#execute*
 * opaque com.acme.plugins.PluginBus#dispatch
 * store  settings write com.acme.settings.SettingsService#set*
 * store  settings read  com.acme.settings.SettingsService#get*
 * participant class  com.acme.web.BeforeEachRequest
 * participant global com.acme.audit.Audited
 * </pre>
 *
 * A {@code store} line declares state that outlives a request (a table, a bucket, a cache): whoever
 * writes it influences whoever reads it on a critical flow. Repositories, mappers, DAOs and the
 * common database clients are recognised without being declared.
 *
 * <p>A {@code participant} line names an annotation that makes a framework call the method inside
 * flows it is not called from: {@code class} when it runs alongside the other methods of the same
 * object, {@code global} when it can run inside any flow.
 *
 * Patterns are {@code owner#method} globs where {@code *} matches any run of characters. In place of
 * the method glob, {@code @write} matches every method whose name says it writes (see {@link #WRITES}).
 */
public final class Policy {
  public record Rule(String domain, Pattern owner, Pattern name, String source) {
    boolean matches(String ownerInternal, String methodName) {
      return owner.matcher(ownerInternal.replace('/', '.')).matches()
          && name.matcher(methodName).matches();
    }
  }

  /** An annotation that makes a framework call the method inside flows it is not called from. */
  public record Participant(Pattern annotation, boolean global, String source) {
    boolean matches(String annotationDesc) {
      // Ljakarta/annotation/PostConstruct; -> jakarta.annotation.PostConstruct
      return annotation.matcher(annotationDesc.substring(1, annotationDesc.length() - 1).replace('/', '.')).matches();
    }
  }

  public final List<Rule> sinks = new ArrayList<>();
  /**
   * Whether to fall back on {@link #DEFAULT_SINKS} when nothing declares a sink: no {@code sink}
   * line here and no {@code @Critical} in the code.
   */
  public boolean defaultSinks = true;
  public final List<Rule> opaque = new ArrayList<>();
  public final List<Participant> participants = new ArrayList<>();
  /** Declared stores: the rule's domain is the store's name. */
  public final List<Rule> storeWrites = new ArrayList<>();
  public final List<Rule> storeReads = new ArrayList<>();

  /** Library types that are a door to a database or cache, whatever sits behind them. */
  static final String[] DATA_CLIENTS = {
    "jakarta/persistence/EntityManager", "javax/persistence/EntityManager", "org/hibernate/Session",
    "org/springframework/jdbc/core/JdbcTemplate", "org/springframework/jdbc/core/JdbcOperations",
    "org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate",
    "org/springframework/jdbc/core/namedparam/NamedParameterJdbcOperations",
    "org/springframework/jdbc/core/simple/JdbcClient", "org/springframework/jdbc/core/simple/SimpleJdbcInsert",
    "java/sql/Statement", "java/sql/PreparedStatement", "java/sql/CallableStatement",
    "org/springframework/data/mongodb/core/MongoTemplate", "org/springframework/data/mongodb/core/MongoOperations",
    "org/springframework/data/redis/core/RedisTemplate", "org/springframework/data/redis/core/StringRedisTemplate",
    "org/springframework/data/redis/core/ValueOperations", "org/springframework/data/redis/core/HashOperations",
    "software/amazon/awssdk/services/dynamodb/DynamoDbClient",
    "software/amazon/awssdk/services/dynamodb/DynamoDbAsyncClient",
    "com/amazonaws/services/dynamodbv2/AmazonDynamoDB", "org/apache/ibatis/session/SqlSession",
    "jakarta/persistence/Query", "jakarta/persistence/TypedQuery", "javax/persistence/Query", "javax/persistence/TypedQuery",
  };

  /**
   * The sinks used when none are declared: every data write, whether through the project's own
   * repositories, mappers and DAOs or straight through a common database or storage client. A
   * blunt starting point that needs no setup; a project's own sinks are narrower and better.
   */
  /**
   * In place of a method glob, {@code @write} matches every method whose name says it writes:
   * {@code save}, {@code insert}, {@code update}, {@code delete}, {@code remove}, {@code upsert} or
   * {@code clear}, at the start of the name or as a later word of it ({@code batchDelete},
   * {@code scheduleBatchUpdate}, {@code markDeleted}), unless the name starts as a read
   * ({@code findByUpdateTime}, {@code isDeleted}).
   */
  static final String WRITES = "@write";
  private static final String WRITE_WORDS = "save|insert|update|delete|remove|upsert|clear";
  private static final Pattern WRITE_NAME = Pattern.compile(
      "(?:" + WRITE_WORDS + ").*"
      + "|(?!(?:find|get|count|exists|query|select|list|search|load|read|is|has|fetch|lookup|scan|contains|stream)(?![a-z]))"
      + "[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)*?(?:" + capitalized(WRITE_WORDS) + ")(?:s|d|ed)?(?![a-z]).*");

  private static String capitalized(String alternatives) {
    StringBuilder sb = new StringBuilder();
    for (String w : alternatives.split("\\|")) {
      if (sb.length() > 0) sb.append('|');
      sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
    }
    return sb.toString();
  }

  static final List<Rule> DEFAULT_SINKS = new ArrayList<>();
  static final String DEFAULT_SINK_DOMAIN = "DATA_WRITE";

  /** Raw clients, as {owner glob, method globs...}. Reads, queries and plain execute() are left out. */
  private static final String[][] DEFAULT_CLIENT_WRITES = {
    {"java.sql.*Statement", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"},
    {"org.springframework.jdbc.core.*", "update", "batchUpdate"},
    {"org.springframework.jdbc.core.simple.SimpleJdbcInsert", "execute*"},
    {"jakarta.persistence.EntityManager", "persist", "merge", "remove"},
    {"javax.persistence.EntityManager", "persist", "merge", "remove"},
    {"jakarta.persistence.*Query", "executeUpdate"},
    {"javax.persistence.*Query", "executeUpdate"},
    {"org.hibernate.*Session", "save*", "persist", "update", "merge", "delete", "remove"},
    {"org.apache.ibatis.session.SqlSession", "insert", "update", "delete"},
    {"org.springframework.data.mongodb.core.*Mongo*", "save", "insert*", "update*", "upsert", "remove",
        "findAndModify", "findAndRemove", "findAllAndRemove", "findAndReplace"},
    {"com.mongodb.*MongoCollection", "insert*", "update*", "delete*", "replace*", "bulkWrite", "findOneAndDelete",
        "findOneAndReplace", "findOneAndUpdate"},
    {"software.amazon.awssdk.services.dynamodb.DynamoDb*Client", "putItem", "updateItem", "deleteItem",
        "batchWriteItem", "transactWriteItems", "executeStatement", "batchExecuteStatement", "executeTransaction"},
    {"software.amazon.awssdk.enhanced.dynamodb.DynamoDb*", "putItem", "updateItem", "deleteItem",
        "batchWriteItem", "transactWriteItems"},
    {"com.amazonaws.services.dynamodbv2.AmazonDynamoDB*", "putItem", "updateItem", "deleteItem",
        "batchWriteItem", "transactWriteItems"},
    {"com.amazonaws.services.dynamodbv2.datamodeling.DynamoDB*Mapper", "save", "delete", "batchSave",
        "batchDelete", "batchWrite", "transactionWrite"},
    {"software.amazon.awssdk.services.s3.S3*Client", "putObject", "deleteObject", "deleteObjects", "copyObject"},
    {"com.amazonaws.services.s3.AmazonS3*", "putObject", "deleteObject", "deleteObjects", "copyObject"},
  };

  static {
    for (String type : new String[] {"*Repository", "*Mapper", "*Dao", "*DAO"}) {
      DEFAULT_SINKS.add(rule(DEFAULT_SINK_DOMAIN, type + "#" + WRITES));
    }
    for (String[] client : DEFAULT_CLIENT_WRITES) {
      for (int i = 1; i < client.length; i++) DEFAULT_SINKS.add(rule(DEFAULT_SINK_DOMAIN, client[0] + "#" + client[i]));
    }
  }

  private static final Pattern WRITE_VERB = Pattern.compile(
      "(save|insert|update|delete|remove|add|put|create|persist|merge|set|store|write|upsert|increment|decrement|clear|evict|flush|drop|truncate).*");
  private static final Pattern READ_VERB = Pattern.compile(
      "(find|get|read|query|select|load|list|search|exists|count|fetch|is|has|contains|scan|lookup).*");

  // Building a query reads and writes nothing; running it does (Query#executeUpdate is a write).
  private static final Pattern NEITHER = Pattern.compile("close|toString|hashCode|equals|getClass|unwrap|isWrapperFor|isClosed|isOpen"
      + "|create(Native|Named|StoredProcedure|Criteria)?Query|createNamedStoredProcedureQuery|getCriteriaBuilder|createEntityGraph"
      + "|getMetamodel|getProperties|getFlushMode|setFlushMode|getLockMode"
      + "|set(Parameter|FirstResult|MaxResults|Hint|LockMode|Timeout|CacheMode|CacheStoreMode|CacheRetrieveMode)");

  /** What a call to a store method does, judged by its name; an unfamiliar name may do either. */
  static boolean writes(String method) {
    if (NEITHER.matcher(method).matches()) return false;
    return WRITE_VERB.matcher(method).matches() || !READ_VERB.matcher(method).matches();
  }

  static boolean reads(String method) {
    if (NEITHER.matcher(method).matches()) return false;
    return READ_VERB.matcher(method).matches() || !WRITE_VERB.matcher(method).matches();
  }

  /**
   * Methods the framework runs alongside the other methods of the same object: before each request
   * a controller handles, or when the object is set up.
   */
  private static final String[] DEFAULT_CLASS_PARTICIPANTS = {
    "org.springframework.web.bind.annotation.ModelAttribute",
    "org.springframework.web.bind.annotation.InitBinder",
    "org.springframework.web.bind.annotation.ExceptionHandler",
    "jakarta.annotation.PostConstruct", "javax.annotation.PostConstruct",
    "jakarta.annotation.PreDestroy", "javax.annotation.PreDestroy",
    "jakarta.persistence.Pre*", "jakarta.persistence.Post*",
    "javax.persistence.Pre*", "javax.persistence.Post*",
    "org.springframework.beans.factory.annotation.Autowired",
    "jakarta.inject.Inject", "javax.inject.Inject",
  };

  /** Methods the framework runs inside flows anywhere in the program. */
  private static final String[] DEFAULT_GLOBAL_PARTICIPANTS = {
    "org.aspectj.lang.annotation.Before", "org.aspectj.lang.annotation.After",
    "org.aspectj.lang.annotation.Around", "org.aspectj.lang.annotation.AfterReturning",
    "org.aspectj.lang.annotation.AfterThrowing",
    "org.springframework.context.event.EventListener",
    "org.springframework.transaction.event.TransactionalEventListener",
  };

  /** On a class: its participant methods apply to every controller, not only their own. */
  static final Set<String> ADVICE_CLASS_ANNOTATIONS = Set.of(
      "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
      "Lorg/springframework/web/bind/annotation/RestControllerAdvice;");

  /** Reflection and dynamic dispatch the call graph cannot follow. */
  private static final String[] DEFAULT_OPAQUE = {
    "java.lang.reflect.Method#invoke",
    "java.lang.reflect.Constructor#newInstance",
    "java.lang.reflect.Proxy#newProxyInstance",
    "java.lang.Class#forName",
    "java.lang.Class#newInstance",
    "java.lang.invoke.MethodHandle#invoke*",
    "java.util.ServiceLoader#load*",
    "org.springframework.context.ApplicationEventPublisher#publishEvent",
    "org.springframework.context.*ApplicationContext#publishEvent",
    "org.springframework.beans.factory.BeanFactory#getBean",
    "org.springframework.context.*ApplicationContext#getBean",
    // Dispatch by URL or name: the framework picks the handler at run time.
    "org.springframework.test.web.servlet.MockMvc#perform",
    "jakarta.servlet.RequestDispatcher#*", "javax.servlet.RequestDispatcher#*",
    // Handing on to the rest of a chain: whatever handles the request runs inside this call.
    "jakarta.servlet.FilterChain#doFilter", "javax.servlet.FilterChain#doFilter",
    "org.springframework.web.server.WebFilterChain#filter",
    "org.springframework.http.client.ClientHttpRequestExecution#execute",
    "org.aspectj.lang.ProceedingJoinPoint#proceed", "org.aopalliance.intercept.Joinpoint#proceed",
    "org.aopalliance.intercept.MethodInvocation#proceed",
    // Reflection helpers.
    "org.springframework.util.ReflectionUtils#invokeMethod",
    "org.springframework.test.util.ReflectionTestUtils#invoke*",
    "org.apache.commons.lang3.reflect.MethodUtils#invoke*",
  };

  /**
   * On a class: a framework constructs it and calls it, whether or not application code also
   * constructs one somewhere.
   */
  static final Set<String> MANAGED_CLASS_ANNOTATIONS = Set.of(
      "Lorg/springframework/stereotype/Component;", "Lorg/springframework/stereotype/Service;",
      "Lorg/springframework/stereotype/Repository;", "Lorg/springframework/stereotype/Controller;",
      "Lorg/springframework/web/bind/annotation/RestController;",
      "Lorg/springframework/web/bind/annotation/ControllerAdvice;",
      "Lorg/springframework/web/bind/annotation/RestControllerAdvice;",
      "Lorg/springframework/context/annotation/Configuration;",
      "Lorg/springframework/boot/autoconfigure/SpringBootApplication;",
      "Ljakarta/inject/Named;", "Ljavax/inject/Named;", "Ljakarta/inject/Singleton;", "Ljavax/inject/Singleton;",
      "Ljakarta/enterprise/context/ApplicationScoped;", "Ljavax/enterprise/context/ApplicationScoped;",
      "Ljakarta/enterprise/context/RequestScoped;", "Ljavax/enterprise/context/RequestScoped;",
      "Ljakarta/servlet/annotation/WebFilter;", "Ljavax/servlet/annotation/WebFilter;",
      "Ljakarta/servlet/annotation/WebServlet;", "Ljavax/servlet/annotation/WebServlet;",
      "Ljakarta/servlet/annotation/WebListener;", "Ljavax/servlet/annotation/WebListener;",
      "Ljakarta/ws/rs/ext/Provider;", "Ljavax/ws/rs/ext/Provider;", "Lorg/aspectj/lang/annotation/Aspect;",
      "Ljakarta/persistence/Converter;", "Ljavax/persistence/Converter;");

  public static Policy defaults() {
    Policy p = new Policy();
    for (String s : DEFAULT_OPAQUE) p.opaque.add(rule(null, s));
    for (String s : DEFAULT_CLASS_PARTICIPANTS) p.participants.add(new Participant(glob(s), false, s));
    for (String s : DEFAULT_GLOBAL_PARTICIPANTS) p.participants.add(new Participant(glob(s), true, s));
    return p;
  }

  public static Policy load(Path file) throws IOException {
    Policy p = defaults();
    int lineNo = 0;
    for (String raw : Files.readAllLines(file)) {
      lineNo++;
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] t = line.split("\\s+");
      if (t[0].equals("sink") && t.length == 3) {
        p.sinks.add(rule(t[1], t[2]));
      } else if (t[0].equals("opaque") && t.length == 2) {
        p.opaque.add(rule(null, t[1]));
      } else if (t[0].equals("store") && t.length == 4 && (t[2].equals("write") || t[2].equals("read"))) {
        (t[2].equals("write") ? p.storeWrites : p.storeReads).add(rule(t[1], t[3]));
      } else if (t[0].equals("participant") && t.length == 3 && (t[1].equals("class") || t[1].equals("global"))) {
        p.participants.add(new Participant(glob(t[2]), t[1].equals("global"), t[2]));
      } else {
        throw new IOException(file + ":" + lineNo
            + ": expected 'sink DOMAIN owner#method', 'opaque owner#method',"
            + " 'store NAME write|read owner#method' or 'participant class|global annotation'");
      }
    }
    return p;
  }

  static Rule rule(String domain, String pattern) {
    int hash = pattern.indexOf('#');
    if (hash < 0) throw new IllegalArgumentException("Pattern needs owner#method: " + pattern);
    String method = pattern.substring(hash + 1);
    return new Rule(domain, glob(pattern.substring(0, hash)),
        method.equals(WRITES) ? WRITE_NAME : glob(method), pattern);
  }



  private static Pattern glob(String g) {
    StringBuilder sb = new StringBuilder();
    for (String part : g.split("\\*", -1)) {
      if (sb.length() > 0) sb.append(".*");
      sb.append(Pattern.quote(part));
    }
    return Pattern.compile(sb.toString());
  }
}
