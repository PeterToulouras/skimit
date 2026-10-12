package shop.support;

import shop.auth.Permissions;
import shop.billing.Ledger;

public class RefundHandler {
  private final Permissions permissions = new Permissions();
  private final Ledger ledger;

  public RefundHandler(Ledger ledger) {
    this.ledger = ledger;
  }

  public boolean refund(String user, String customer, long cents) {
    if (!permissions.canRefund(user)) return false;
    ledger.post(customer, -cents);
    return true;
  }
}
