package shop.reports;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import shop.model.Order;

@RestController
public class SalesReport {
  private final TextTable table = new TextTable();

  @GetMapping("/reports/sales")
  public String render(List<Order> orders) {
    StringBuilder sb = new StringBuilder();
    for (Order o : orders) {
      sb.append(table.pad(o.customer(), 20)).append(o.quantity()).append('\n');
    }
    return sb.toString();
  }
}
