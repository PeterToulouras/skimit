package shop.reports;

public class TextTable {
  public String pad(String s, int width) {
    StringBuilder sb = new StringBuilder(s);
    while (sb.length() < width) sb.append(' ');
    return sb.toString();
  }
}
