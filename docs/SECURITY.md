# Authentication and authorization

The API is stateless: every request to a private endpoint carries a JWT access token in the
`Authorization: Bearer <token>` header. There are no sessions or cookies.

## How it works

```
POST /api/v1/auth/login ──► AuthService ──► BCrypt check ──► JwtService.issue() ──► { accessToken, expiresIn }

GET /api/v1/... + Bearer token
   │
   ▼
CorrelationIdFilter ─► JwtAuthenticationFilter ─► Spring Security authorization ─► controller (@PreAuthorize)
                         │  verify signature, iss, aud, exp
                         │  load the user from the DB: must exist and be ACTIVE; take the current roles
                         └─ invalid/expired → request stays anonymous → 401 from the entry point
```

| Piece | Where |
|---|---|
| Filter chain, public URLs, BCrypt encoder | `security/config/SecurityConfig.java` |
| Token issue and verification (JJWT, HS256) | `security/jwt/JwtService.java` |
| Custom bearer filter | `security/jwt/JwtAuthenticationFilter.java` |
| 401 and 403 JSON responses | `security/web/ProblemAuthenticationEntryPoint.java`, `ProblemAccessDeniedHandler.java` |
| Register, login, current user | `identity/service/AuthService.java`, `identity/controller/AuthController.java` |
| User administration (ADMIN) | `identity/service/UserAdminService.java`, `identity/controller/UserAdminController.java` |
| First administrator | `identity/service/AdminBootstrap.java` |
| Settings | `wms.security.*` in `application.yml`; secrets from `.env` or environment variables |

### Design decisions

- **Passwords** are hashed with BCrypt, cost 12 (`WMS_BCRYPT_STRENGTH`). Hashes are stored as
  `{bcrypt}$2a$12$…`, so the algorithm can be upgraded later. The plain password is never stored, logged or
  returned. Request DTOs mask it in `toString()`, and no response DTO has a password or hash field.
