package shop.billing;

public interface PaymentService {
  void charge(String customer, long cents);
}
