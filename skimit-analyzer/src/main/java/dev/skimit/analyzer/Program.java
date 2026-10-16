package dev.skimit.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** The application classes under analysis, loaded from class directories and jars. */
public final class Program {
  public final Map<String, ClassNode> classes = new TreeMap<>();
  /** The source file each class was compiled from, as javac or kotlinc recorded it ("Foo.java"). */
  public final Map<String, String> sourceFiles = new TreeMap<>();
  /** The source lines each method's code was compiled from: its first and its last. */
  public final Map<MethodKey, int[]> lines = new java.util.HashMap<>();

  public static Program load(List<Path> inputs) throws IOException {
    Program p = new Program();
    for (Path in : inputs) {
      if (Files.isDirectory(in)) {
        try (Stream<Path> walk = Files.walk(in)) {
          for (Path f : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".class"))::iterator) {
            try (InputStream is = Files.newInputStream(f)) {
              p.add(is);
            }
          }
        }
      } else if (Files.isRegularFile(in)) {
        try (JarFile jar = new JarFile(in.toFile())) {
          for (JarEntry e : (Iterable<JarEntry>) jar.stream()::iterator) {
            String n = e.getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
            try (InputStream is = jar.getInputStream(e)) {
              p.add(is);
            }
          }
        }
      } else {
        throw new IOException("Not a class directory or jar: " + in);
      }
    }
    return p;
  }

  private void add(InputStream is) throws IOException {
    ClassNode cn = new ClassNode();
    ClassReader reader = new ClassReader(is);
    // Debug info is dropped so that moving code or renaming a local never counts as a change.
    reader.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    if (cn.name.equals("module-info") || cn.name.endsWith("/package-info")) return;
    classes.put(cn.name, cn);
    // It is read again only for where each method sits in its source, so a report can show the
    // lines of the diff that belong to it.
    reader.accept(new Locations(cn.name), ClassReader.SKIP_FRAMES);
  }

  private final class Locations extends ClassVisitor {
    private final String owner;

    Locations(String owner) {
      super(Opcodes.ASM9);
      this.owner = owner;
    }

    @Override
    public void visitSource(String source, String debug) {
      if (source != null) sourceFiles.put(owner, source);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
      MethodKey key = new MethodKey(owner, name, desc);
      return new MethodVisitor(Opcodes.ASM9) {
        private final List<Integer> seen = new java.util.ArrayList<>();

        @Override
        public void visitLineNumber(int line, Label start) {
          seen.add(line);
        }

        @Override
        public void visitEnd() {
          if (seen.isEmpty()) return;
          java.util.Collections.sort(seen);
          // Kotlin numbers the code it inlines from elsewhere past the end of the file; those
          // lines sit after a wide gap and are not this method's.
          int first = seen.get(0);
          int last = first;
          for (int line : seen) {
            if (line - last > 500) break;
            last = line;
          }
          lines.put(key, new int[] {first, last});
        }
      };
    }
  }
}
