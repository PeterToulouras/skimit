package shop.billing;

import dev.skimit.Critical;
import java.util.ArrayList;
import java.util.List;

/** The only place money moves. This is the one thing a human tagged. */
public class Ledger {
  private final List<String> entries = new ArrayList<>();

  @Critical("MONEY")
  public void post(String account, long cents) {
    entries.add(account + ":" + cents);
  }

  public int size() {
    return entries.size();
  }
}
