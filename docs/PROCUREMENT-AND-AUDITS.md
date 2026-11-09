# Procurement, batches and inventory audits

All stock changes described here go through the stock movement engine
([STOCK-MOVEMENTS.md](STOCK-MOVEMENTS.md)): purchasing and audits never write balances themselves.

## Purchase orders

```
DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED ──receipt──▶ PARTIALLY_RECEIVED ──receipt──▶ RECEIVED
  ▲                   │                     │                        │
  └──────reject───────┘                     └────────cancel──────────┘   (DRAFT and SUBMITTED can be cancelled too)
```

- The order number (`PO-<year>-<seq>`) and the total are computed by the server. There is no total field to send.
- Only DRAFT orders can be edited (`PUT`, with the `version` from the last read). An edit replaces the header and
  all items. Each product may appear once per order.
- Rejecting a submitted order sends it back to DRAFT with the reason, so it can be corrected and resubmitted.
- Cancelling is possible until the order is fully received. Goods already received stay in stock; cancelling
  never reverses stock.
- Writes are ADMIN only. Receiving is ADMIN or WAREHOUSE_MANAGER (of the order's warehouse).

### Receiving

`POST /purchase-orders/{id}/receipts` with header `Idempotency-Key: <unique per receipt, e.g. a UUID>`:

```json
{"items": [{"itemId": 41, "quantity": 120, "batchNumber": "LOT-7731", "expirationDate": "2027-05-31"}]}
```

- One receipt posts one INBOUND stock document, with one ledger line per item, the supplier attached and the
  order number as reference. Partial deliveries are just several receipts.
- A receipt can't exceed an item's outstanding quantity (`422 RECEIPT_EXCEEDS_ORDER`). Only APPROVED and
  PARTIALLY_RECEIVED orders receive (`409 INVALID_STATE_TRANSITION`).
- For batch-tracked products the batch number is required. A new batch is created with the dates, the item's
  unit price as `unit_cost`, and a link to the order item. A batch number identifies one physical lot, so
  receiving an **existing** number with a different production or expiration date is rejected
  (`409 BATCH_METADATA_MISMATCH`) rather than silently reusing the lot; the production date may not be in the future.
- **Idempotency.** The order row is locked (`FOR UPDATE`) first, and only then is the key looked up. Concurrent
  retries of the same request therefore run one after another: the first receives, the rest get the original
  receipt back (`200`, `"replayed": true`) without touching stock. Reusing a key for a different order is
  `409 IDEMPOTENCY_KEY_REUSED`. The unique constraint on `goods_receipt.idempotency_key` is the backstop.
- Everything (order lines, status, stock, ledger, receipt record) commits in one transaction or not at all.

### Reports

- `GET /purchase-orders` filters by supplier, warehouse, status, order-number prefix and order date range
  (`from`/`to`). `outstanding=true` returns APPROVED and PARTIALLY_RECEIVED orders.
- `GET /purchase-orders/summary?groupBy=SUPPLIER|WAREHOUSE|STATUS&from=&to=` gives the order count and ordered
  value per group.

## Batches and expiration

A batch's stock is per warehouse, read from the balance projection:

| Field | Meaning |
|---|---|
| `availableQuantity` | Current stock of the batch in that warehouse |
| `initialQuantity` | Everything that ever entered that warehouse for the batch (receipts, transfers in, positive adjustments); never less than available |
| `daysUntilExpiration`, `expired` | Relative to today in the business time zone (`Asia/Baku`) |

Endpoints: `GET /batches` (filters `productId`, `warehouseId`, `batchNumber` prefix, `includeEmpty`),
`/batches/{id}`, `/batches/{id}/movements`, `/batches/product/{id}`, `/batches/warehouse/{id}`,
`/batches/expired`, `/batches/expiring?days=7|30|…` (0–3650, inclusive of today), and
`/batches/expiration-risk?days=30`. The last one ranks products by expired stock, then by stock expiring within
the horizon. Empty batches are hidden unless `includeEmpty=true`.

### FEFO

When an outgoing movement (outbound, transfer, write-off) names no batch for a batch-tracked product, the engine
picks batches **first-expired, first-out**:

- Batches are ordered by expiration date (no date last), then batch number. Zero-quantity rows are skipped.
- Expired batches are skipped, except for write-offs, which exist to remove them.
- One request may be split over several batches. The response holds the total plus an `allocations` list, one
  entry per ledger line, with the batch and the balance after it.
- **Concurrency:** the candidate balance rows are locked (`FOR UPDATE`) in the canonical lock order before
  choosing. Concurrent requests queue instead of picking the same units, and can't deadlock with other postings.
  If the batches can't cover the request, nothing is posted (`422 INSUFFICIENT_STOCK` with the available
  quantity).
