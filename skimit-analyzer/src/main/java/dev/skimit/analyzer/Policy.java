package dev.skimit.analyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What people declare: which calls change the world (sinks, in addition to {@code @Critical}
 * annotations and the built-in rule for data writes) and the rules a change must keep.
 *
 * <pre>
 * # comments start with a hash
 * sink   MONEY  com.acme.billing.Ledger#post*
 * sink   DATA   java.sql.*Statement#execute*
 * never  "GET *"      writes *
 * only   OrderService writes Order
 * review calls *
 * </pre>
 *
 * Patterns are {@code owner#method} globs where {@code *} matches any run of characters. In place of
 * the method glob, {@code @write} matches every method whose name says it writes (see {@link #WRITES}).
 * Rules are described in {@link Rules}.
 */
public final class Policy {
  public record Rule(String domain, Pattern owner, Pattern name) {
    boolean matches(String ownerInternal, String methodName) {
      return owner.matcher(ownerInternal.replace('/', '.')).matches()
          && name.matcher(methodName).matches();
    }
  }

  public final List<Rule> sinks = new ArrayList<>();
  /**
   * Whether the built-in rule ({@link #DEFAULT_SINKS}, data writes) adds its sinks to the ones
   * declared here and with {@code @Critical}.
   */
  public boolean defaultSinks = true;
  /** What people decided about behaviour: never, only and review lines. */
  public final List<Rules.Rule> rules = new ArrayList<>();

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

  /** Calls that pick what they run by name at run time: reflection and method handles. The call graph stops there. */
  static final List<Rule> BY_NAME = List.of(
      rule("BY_NAME", "java.lang.reflect.Method#invoke"), rule("BY_NAME", "java.lang.reflect.Constructor#newInstance"),
      rule("BY_NAME", "java.lang.invoke.MethodHandle#invoke*"),
      rule("BY_NAME", "org.springframework.util.ReflectionUtils#invokeMethod"),
      rule("BY_NAME", "org.springframework.test.util.ReflectionTestUtils#invokeMethod"));

  static boolean byName(MethodKey m) {
    for (Rule r : BY_NAME) if (r.matches(m.owner(), m.name())) return true;
    return false;
  }

  public static Policy defaults() {
    Policy p = new Policy();
    return p;
  }

  public static Policy load(Path file) throws IOException {
    Policy p = defaults();
    int lineNo = 0;
    for (String raw : Files.readAllLines(file)) {
      lineNo++;
      String line = withoutComment(raw).strip();
      if (line.isEmpty()) continue;
      String[] t = line.split("\\s+");
      if (t[0].equals("sink") && t.length == 3) {
        p.sinks.add(rule(t[1], t[2]));
      } else if (t[0].equals("opaque") || t[0].equals("store") || t[0].equals("participant")) {
        System.err.println("skimit: " + file + ":" + lineNo + ": '" + t[0] + "' lines are no longer used; this one is ignored");
      } else if (t[0].equals("never") || t[0].equals("only") || t[0].equals("review")) {
        try {
          p.rules.add(Rules.parse(t[0], line.substring(t[0].length()).strip(), line));
        } catch (IllegalArgumentException e) {
          throw new IOException(file + ":" + lineNo + ": " + e.getMessage());
        }
      } else {
        throw new IOException(file + ":" + lineNo
            + ": expected 'sink DOMAIN owner#method', 'never WAY-IN VERB TARGET', 'only CLASS VERB TARGET'"
            + " or 'review VERB TARGET'");
      }
    }
    return p;
  }

  /** The line up to a {@code #} that starts a comment: at the start or after a space, outside quotes. */
  static String withoutComment(String line) {
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') quoted = !quoted;
      else if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) return line.substring(0, i);
    }
    return line;
  }

  static Rule rule(String domain, String pattern) {
    int hash = pattern.indexOf('#');
    if (hash < 0) throw new IllegalArgumentException("Pattern needs owner#method: " + pattern);
    String method = pattern.substring(hash + 1);
    return new Rule(domain, glob(pattern.substring(0, hash)), method.equals(WRITES) ? WRITE_NAME : glob(method));
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
