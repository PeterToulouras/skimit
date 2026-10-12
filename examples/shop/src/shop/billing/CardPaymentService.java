package shop.billing;

public class CardPaymentService implements PaymentService {
  private final Ledger ledger;

  public CardPaymentService(Ledger ledger) {
    this.ledger = ledger;
  }

  @Override
  public void charge(String customer, long cents) {
    ledger.post(customer, cents);
  }
}
