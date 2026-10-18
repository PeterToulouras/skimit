package shop;

import java.util.List;
import shop.billing.CardPaymentService;
import shop.billing.Ledger;
import shop.checkout.CheckoutHandler;
import shop.model.Order;
import shop.reports.SalesReport;
import shop.support.RefundHandler;

/** Drives the shop the way a test would, calling each route once, so the demo has a real run to check. */
public class Demo {
  public static void main(String[] args) {
    Ledger ledger = new Ledger();
    Order order = new Order("ada", 1250, 2);
    new CheckoutHandler(new CardPaymentService(ledger)).submit(order);
    new RefundHandler(ledger).refund("admin:grace", "ada", 500);
    System.out.print(new SalesReport().render(List.of(order)));
    System.out.println("ledger entries: " + ledger.size());
  }
}
