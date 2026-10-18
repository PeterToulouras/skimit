package shop.support;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.auth.Permissions;
import shop.billing.Ledger;

@RestController
public class RefundHandler {
  private final Permissions permissions = new Permissions();
  private final Ledger ledger;

  public RefundHandler(Ledger ledger) {
    this.ledger = ledger;
  }

  @PostMapping("/refunds")
  public boolean refund(String user, String customer, long cents) {
    if (!permissions.canRefund(user)) return false;
    ledger.post(customer, -cents);
    return true;
  }
}
