package shop.checkout;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.billing.PaymentService;
import shop.model.Order;

@RestController
public class CheckoutHandler {
  private final PaymentService payments;
  private final PriceCalculator prices = new PriceCalculator();

  public CheckoutHandler(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/checkout")
  public void submit(Order order) {
    payments.charge(order.customer(), prices.total(order));
  }
}
