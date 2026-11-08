# Stock movement engine

Every stock change is a **movement**: an immutable ledger record (`stock_transaction` plus its `stock_movement`
line). The quantity on hand per warehouse, product and batch (`inventory_balance`) is a projection of that ledger,
kept equal to it inside the same database transaction.

## Movement types

| Type | Warehouses | Endpoint | Who | Notes |
|---|---|---|---|---|
| `INBOUND` | destination | `POST /api/v1/stock-movements/inbound` | ADMIN, WAREHOUSE_MANAGER | Optional supplier, unit cost (defaults to the purchase price), reference number |
| `OUTBOUND` | source | `POST /api/v1/stock-movements/outbound` | ADMIN, WAREHOUSE_MANAGER | Can't exceed stock on hand |
| `TRANSFER` | source → destination | `POST /api/v1/stock-movements/transfer` | ADMIN, WAREHOUSE_MANAGER | One movement, both balances, one transaction; managers need both warehouses |
| `ADJUSTMENT` | destination (`INCREASE`) or source (`DECREASE`) | `POST /api/v1/stock-movements/adjustment` | ADMIN | Reason required |
| `WRITE_OFF` | source | `POST /api/v1/stock-movements/write-off` | ADMIN, WAREHOUSE_MANAGER | Reason required; the only way out for expired batches |

Every movement records its number (`SM-2026-000123`), type, product, batch, positive quantity, source and/or
destination warehouse, the balance of each touched warehouse right after the movement, reference number, reason,
supplier (inbound), the authenticated user, and the time. The time is server time; movements can't be backdated,
so the ledger order is the real order.

## Rules

| Rule | Where it's enforced |
|---|---|
| Quantity is positive and fits the product's unit (no 2.5 pieces) | Request validation (`400`), `CHECK (quantity > 0)` |
| Product and warehouses exist, are active, and are visible to the user | Service (`422 INACTIVE_RESOURCE`, `404` out of scope) |
| No transfer to the same warehouse | Request validation, service, `CHECK` on the table |
| Batch-tracked products need a batch; others must not have one | Service (`422 BATCH_REQUIRED` / `BATCH_NOT_ALLOWED`) |
| Expired batches can't be received, issued or transferred, only written off | Service (`422 BATCH_EXPIRED`) |
| Stock never goes negative | Atomic conditional update (`422 INSUFFICIENT_STOCK`), plus `CHECK (quantity >= 0)` |
| A failed movement changes nothing | One transaction per request; any exception rolls back balances, batches and ledger |
| Movements are never edited or deleted | No such endpoints (`405`), `@Immutable` entities, no UPDATE/DELETE grant for the app role, database triggers |
| Only the posting service touches balances | `BalanceWriter` is used only by `InventoryPostingService`; an architecture test fails the build otherwise |

## Concurrency: why atomic conditional updates

Two managers withdrawing the last units at the same moment is the classic race. Checking stock in Java and then
writing it ("read, check, write") lets both see the same quantity and both succeed.

The engine never reads-then-writes. Each withdrawal is one statement:

```sql
UPDATE inventory_balance
   SET quantity = quantity - :qty, version = version + 1, updated_at = now()
 WHERE warehouse_id = :w AND product_id = :p AND batch_id ... AND quantity >= :qty
RETURNING quantity
```

- The `UPDATE` takes the row lock. A second withdrawal on the same row waits. When the first commits, PostgreSQL
  re-evaluates the `WHERE` against the new row, so the second either fits the remaining stock or matches nothing.
  "Nothing returned" means insufficient stock, and the whole transaction rolls back.
- Receipts use `INSERT ... ON CONFLICT DO UPDATE`, so two first receipts of a product into a warehouse can't race.
- The lock is held only for the one row and only until commit. Different products or warehouses never wait for each
  other, which a table lock or a `SERIALIZABLE` transaction with retries would not give.
- **Deadlocks:** a transfer touches two rows. Rows are always locked in ascending warehouse id, so A→B and B→A
  transfers queue up instead of deadlocking. Should the database still report a lock failure, the API answers `409`
  and nothing is written.
- **Deactivation:** postings take a shared lock (`FOR SHARE`) on their warehouses. Deactivating a warehouse takes
  an exclusive lock and checks it is empty, so stock can't arrive while a warehouse is being closed.

`StockConcurrencyIT` checks this against the real database. It releases 30 one-unit withdrawals against 10 units at
once (exactly 10 succeed), runs 40 opposite transfers at once (no deadlock, no stock lost), and mixes withdrawals
and transfers competing for the same stock. After each run it checks that every balance equals the ledger and is not
negative.

## History

`GET /api/v1/stock-movements` filters by `productId`, `warehouseId` (source or destination), `type`, `supplierId`,
`referenceNumber`, and `from`/`to` (inclusive dates in the business time zone). It is paged and sortable by
`movementDate` (default, newest first), `quantity` and `id`. Shortcuts: `/{id}`, `/product/{id}`, `/warehouse/{id}`,
`/inbound`, `/outbound`, `/transfer`, and `/api/v1/suppliers/{id}/stock-movements` for a supplier's deliveries.
Non-admins see only movements that touch their assigned warehouses.

## Known limitations

- One product per request. The ledger supports multi-line documents (for example one transfer of 20 products),
  but the API doesn't expose them yet.
- No idempotency key yet: a client that retries a timed-out request may post twice. The column exists
  (`stock_transaction.idempotency_key`, unique).
- Batch-tracked products need an explicit batch on outbound movements. Automatic FEFO picking (earliest expiry
  first) is not implemented.
- No reversal endpoint yet; a wrong movement is corrected with an `ADJUSTMENT`.
- Unit cost is recorded on inbound movements, but there is no inventory valuation (average or FIFO cost) yet.
