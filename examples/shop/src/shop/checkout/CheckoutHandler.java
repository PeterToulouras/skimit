package shop.checkout;

import shop.billing.PaymentService;
import shop.model.Order;

public class CheckoutHandler {
  private final PaymentService payments;
  private final PriceCalculator prices = new PriceCalculator();

  public CheckoutHandler(PaymentService payments) {
    this.payments = payments;
  }

  public void submit(Order order) {
    payments.charge(order.customer(), prices.total(order));
  }
}