- History is preserved: each allocation is its own immutable ledger line.

## Inventory audits

```
DRAFT ─start─▶ IN_PROGRESS ─submit─▶ PENDING_APPROVAL ─approve─▶ APPROVED ─complete─▶ COMPLETED
                    ▲                       │
                    └───────reject──────────┘          cancel: from DRAFT, IN_PROGRESS, PENDING_APPROVAL
```

- **Create** with `warehouseId`, optional `fullAudit` and `blind`. A full audit starts with every stocked item of
  the warehouse and picks up new stock again when it is started. At most one full audit can be open per warehouse.
- **Items:** `POST /{id}/items` adds products. For a batch-tracked product without `batchNumber`, every stocked
  batch is added.
- **Count:** `PUT /{id}/items/{itemId}/count` with `countedQuantity ≥ 0`. The expected quantity is taken from the
  system balance at that moment, never from the client. The discrepancy (`counted − expected`) is computed by
  the database. Counts can be corrected while IN_PROGRESS.
- **Blind audits** hide expected quantities and discrepancies until the audit is submitted.
- **Submit** needs every item counted. **Approve/reject/complete** are ADMIN only. Reject sends the audit back to
  IN_PROGRESS with the reason; counts are kept.
- **Complete** posts every non-zero discrepancy as one ADJUSTMENT document and stores its number on the audit.
  The audit row is locked first, so concurrent completions run one after another and the second gets
  `409 INVALID_STATE_TRANSITION`: adjustments are posted exactly once.

### Movements during an audit

Stock is not frozen while an audit runs. The strategy is a **per-item snapshot at count time**:

1. Recording a count reads the balance with a share lock (`FOR SHARE`). A movement of that stock waits until the
   count is committed, so the snapshot and the count describe the same moment.
2. Movements after the count change the shelf and the balance alike, so the discrepancy stays valid.
   Completion applies it to the balance as it is then: counted 8 when the system said 10, then 3 shipped, so
   the balance goes 7 → 5.
3. Each item shows `movementsSinceCount` and `netChangeSinceCount`, so approvers can see activity after the count.
4. If stock left after the count makes a shortage larger than what is now on hand, completion fails as a whole
   (`422 INSUFFICIENT_STOCK`) and nothing is posted. The audit stays APPROVED; investigate, then complete again
   or cancel and recount.

### Reports

- `GET /inventory-audits` is the audit history (filters: warehouse, status, number prefix, creation date range).
- `GET /inventory-audits/discrepancies` lists counted items that differed from the system, largest first.
- `GET /inventory-audits/statistics` returns audits by status, plus counted items, discrepancies, accuracy %,
  surplus and shortage over completed audits.
- `GET /inventory-audits/statistics/by-warehouse` gives the same figures per warehouse.

## Known limitations

- `movementsSinceCount` compares movement and count timestamps. A movement that started before a count but was
  blocked by its share lock is recorded with the earlier timestamp and is not listed. The posted adjustment
  is still correct.
- Purchase summary rows are grouped in SQL but sorted and paged in memory (one row per supplier, warehouse or
  status, so the set is small).
- Receipts can't be reversed or returned to the supplier through the API yet (the ledger already has REVERSAL and
  RETURN_TO_SUPPLIER types, but no endpoints). For now a wrong receipt is corrected with an ADMIN adjustment.
