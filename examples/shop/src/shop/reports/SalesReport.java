package shop.reports;

import java.util.List;
import shop.model.Order;

public class SalesReport {
  private final TextTable table = new TextTable();

  public String render(List<Order> orders) {
    StringBuilder sb = new StringBuilder();
    for (Order o : orders) {
      sb.append(table.pad(o.customer(), 20)).append(o.quantity()).append('\n');
    }
    return sb.toString();
  }
}
