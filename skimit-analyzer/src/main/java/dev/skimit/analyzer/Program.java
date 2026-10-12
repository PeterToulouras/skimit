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
import org.objectweb.asm.tree.ClassNode;

/** The application classes under analysis, loaded from class directories and jars. */
public final class Program {
  public final Map<String, ClassNode> classes = new TreeMap<>();

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
    // Debug info is dropped so that moving code or renaming a local never counts as a change.
    new ClassReader(is).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    if (cn.name.equals("module-info") || cn.name.endsWith("/package-info")) return;
    classes.put(cn.name, cn);
  }
}
