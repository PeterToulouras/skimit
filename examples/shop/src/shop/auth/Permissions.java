package shop.auth;

public class Permissions {
  public boolean canRefund(String user) {
    return user.startsWith("admin:");
  }
}
