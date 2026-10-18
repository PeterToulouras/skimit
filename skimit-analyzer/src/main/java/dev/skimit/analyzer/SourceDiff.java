package dev.skimit.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A unified diff of the sources (what {@code git diff} prints), read so that each changed method
 * can be shown with its own lines. The analysis never reads it: what skimit says comes from the
 * bytecode alone, and this only puts the code behind a line next to it, for whoever asks.
 */
public final class SourceDiff {

  /** One line of a hunk: ' ', '+' or '-', its numbers before and after (0 when it has none), its text. */
  public record Line(char kind, int before, int after, String text) {}

  /** One file of the diff: its paths before and after ("" for a side it does not exist on) and its hunk lines. */
  record File(String before, String after, List<Line> lines) {}

  /** The most lines shown for one method; a longer change is cut and says so. */
  static final int MAX_LINES = 80;

  final List<File> files = new ArrayList<>();

  private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  public static SourceDiff parse(String text) {
    SourceDiff d = new SourceDiff();
    String before = "";
    String after = "";
    List<Line> lines = null;
    int b = 0;
    int a = 0;
    int leftBefore = 0;  // lines of the current hunk still to come, on each side
    int leftAfter = 0;
    boolean inHunk = false;
    for (String raw : text.split("\n", -1)) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      if (line.startsWith("diff --git ")) {
        inHunk = false;
        lines = null;
        continue;
      }
      if (!inHunk && line.startsWith("--- ")) {
        before = path(line.substring(4));
        continue;
      }
      if (!inHunk && line.startsWith("+++ ")) {
        after = path(line.substring(4));
        lines = new ArrayList<>();
        d.files.add(new File(before, after, lines));
        continue;
      }
      Matcher h = HUNK.matcher(line);
      if (h.matches() && lines != null) {
        b = Integer.parseInt(h.group(1));
        leftBefore = h.group(2) == null ? 1 : Integer.parseInt(h.group(2));
        a = Integer.parseInt(h.group(3));
        leftAfter = h.group(4) == null ? 1 : Integer.parseInt(h.group(4));
        inHunk = leftBefore + leftAfter > 0;
        continue;
      }
      if (!inHunk || lines == null) continue;
      if (line.startsWith("\\")) continue;  // "\ No newline at end of file"
      // An empty line is a context line whose leading space was trimmed on the way.
      char kind = line.isEmpty() ? ' ' : line.charAt(0);
      String body = line.isEmpty() ? "" : line.substring(1);
      switch (kind) {
        case ' ' -> { lines.add(new Line(' ', b++, a++, body)); leftBefore--; leftAfter--; }
        case '+' -> { lines.add(new Line('+', 0, a++, body)); leftAfter--; }
        case '-' -> { lines.add(new Line('-', b++, 0, body)); leftBefore--; }
        default -> inHunk = false;
      }
      if (leftBefore <= 0 && leftAfter <= 0) inHunk = false;
    }
    return d;
  }

  /** "a/src/Foo.java" to "src/Foo.java"; "/dev/null" to "". Paths with spaces may carry a tab and a date. */
  private static String path(String p) {
    int tab = p.indexOf('\t');
    if (tab >= 0) p = p.substring(0, tab);
    if (p.equals("/dev/null")) return "";
    if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
    return p.startsWith("a/") || p.startsWith("b/") ? p.substring(2) : p;
  }

  /**
   * The file a class was compiled from: the one whose path ends with the class's package and
   * source file name, or else the only one with that file name. Null when the diff has none.
   */
  File fileOf(String internalName, String sourceFile, boolean afterTheChange) {
    if (sourceFile == null) return null;
    int slash = internalName.lastIndexOf('/');
    String expected = (slash < 0 ? "" : internalName.substring(0, slash + 1)) + sourceFile;
    File byName = null;
    int named = 0;
    for (File f : files) {
      String p = afterTheChange ? f.after() : f.before();
      if (p.isEmpty()) continue;
      if (p.equals(expected) || p.endsWith("/" + expected)) return f;
      if (p.equals(sourceFile) || p.endsWith("/" + sourceFile)) {
        byName = f;
        named++;
      }
    }
    return named == 1 ? byName : null;
  }

  /**
   * The lines of the diff that fall within a method compiled from lines {@code first} to
   * {@code last}, on the side the method lives on, with a line or two around it for its
   * signature. Empty when none of them was edited: the method changed for a reason its own
   * lines do not show (a constant it uses, a class it was moved with).
   */
  static List<Line> within(File f, int first, int last, boolean afterTheChange) {
    int from = Math.max(1, first - 2);
    int to = last + 1;
    List<Line> out = new ArrayList<>();
    boolean edited = false;
    int here = 0;  // the number on this side of the last line that has one
    for (Line l : f.lines()) {
      int n = afterTheChange ? l.after() : l.before();
      boolean inside;
      if (n > 0) {
        here = n;
        inside = n >= from && n <= to;
      } else {
        // A line only on the other side sits after the last line of this side.
        inside = here >= from - 1 && here < to;
      }
      if (!inside) continue;
      if (!out.isEmpty()) {
        Line prev = out.get(out.size() - 1);
        int p = afterTheChange ? prev.after() : prev.before();
        if (n > 0 && p > 0 && n > p + 1) out.add(new Line('~', 0, 0, ""));
      }
      out.add(l);
      if (l.kind() != ' ') edited = true;
    }
    if (!edited) return List.of();
    // Context lines at either end that are past the last edit carry no information.
    while (!out.isEmpty() && out.get(out.size() - 1).kind() == '~') out.remove(out.size() - 1);
    if (out.size() > MAX_LINES) {
      List<Line> cut = new ArrayList<>(out.subList(0, MAX_LINES));
      cut.add(new Line('~', 0, 0, (out.size() - MAX_LINES) + " more lines"));
      return cut;
    }
    return out;
  }
}
