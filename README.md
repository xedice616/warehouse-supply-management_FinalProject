# Warehouse & Supply Management System (WMS)

A Spring Boot REST backend for products, warehouses, batch-tracked stock movements, suppliers, purchase
orders, inventory audits, CSV bulk import and analytical reports. Stock is an append-only ledger with a
balance projection, so quantities can never silently drift or go negative.

**Stack:** Java 21, Spring Boot 4, Spring Security + JWT, Spring Data JPA / Hibernate, PostgreSQL, Flyway,
MapStruct, Lombok, springdoc OpenAPI, JUnit 5 + Mockito. Build: Maven (wrapper included).

**Modules** (one package per bounded context under `com.anbar.wms`): `identity` (users, roles, JWT),
`catalog` (products, categories, units), `warehouse`, `supplier`, `inventory` (stock ledger, balances,
batches, FEFO, queries), `purchasing` (purchase orders, receiving), `audit` (inventory audits),
`importing` (CSV import), `reporting` (analytics), plus `common` (shared kernel) and `security`.

- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Stock movements & concurrency: [docs/STOCK-MOVEMENTS.md](docs/STOCK-MOVEMENTS.md)
- Procurement, batches & audits: [docs/PROCUREMENT-AND-AUDITS.md](docs/PROCUREMENT-AND-AUDITS.md)
- CSV import, search & reports: [docs/IMPORTS-AND-REPORTS.md](docs/IMPORTS-AND-REPORTS.md)
- Database schema and relationships: [docs/DATABASE.md](docs/DATABASE.md)
- Authentication, roles, example requests and Swagger walkthrough: [docs/SECURITY.md](docs/SECURITY.md)

## Requirements

- JDK 21 or newer (the build targets Java 21)
- PostgreSQL 15 or newer, e.g. a local install managed with pgAdmin 4

Maven itself is not required: use the included wrapper (`./mvnw` or `mvnw.cmd`). Docker is not required.

## Database setup (once)

On the PostgreSQL server, as the `postgres` superuser (pgAdmin → Query Tool):

1. Create an empty database for development, e.g. `warehouse_db`.
2. Inside it, run [docs/sql/local-postgres-setup.sql](docs/sql/local-postgres-setup.sql). Replace the two passwords
   first with `WMS_DB_OWNER_PASSWORD` and `WMS_DB_APP_PASSWORD` from `.env`. The script creates the `wms_owner`
   and `wms_app` roles and the `wms` schema.

Flyway creates every table when the application (or the test suite) starts. The application never uses the
`postgres` superuser.

## Configuration

All connection settings and secrets come from environment variables. Nothing is hardcoded. For local
development, put them in a git-ignored `.env` file:

```bash
cp .env.example .env
```

