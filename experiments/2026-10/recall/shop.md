Changes to behaviour said: 15 of 15.
Changes to code alone kept silent: 5 of 9.
Read-path changes, silent by design: 1 of 1.

| Mutation | Kind | What it does | skimit | What skimit said |
|---|---|---|---|---|
| `remove_auth_check` | behaviour | refunds no longer need permission | **caught** | POST /refunds: no longer checks Permissions.canRefund before it writes MONEY via Ledger (in RefundHandler.refund) |
| `invert_auth_check` | behaviour | only people WITHOUT permission can refund | **caught** | POST /refunds: changed the check before it writes MONEY via Ledger: a comparison changed (in RefundHandler.refund) |
| `get_route_writes` | behaviour | a report route now moves money | **caught** | Breaks a rule (never "GET *" writes *): GET /reports/sales writes MONEY via Ledger<br>GET /reports/sales now writes MONEY via Ledger (2 → 3 ways in) |
| `new_write_route` | behaviour | a new route credits accounts | **caught** | New way in: POST /credits → writes MONEY via Ledger (2 → 3 ways in) |
| `remove_write` | behaviour | checkout no longer charges anyone | **caught** | POST /checkout no longer writes MONEY via Ledger (2 → 1 ways in) |
| `price_doubles` | behaviour | every checkout charges twice the price | **caught** | POST /checkout: writes MONEY via Ledger with different data (in PriceCalculator.total: now uses 2) |
| `refund_charges` | behaviour | a refund now charges instead of crediting | **caught** | POST /refunds: writes MONEY via Ledger with different data: a calculation changed (in RefundHandler.refund) |
| `refund_to_wrong_account` | behaviour | the refund goes to the admin, not the customer | **caught** | POST /refunds: writes MONEY via Ledger with different data: Ledger.post now given user, not customer (in RefundHandler.refund) |
| `add_quantity_check` | behaviour | empty orders are no longer charged | **caught** | POST /checkout: new check before it writes MONEY via Ledger, on Order.quantity (in CheckoutHandler.submit) |
| `widen_permission` | behaviour | support staff can refund too | **caught** | POST /refunds: changed the check before it writes MONEY via Ledger (in Permissions.canRefund: now uses "support:") |
| `sink_drops_amount` | behaviour | the ledger no longer records the amount | **caught** | 2 ways in (POST /checkout, POST /refunds): the code that writes MONEY via Ledger changed (in Ledger.post: no longer uses ":") |
| `report_calls_out` | behaviour | the report route now calls another service | **caught** | GET /reports/sales now calls out over HTTP (nothing called that before) |
| `charge_twice` | behaviour | checkout charges a second time, a flat fee | **caught** | POST /checkout: writes MONEY via Ledger now using 100 (in CheckoutHandler.submit) |
| `refund_cap` | behaviour | refunds over a limit are refused | **caught** | POST /refunds: changed the check before it writes MONEY via Ledger: now against 100000 (in RefundHandler.refund) |
| `write_via_reflection` | behaviour | the same write, made through reflection | **caught** | POST /refunds no longer writes MONEY via Ledger (2 → 1 ways in); RefundHandler.refund now calls Method.invoke, which skimit does not follow |
| `rename_local` | same | a parameter renamed | **silent** | No change in behaviour. |
| `reformat_and_comment` | same | a comment and line breaks | **silent** | No change in behaviour. |
| `extract_method` | same | the charge moved into a private helper | **FALSE LINE** | POST /checkout: writes MONEY via Ledger now using CheckoutHandler.charge, no longer Order.customer, PaymentService.charge and 1 more (in CheckoutHandler.submit) |
| `move_class_package` | same | Permissions moved to another package | **silent** | No change in behaviour. |
| `add_logging` | same | a line printed before the charge | **silent** | No change in behaviour. |
| `unused_param` | same | a parameter added and never used | **FALSE LINE** | POST /checkout: writes MONEY via Ledger with different data (in CheckoutHandler.submit) |
| `rename_method` | same | PaymentService.charge renamed to pay | **silent** | No change in behaviour. |
| `commute_multiplication` | same | the two factors of the price swapped | **FALSE LINE** | POST /checkout: writes MONEY via Ledger with different data (in PriceCalculator.total: the same steps in another order) |
| `field_to_local` | same | the calculator made where it is used instead of held in a field | **FALSE LINE** | POST /checkout: writes MONEY via Ledger now using new PriceCalculator (in CheckoutHandler.submit) |
| `read_path_rewrite` | read | the report pads its columns another way | **silent** | No change in behaviour. |
