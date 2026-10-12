package shop.checkout;

import shop.model.Order;

public class PriceCalculator {
  public long total(Order order) {
    return order.unitCents() * order.quantity();
  }
}