| Variable | Purpose |
|---|---|
| `WMS_DB_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:7878/warehouse_db` |
| `WMS_DB_OWNER_USER` / `WMS_DB_OWNER_PASSWORD` | Schema owner; Flyway runs migrations as this role |
| `WMS_DB_APP_USER` / `WMS_DB_APP_PASSWORD` | Runtime role; the application connects as this role (data only, no DDL) |
| `WMS_TEST_DB_URL` | Optional separate database for integration tests (default: `WMS_DB_URL`) |
| `WMS_DB_POOL_SIZE` | Connection pool size (default 10) |
| `WMS_BUSINESS_TIMEZONE` | Time zone for calendar rules such as expiry (default `Asia/Baku`) |
| `WMS_JWT_SECRET` | **Required.** Base64 HMAC key of at least 256 bits (`openssl rand -base64 48`) |
| `WMS_JWT_ACCESS_TOKEN_TTL` | Access token lifetime (default `PT15M`) |
| `WMS_BCRYPT_STRENGTH` | BCrypt cost (default 12) |
| `WMS_CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API (default none → cross-origin denied) |
| `WMS_IMPORT_MAX_FILE_SIZE` | Maximum CSV import upload size (default `5MB`) |
| `WMS_BOOTSTRAP_ADMIN_USERNAME` / `_EMAIL` / `_PASSWORD` | First administrator, created at startup only if no active ADMIN exists |

Profiles:

- `dev`: debug logging for application code and SQL.
- `prod`: Swagger UI is off and `flyway clean` is disabled. Provide every variable from the environment or a
  secret store.

## Run

From IntelliJ IDEA, run `WmsApplication` with the active profile `dev`. The working directory must be the project
root, so `.env` is found. From a terminal:

```bash
./mvnw package -DskipTests
```

```bash
java -jar target/wms-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8081
```

`./mvnw spring-boot:run` fails on Windows with `ClassNotFoundException` when the project path contains non-ASCII
characters (as `Anbar və Təchizat İdarəetmə Sistemi` does); the jar and the IDE are not affected. Port 8080 is
often taken by WSL's `wslrelay`, hence `8081`.

Then open:

- Swagger UI: http://localhost:8081/swagger-ui.html (see [docs/SECURITY.md](docs/SECURITY.md) for logging in)
- Health: http://localhost:8081/actuator/health

## Roles

Register via `POST /api/v1/auth/register`, then `POST /api/v1/auth/login` for a bearer token. Three roles:

- **ADMIN** — everything: users and roles, catalog, warehouses, suppliers, purchase orders, all stock
  operations, imports and reports.
- **WAREHOUSE_MANAGER** — their assigned warehouses only: stock movements, receiving, inventory audits,
  CSV import, reports.
- **USER** — read-only: products, product search, available stock in assigned warehouses.

The first administrator is bootstrapped from `WMS_BOOTSTRAP_ADMIN_*`. Admins manage accounts through the Users
endpoints (`GET /users/{id}`, `PATCH /users/{id}/status` to enable/disable, `POST /users/{id}/unlock`,
`PUT /users/{id}/password`, `PUT /users/{id}/roles`, `/warehouses`); users change their own password with
`POST /auth/change-password`. Full matrix and example requests: [docs/SECURITY.md](docs/SECURITY.md).

## Optional: run with Docker

Docker is **not** required — the steps above run the app against your local PostgreSQL. A `Dockerfile` and
`docker-compose.yml` are provided only as an optional, self-contained deployment that brings up PostgreSQL and
the app together, reading secrets from `.env` (set `POSTGRES_PASSWORD`, the `WMS_DB_*_PASSWORD` values, and
`WMS_JWT_SECRET`):

```bash
docker compose up --build
```

The database roles and `wms` schema are created on first start by `docker/init-db.sh`; Flyway then creates the
tables. The API is exposed on `http://localhost:8080`.

## API overview

All endpoints are under `/api/v1`, need a bearer token, and return RFC 9457 problems with a stable `code` on
errors. Collections accept `page`, `size` (≤ 100) and `sort` (`?sort=name,desc`, whitelisted per endpoint).

| Resource | Endpoints | Writes |
|---|---|---|
| Units of measure | `GET/POST /units`, `GET/PUT/DELETE /units/{id}`, `PATCH /units/{id}/status` | ADMIN |
| Categories | `GET/POST /categories`, `GET/PUT/DELETE /categories/{id}`, `PATCH /categories/{id}/status` | ADMIN |
| Products | `GET/POST /products`, `GET/PUT/DELETE /products/{id}`, `PATCH /products/{id}/status` | ADMIN |
| Product search | `GET /products/search` (sku, barcode, q, categoryId, active), `GET /products/sku/{sku}`, `GET /products/barcode/{barcode}`, `GET /warehouses/{id}/products/search` | none (read) |
| Warehouses | `GET/POST /warehouses`, `GET/PUT /warehouses/{id}`, `PATCH /warehouses/{id}/status` | ADMIN |
| Warehouse managers | `GET /warehouses/{id}/managers`, `PUT/DELETE /warehouses/{id}/managers/{userId}` | ADMIN |
| Warehouse access | `PUT /users/{id}/warehouses` | ADMIN |
| Stock (read-only) | `GET /warehouses/{id}/products`, `GET /warehouses/{id}/stock`, `GET /warehouses/{id}/stock/{productId}`, `GET /products/{id}/stock`, `GET /products/low-stock` | none |
| Stock movements | `POST /stock-movements/inbound`, `/outbound`, `/transfer`, `/write-off` (ADMIN, WAREHOUSE_MANAGER); `POST /stock-movements/adjustment` (ADMIN); `GET /stock-movements`, `/{id}`, `/product/{id}`, `/warehouse/{id}`, `/inbound`, `/outbound`, `/transfer` | see left |
| Suppliers | `GET/POST /suppliers`, `GET/PUT/DELETE /suppliers/{id}`, `PATCH /suppliers/{id}/status`, `GET /suppliers/{id}/stock-movements` | ADMIN (read: + WAREHOUSE_MANAGER) |
| Purchase orders | `GET/POST /purchase-orders`, `GET/PUT /purchase-orders/{id}`, `PATCH /purchase-orders/{id}/submit`, `/approve`, `/reject`, `/cancel`, `GET /purchase-orders/summary?groupBy=SUPPLIER|WAREHOUSE|STATUS` | ADMIN |
| Goods receipts | `POST /purchase-orders/{id}/receipts` (header `Idempotency-Key`) | ADMIN, WAREHOUSE_MANAGER |
| Batches (read-only) | `GET /batches`, `/batches/{id}`, `/batches/{id}/movements`, `/batches/product/{id}`, `/batches/warehouse/{id}`, `/batches/expired`, `/batches/expiring?days=`, `/batches/expiration-risk?days=` | none |
| Inventory audits | `GET/POST /inventory-audits`, `GET /inventory-audits/{id}`, `POST /{id}/items`, `PUT /{id}/items/{itemId}/count`, `PATCH /{id}/start`, `/submit`, `/cancel` (ADMIN, WAREHOUSE_MANAGER); `PATCH /{id}/approve`, `/reject`, `/complete` (ADMIN); reports `GET /inventory-audits/discrepancies`, `/statistics`, `/statistics/by-warehouse` | see left |
| CSV import | `POST /imports/stock-inbound` (multipart `file`, header `Idempotency-Key`) | ADMIN, WAREHOUSE_MANAGER |
| Reports | `GET /reports/stock-movements`, `/top-products`, `/inbound-outbound`, `/inventory-valuation`, `/warehouse-performance`, `/supplier-performance` | ADMIN, WAREHOUSE_MANAGER |

