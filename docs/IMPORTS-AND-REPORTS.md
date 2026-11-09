# CSV import, search and reports

Stage 7 adds bulk CSV import, fast product search and an analytical reporting suite. None of it bypasses the
existing business logic: imports post through the stock-movement engine, and reports are read-only aggregations.

## CSV bulk inbound import

`POST /api/v1/imports/stock-inbound` — `multipart/form-data` with a `file` part and an `Idempotency-Key` header.
ADMIN or WAREHOUSE_MANAGER only.

Header (exactly, case-insensitive):

```
warehouseCode,sku,quantity,unitCost,batchNumber,expirationDate,referenceNumber
WH-001,SKU-1001,50,12.50,,,IMPORT-001
WH-001,SKU-1002,30,,LOT-7,2027-09-15,IMPORT-001
```

- `unitCost`, `batchNumber`, `expirationDate` and `referenceNumber` may be blank where the product allows it.
  `referenceNumber` defaults to the Idempotency-Key. A row with an expiry-tracked batch must carry a date.
- **Partial success.** Each row is validated independently. Valid rows are imported; invalid rows are returned
  in `errors` (never silently dropped) and skipped. `successfulRows + failedRows = totalRows`.
- Valid rows are posted through `StockMovementService` as INBOUND stock, **one document per reference number**,
  so imports go through the same balance, batch (FEFO/creation) and ledger logic as any other inbound. Balances
  are never written directly.
- **One transaction.** The valid rows and the import record commit together. If an unforeseen posting conflict
  occurs (e.g. a race), nothing is imported and an error is returned — the validation is thorough enough that this
  is rare in practice.
- **Idempotency.** A repeated upload with the same key returns the stored result (`200`, `replayed=true`) without
  importing again; the full result (per-row errors included) is kept as JSON in `stock_import`.
- **Guards.** The file must be a CSV with the exact header, at most 10 000 data rows, and within the multipart size
  limit (`WMS_IMPORT_MAX_FILE_SIZE`, default 5 MB → `413 FILE_TOO_LARGE`). Rows may only target warehouses the
  caller can access.

Result:

```json
{
  "importId": 42, "replayed": false,
  "totalRows": 3, "successfulRows": 2, "failedRows": 1, "importedQuantity": 80,
  "errors": [{"rowNumber": 2, "field": "sku", "errorCode": "MISSING_PRODUCT", "message": "No product with SKU 'X'"}]
}
```

Row `errorCode` values: `MALFORMED_ROW`, `MISSING_VALUE`, `INVALID_NUMBER`, `INVALID_QUANTITY`,
`INVALID_QUANTITY_SCALE`, `MISSING_PRODUCT`, `INACTIVE_PRODUCT`, `MISSING_WAREHOUSE`, `INACTIVE_WAREHOUSE`,
`INVALID_UNIT_COST`, `BATCH_REQUIRED`, `BATCH_NOT_ALLOWED`, `INVALID_EXPIRATION_DATE`, `BATCH_EXPIRED`,
`EXPIRY_DATE_REQUIRED`, `INVALID_VALUE`.

## Product search

Lightweight, projection-based (no lazy relationships loaded), paginated:

- `GET /products/search` — `sku` and `barcode` exact (SKU case-insensitive), `q` a name prefix, plus `categoryId`
  and `active`. Sortable by `sku`, `name`.
- `GET /products/sku/{sku}`, `GET /products/barcode/{barcode}` — exact single-product lookups.
- `GET /warehouses/{warehouseId}/products/search` — the same filters within one warehouse, each row carrying the
  product's available quantity there; `inStockOnly` hides products with no stock.

All lookups hit covering indexes: unique on SKU and barcode, and `ix_product_name_prefix` (`lower(name)`) for the
name prefix. No external search infrastructure is used.

## Reports

All under `/api/v1/reports`, ADMIN or WAREHOUSE_MANAGER, aggregated in the database and limited to the caller's
warehouse scope. Dates are inclusive calendar days in the business time zone. Movement **direction** is defined by
the ledger: a quantity is inbound when it enters a scoped warehouse and outbound when it leaves one (a transfer
inside the scope counts as both), independent of the movement type.

| Report | Endpoint | Notes |
|---|---|---|
| 1. Stock movements | `GET /reports/stock-movements` | Filter by date, product, warehouse, type, responsible user. |
| 2. Top moved products | `GET /reports/top-products` | Inbound vs outbound volume per product over the period. |
| 3. Inbound vs outbound | `GET /reports/inbound-outbound` | Totals, net, count, plus a DAY/WEEK/MONTH breakdown. |
| 4. Inventory valuation | `GET /reports/inventory-valuation?groupBy=PRODUCT\|WAREHOUSE\|CATEGORY\|TOTAL` | See valuation note below. |
| 5. Warehouse performance | `GET /reports/warehouse-performance` | Products, stock, value (+share), inbound/outbound activity. |
| 6. Supplier performance | `GET /reports/supplier-performance` | Orders, purchased quantity and value, completed/partial/outstanding. |
| 7. Low stock | `GET /products/low-stock` (stage 4) | Products below minimum; `shortage` is the gap. |
| 8. Expiration | `GET /batches/expired`, `/batches/expiring?days=`, `/batches/expiration-risk?days=` (stage 6) | Expired, expiring, risk per product. |
| 9. Audit analysis | `GET /inventory-audits/discrepancies`, `/statistics`, `/statistics/by-warehouse` (stage 6) | Discrepancies and audit statistics. |

### Inventory valuation methodology

Value = `Σ quantity × unit cost`, where the unit cost is **each batch's `unit_cost` where known, otherwise the
product's `purchase_price`** — never the selling price. Because each batch carries its own cost and quantities are
summed, this is a quantity-weighted average cost at the product level, which is what the existing data model
supports (there is no separate moving-average cost ledger).

The value **assumes a single base currency** (the deployment's default, AZN); there is no FX conversion, so mixing
purchase orders in different currencies would mix units of money. `totalQuantity` is returned only for the
`PRODUCT` grouping — across warehouses, categories or the whole inventory, products use different units (pieces,
kg, …) and a summed quantity would be meaningless, so it is `null` there.

## Known limitations

- The import posts valid rows atomically per file (all-or-nothing for the set of *valid* rows); it is not a
  streaming importer, so it holds the file in memory (bounded by the 5 MB / 10 000-row limits).
- Reports 7–9 keep their original stage-4/6 endpoints rather than being duplicated under `/reports`.
- Warehouse and supplier performance rows are paged after aggregation; with hundreds of warehouses or suppliers a
  single page still aggregates the whole set in the database first.
