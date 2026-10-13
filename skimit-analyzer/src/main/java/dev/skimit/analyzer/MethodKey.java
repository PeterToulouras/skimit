package dev.skimit.analyzer;

import org.objectweb.asm.Type;

/** Identity of a method: owner in internal form ({@code com/acme/Foo}), name, descriptor. */
public record MethodKey(String owner, String name, String desc) implements Comparable<MethodKey> {

  public String id() {
    return owner.replace('/', '.') + "#" + name + desc;
  }

  /** Short human form, e.g. {@code Ledger.post(Money)}. */
  public String pretty() {
    StringBuilder sb = new StringBuilder(simple(owner.replace('/', '.')));
    if (name.equals("<clinit>")) return sb.append(" (static initializer)").toString();
    String n = name;
    if (n.startsWith("lambda$")) {
      String[] parts = n.split("\\$");
      n = (parts.length > 1 ? parts[1] : n) + "[lambda]";
    }
    sb.append('.').append(n).append('(');
    Type[] args;
    try {
      args = Type.getArgumentTypes(desc);
    } catch (RuntimeException e) {
      args = new Type[0];
    }
    for (int i = 0; i < args.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(simple(args[i].getClassName()));
    }
    return sb.append(')').toString();
  }

  private static String simple(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  @Override
  public int compareTo(MethodKey o) {
    return id().compareTo(o.id());
  }
}
