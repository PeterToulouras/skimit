package shop.auth;

import dev.skimit.Critical;

public class Permissions {
  @Critical("AUTH")
  public boolean canRefund(String user) {
    return user.startsWith("admin:");
  }
}