Rules worth knowing:

- **Stock changes only through stock movements.** Each movement is an immutable ledger record (no update or delete
  endpoint). The balance table is a projection of the ledger, updated in the same transaction by a single atomic SQL
  statement per warehouse (`UPDATE ... WHERE quantity >= :qty`), so concurrent withdrawals can never overdraw stock.
  See [docs/STOCK-MOVEMENTS.md](docs/STOCK-MOVEMENTS.md).
- **SKU and warehouse code are permanent identifiers.** They are stored upper-case, unique ignoring case, and
  cannot be changed. A barcode, if given, is unique too.
- **Deleting vs. deactivating:**
  - A product, category or unit is physically deleted only if nothing references it; otherwise the API returns
    `409 RESOURCE_IN_USE`, and you deactivate it instead.
  - Deactivation is refused while stock is on hand (`409 STOCK_ON_HAND`).
  - Inactive products, categories, units and warehouses can't be used for anything new (`422 INACTIVE_RESOURCE`).
- **A product's unit and batch/expiry tracking are fixed once it has stock movements** (`409 STOCK_HISTORY_EXISTS`).
- **Updates (`PUT`) require the `version` from the last read;** a stale one gets `409 CONCURRENT_MODIFICATION`.
- **Visibility:** ADMIN sees every warehouse. Everyone else sees only the warehouses assigned to them;
  other warehouses answer `404`. Products, categories and units are readable by every authenticated user.
- **Procurement and audits** (purchase order workflow, idempotent receiving, batches, FEFO, expiration reports,
  inventory audits): see [docs/PROCUREMENT-AND-AUDITS.md](docs/PROCUREMENT-AND-AUDITS.md).
- **CSV bulk import, product search and analytical reports** (stage 7): see
  [docs/IMPORTS-AND-REPORTS.md](docs/IMPORTS-AND-REPORTS.md). Reports 7–9 (low stock, expiration, audit analysis)
  reuse the existing `/products/low-stock`, `/batches/*` and `/inventory-audits/*` endpoints.

## Tests

```bash
./mvnw test
```

Unit, web-slice and architecture tests. No database needed.

```bash
./mvnw verify
```

Also runs the `*IT` integration tests against the local PostgreSQL from `.env` (`WMS_TEST_DB_URL` overrides it),
with the production role split (Flyway as `wms_owner`, the app as `wms_app`). Tests use unique names and assert only
on their own data, but the data they create stays: test users (`reg-…`, `login-…`), products, warehouses and stock
movements, which are append-only and can never be deleted. Point `WMS_TEST_DB_URL` at a separate database
([docs/sql/local-postgres-test-db.sql](docs/sql/local-postgres-test-db.sql)) to keep development data clean.
The tests manage their own `it-admin` account with a new random password on every run.