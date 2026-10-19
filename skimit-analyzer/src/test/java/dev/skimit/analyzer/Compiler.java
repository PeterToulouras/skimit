package dev.skimit.analyzer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Compiles source snippets to a temporary class directory so tests read like the code they test. */
final class Compiler {
  private Compiler() {}

  static Analysis analyze(String... sources) throws IOException {
    return analyze(Policy.defaults(), sources);
  }

  static Analysis analyze(Policy policy, String... sources) throws IOException {
    return new Analysis(Program.load(List.of(compile(sources))), policy);
  }

  /** As {@link #analyze(String...)}, compiled for an older Java: "8". */
  static Analysis analyzeFor(String release, String... sources) throws IOException {
    return new Analysis(Program.load(List.of(compile(List.of("--release", release, "-Xlint:-options"), sources))), Policy.defaults());
  }

  static Path compile(String... sources) throws IOException {
    return compile(List.of(), sources);
  }

  static Path compile(List<String> extra, String... sources) throws IOException {
    Path out = Files.createTempDirectory("skimit-test");
    List<JavaFileObject> units = new ArrayList<>();
    for (String src : sources) {
      String name = src.replaceAll("(?s).*?\\b(?:class|interface|record|enum)\\s+(\\w+).*", "$1");
      units.add(new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
          return src;
        }
      });
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    // As Maven and Gradle compile by default: with local variable names.
    List<String> options = new ArrayList<>(List.of("-g", "-d", out.toString(), "-cp", System.getProperty("java.class.path")));
    options.addAll(extra);
    if (!javac.getTask(null, null, null, options, null, units).call()) {
      throw new IllegalStateException("test sources did not compile");
    }
    return out;
  }
}
