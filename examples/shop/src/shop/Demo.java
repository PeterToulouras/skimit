package shop;

import shop.billing.CardPaymentService;
import shop.billing.Ledger;
import shop.checkout.CheckoutHandler;
import shop.model.Order;
import shop.support.RefundHandler;

/** A small run of the shop, so the demo has something real to record and check. */
public class Demo {
  public static void main(String[] args) {
    Ledger ledger = new Ledger();
    new CheckoutHandler(new CardPaymentService(ledger)).submit(new Order("ada", 1250, 2));
    new RefundHandler(ledger).refund("admin:grace", "ada", 500);
    System.out.println("ledger entries: " + ledger.size());
  }
}