- **Password policy:** 10–64 characters, at least one letter and one digit, at most 72 bytes in UTF-8
  (BCrypt's limit), and no leading or trailing spaces.
- **Tokens:**
  - HS256, signed with `WMS_JWT_SECRET` (Base64, at least 256 bits; the app refuses to start otherwise).
  - Valid for 15 minutes (`WMS_JWT_ACCESS_TOKEN_TTL`).
  - Claims: `sub` (user id), `username`, `roles`, `iss=wms`, `aud=wms-api`, `iat`, `exp`, `jti`.
  - Unsigned (`alg: none`) and otherwise-signed tokens are rejected.
- **The database is the authority, not the token.** On every request the filter loads the user. Disabling an
  account or changing roles therefore takes effect on the next request, without waiting for tokens to expire.
  The cost is one indexed lookup per request.
- **Brute-force protection:** 5 wrong passwords lock the account for 15 minutes. The counter is updated with an
  atomic SQL statement, so parallel attempts can't slip past it. While the account is locked, the password isn't
  even checked.
- **No user enumeration at login:** an unknown user and a wrong password both return `INVALID_CREDENTIALS`.
  Unknown users still cost one BCrypt comparison, so timing doesn't reveal which accounts exist. A disabled
  account is only revealed to someone who knows its password.
- **Self-registration always gets USER.** The request has no roles field, and unknown JSON fields are rejected,
  so `"roles": ["ADMIN"]` fails with 400. Only an ADMIN can grant roles.
- **The last active ADMIN** can't lose the role (`LAST_ADMIN_REQUIRED`). Role changes are serialized with a row
  lock, so two admins can't demote each other at the same time and leave none.
- **CSRF is disabled** because nothing authenticates through cookies; browsers never send the `Authorization`
  header on their own. If cookie-based auth is ever added, CSRF protection must come back.

## Roles

| Role | Intended permissions |
|---|---|
| `ADMIN` | Everything: users and roles, products and categories, warehouses, suppliers, purchase orders, inventory operations, all reports |
| `WAREHOUSE_MANAGER` | Assigned warehouses: view them, permitted stock movements, receive purchase orders, inventory audits, warehouse reports |
| `USER` | Read-only: products, available stock, permitted warehouse information |

Endpoints that exist so far:

| Endpoint | Anonymous | USER | WAREHOUSE_MANAGER | ADMIN |
|---|---|---|---|---|
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | ✔ | ✔ | ✔ | ✔ |
| `GET /api/v1/auth/me` | 401 | ✔ | ✔ | ✔ |
| `GET /api/v1/users`, `PUT /api/v1/users/{id}/roles`, `PUT /api/v1/users/{id}/warehouses` | 401 | 403 | 403 | ✔ |
| `GET` units, categories, products | 401 | ✔ | ✔ | ✔ |
| `POST/PUT/PATCH/DELETE` units, categories, products | 401 | 403 | 403 | ✔ |
| `GET /api/v1/warehouses`, `GET /api/v1/warehouses/{id}` | 401 | assigned only | assigned only | ✔ all |
| `POST/PUT/PATCH` warehouses, `/warehouses/{id}/managers` | 401 | 403 | 403 | ✔ |
| Stock queries (`/warehouses/{id}/stock`, `/warehouses/{id}/products`, `/products/{id}/stock`, `/products/low-stock`) | 401 | assigned warehouses | assigned warehouses | ✔ all |
| `POST /stock-movements/inbound`, `outbound`, `transfer`, `write-off` | 401 | 403 | assigned warehouses (both for transfers) | ✔ |
| `POST /stock-movements/adjustment` | 401 | 403 | 403 | ✔ |
| `GET /stock-movements/**` (history) | 401 | movements touching assigned warehouses | same | ✔ all |
| `GET /suppliers/**` | 401 | 403 | ✔ | ✔ |
| `POST/PUT/PATCH/DELETE /suppliers/**` | 401 | 403 | 403 | ✔ |
| `GET /purchase-orders/**` (list, detail, summary) | 401 | orders of assigned warehouses | same | ✔ all |
| `POST/PUT /purchase-orders`, `PATCH .../submit`, `/approve`, `/reject`, `/cancel` | 401 | 403 | 403 | ✔ |
| `POST /purchase-orders/{id}/receipts` | 401 | 403 | orders of assigned warehouses | ✔ |
| `GET /batches/**` | 401 | assigned warehouses | assigned warehouses | ✔ all |
| `GET /inventory-audits/**` (incl. reports) | 401 | assigned warehouses | assigned warehouses | ✔ all |
| `POST/PUT /inventory-audits/**`, `PATCH .../start`, `/submit`, `/cancel` | 401 | 403 | assigned warehouses | ✔ |
| `PATCH /inventory-audits/{id}/approve`, `/reject`, `/complete` | 401 | 403 | 403 | ✔ |
| `GET /products/search`, `/products/sku/{sku}`, `/products/barcode/{barcode}` | 401 | ✔ | ✔ | ✔ |
| `GET /warehouses/{id}/products/search` | 401 | assigned warehouses | assigned warehouses | ✔ all |
| `POST /imports/stock-inbound` | 401 | 403 | assigned warehouses (per row) | ✔ |
| `GET /reports/**` | 401 | 403 | assigned warehouses | ✔ all |
| `GET /actuator/health`, `/v3/api-docs`, `/swagger-ui.html` | ✔ | ✔ | ✔ | ✔ |

"Assigned" means the warehouses in `user_warehouse_access`, set by an ADMIN through
`PUT /api/v1/warehouses/{id}/managers/{userId}` (WAREHOUSE_MANAGER role required) or
`PUT /api/v1/users/{id}/warehouses` (any user). A warehouse outside your scope answers `404`, the same as one
that doesn't exist. The scope is read from the database on every request, so changes apply immediately.

## Error responses

All errors are `application/problem+json` with a stable `code`:

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Invalid fields; `errors` lists each one |
| 400 | `MALFORMED_REQUEST` | Bad JSON, a wrong type, or an unknown property |
| 401 | `AUTHENTICATION_REQUIRED` | No bearer token on a private endpoint |
| 401 | `INVALID_TOKEN` | Bad signature, malformed token, wrong issuer/audience, or a disabled/deleted user |
| 401 | `TOKEN_EXPIRED` | Valid token past its `exp`: log in again |
| 401 | `INVALID_CREDENTIALS` | Unknown user or wrong password |
| 401 | `ACCOUNT_LOCKED` | Too many failed logins; try again later |
| 401 | `ACCOUNT_DISABLED` | Correct password, but the account is disabled |
| 403 | `ACCESS_DENIED` | Authenticated, but the role doesn't allow it |
| 409 | `EMAIL_ALREADY_REGISTERED`, `USERNAME_ALREADY_TAKEN` | Registration conflicts |
| 409 | `LAST_ADMIN_REQUIRED` | Removing ADMIN from the last active administrator |

401 responses also carry `WWW-Authenticate: Bearer realm="wms"`, plus `error="invalid_token"` when a token was
rejected (RFC 6750).

```json
{
  "type": "urn:wms:problem:invalid-credentials",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Invalid username, email or password",
  "instance": "/api/v1/auth/login",
  "code": "INVALID_CREDENTIALS",
  "timestamp": "2026-11-07T12:41:27.738Z",
  "correlationId": "08090dc9-9dc5-439a-89b4-9943fb2731ae"
}
```

## Example requests

These assume the app runs on `http://localhost:8080`. Use your port if you changed it (`--server.port=8081`).

Register:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d '{"username":"aysel.m","email":"aysel@example.com","password":"Warehouse2026!","fullName":"Aysel Mammadova"}'
```

Response `201 Created`:

```json
{"id": 2, "username": "aysel.m", "email": "aysel@example.com", "fullName": "Aysel Mammadova",
 "status": "ACTIVE", "roles": ["USER"], "warehouses": [], "createdAt": "2026-11-07T12:41:26.146Z", "lastLoginAt": null}
```

Log in, with the username or the email:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"usernameOrEmail":"aysel.m","password":"Warehouse2026!"}'
```

Response `200 OK` (with `Cache-Control: no-store`):

```json
{"accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900, "expiresAt": "2026-11-07T12:56:26Z"}
```

Current user:

```bash
curl http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

As an ADMIN, list users and promote one:

```bash
curl "http://localhost:8080/api/v1/users?page=0&size=20&sort=username,asc" -H "Authorization: Bearer <adminToken>"
```

```bash
curl -X PUT http://localhost:8080/api/v1/users/2/roles -H "Authorization: Bearer <adminToken>" -H "Content-Type: application/json" -d '{"roles":["WAREHOUSE_MANAGER"]}'
```

## Testing in Swagger UI

1. Start the app and open `http://localhost:8080/swagger-ui.html`. Swagger is enabled outside the `prod` profile.
2. **Authentication → POST /api/v1/auth/register → Try it out.** Edit the example body and click **Execute**.
   You get `201` and the new user. Register and login are public, so they show no padlock.
3. **POST /api/v1/auth/login → Try it out.** Enter the username (or email) and password, then **Execute**.
   Copy the `accessToken` value from the response, without the quotes.
4. Click **Authorize** (top right). Paste the token into **bearerAuth**; Swagger adds the `Bearer ` prefix
   itself. Click **Authorize**, then **Close**.
5. **GET /api/v1/auth/me → Try it out → Execute.** You get `200` with your user. Every call now sends the
   token automatically.
6. Try the role rules:
   - As this USER, **GET /api/v1/users** returns `403 ACCESS_DENIED`.
   - Click **Authorize → Logout**, log in as the bootstrap admin (`WMS_BOOTSTRAP_ADMIN_*` in `.env`) and
     authorize with that token. **GET /api/v1/users** now returns `200`.
   - Call **PUT /api/v1/users/{id}/roles** to make your first user a `WAREHOUSE_MANAGER`. That user's existing
     token shows the new role on its next `/me` call.
7. Try the failure cases:
   - **Logout**, then call `/me`: `401 AUTHENTICATION_REQUIRED`.
   - Authorize with a mangled token: `401 INVALID_TOKEN`.
   - After 15 minutes, the old token gets `401 TOKEN_EXPIRED`.

## Not included yet

- **Refresh tokens.** Clients log in again when the 15-minute token expires. Refresh-token rotation with
  reuse detection was planned in the architecture and needs a `refresh_token` table.
- **Logout and token revocation** beyond disabling the account. With refresh tokens, logout would revoke the
  refresh token.
- **Rate limiting** on `/auth/*`, planned for the hardening stage. The account lockout covers password guessing
  per account, not credential stuffing across many accounts.
- **Admin endpoints** to disable or enable users and assign warehouses.
