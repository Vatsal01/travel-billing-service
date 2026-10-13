# travel-billing-service

An HTTP service for structured travel commerce transactions — **flight**, **hotel**, **rail** and
platform fees (`navan_fee`) — and for turning them into **invoices that are frozen snapshots** of
what was billed.

Java 21 · Spring Boot 4.0.8 · Spring JDBC · Flyway · H2 (file-backed)

---

## Requirements

- JDK 21 or newer (verified on Temurin 25 and OpenJDK 26)
- No database to install, no Docker, no network access needed after the first build

## Run the API

```bash
./mvnw spring-boot:run
```

Serves `http://localhost:8080` and creates a real database file at `./data/billing.mv.db`,
migrated by Flyway on startup. Data survives a restart.

```bash
curl -s localhost:8080/health
# {"status":"UP","database":"UP"}
```

## Run the tests

```bash
./mvnw test
```

52 tests. The API tests start the service on a random port and drive it over real HTTP against a
real file-backed database — the same unique index, row locks and snapshot tables that run in
production.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `BILLING_DB_PATH` | `./data/billing` | Database file path (no extension) |
| `BILLING_DB_USER` / `BILLING_DB_PASSWORD` | `sa` / empty | Database credentials |

---

## API

| Method | Path | Behaviour |
|---|---|---|
| `POST` | `/transactions` | 201 with the stored transaction. **409** if `external_id` exists, carrying `existing_id` |
| `GET` | `/transactions/{id}` | 200 with `type`, `metadata`, `tax_lines`, `fee_lines`. 404 if missing |
| `GET` | `/transactions` | Paginated. `limit` (default 50, max 100), `offset`. Never unbounded |
| `PATCH` | `/transactions/{id}` | Updates stored fields. 404 if missing. **409 if already invoiced**, writing nothing |
| `DELETE` | `/transactions/{id}` | 204. 404 if missing. **409 if already invoiced**, deleting nothing |
| `POST` | `/invoices` | 201. Snapshots the transactions. 404 unknown id, 409 mixed currency, 409 duplicate id |
| `GET` | `/invoices/{id}` | 200 with header, snapshots, tax rollup, fee rollup, totals |
| `GET` | `/invoices` | Paginated headers (same limits) |
| `PATCH` | `/invoices/{id}` | Replaces the billed set and rebuilds the snapshot atomically |
| `DELETE` | `/invoices/{id}` | 204. Deletes the invoice and its snapshot, **never the transactions** |
| `GET` | `/health` | Liveness plus a database round trip |

Errors are RFC 9457 problem documents with a stable `code`:

```json
{
  "status": 409,
  "title": "Conflict",
  "detail": "A transaction with external_id 'flight-TXL-LHR-2026-03-11' already exists",
  "code": "DUPLICATE_EXTERNAL_ID",
  "external_id": "flight-TXL-LHR-2026-03-11",
  "existing_id": "txn_01a125c2-9e6b-7348-8962-e2f06dedd6b0"
}
```

Every code, so a client can branch on `code` instead of matching on prose:

| `code` | Status | Raised when | Extra members |
|---|---|---|---|
| `MALFORMED_REQUEST` | 400 | Unparseable JSON, or an unknown `type` / `fee_kind` | — |
| `VALIDATION_FAILED` | 400 | Bean validation, bad metadata, bad `limit`/`offset`, bad invoice body shape | `errors` |
| `IMMUTABLE_FIELD` | 400 | A PATCH tried to change `type`, `external_id` or `currency` | `field` |
| `NO_UPDATABLE_FIELDS` | 400 | Empty PATCH body | `updatable_fields` |
| `TRANSACTION_NOT_FOUND` | 404 | Unknown transaction id, on any path | — |
| `INVOICE_NOT_FOUND` | 404 | Unknown invoice id | — |
| `DUPLICATE_EXTERNAL_ID` | 409 | `external_id` already stored | `external_id`, `existing_id` |
| `TRANSACTION_ALREADY_INVOICED` | 409 | PATCH or DELETE of a billed transaction | `transaction_id`, `invoice_ids` |
| `DUPLICATE_TRANSACTION_ON_INVOICE` | 409 | The same id listed twice on one invoice | `transaction_ids` |
| `MIXED_CURRENCY` | 409 | The invoice set spans more than one currency | `currencies` |
| `LOCK_TIMEOUT` | 409 | A row lock could not be taken; nothing was written, retry | — |
| `CONSTRAINT_VIOLATION` | 409 | A database constraint a concurrent change beat the service to | — |
| `DATA_ACCESS_FAILURE` | 500 | Any other database failure, with a fixed message so nothing internal leaks | — |

---

## Walkthrough

The bodies below are the published fixtures, which also live in [`fixtures/`](fixtures/).
Run them in order; each step uses ids from the previous one.

### Create one of each type

```bash
# flight — origin/destination, fare, coupons (one per flown segment)
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "flight",
  "external_id": "flight-TXL-LHR-2026-03-11",
  "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z",
  "total": 220.00,
  "tax_lines": [{ "name": "Airport tax", "amount": 15.00 }],
  "fee_lines": [{ "name": "YQ", "amount": 25.00 }],
  "metadata": {
    "origin": "TXL", "destination": "LHR", "fare": 180.00,
    "coupons": [{
      "carrier": "BA", "flight_number": "BA982", "from": "TXL", "to": "LHR",
      "departure": "2026-03-11T07:00:00Z", "arrival": "2026-03-11T08:20:00Z"
    }]
  }
}'

# hotel — property name and postal address
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "hotel",
  "external_id": "hotel-LON-2026-03-11",
  "currency": "EUR",
  "occurred_at": "2026-03-11T15:00:00Z",
  "total": 331.70,
  "tax_lines": [{ "name": "VAT", "rate": 0.07, "amount": 21.70 }],
  "fee_lines": [],
  "metadata": {
    "name": "Hotel Mitte London",
    "address": { "street": "12 Kingsway", "city": "London", "country": "GB" },
    "check_in": "2026-03-11", "check_out": "2026-03-13",
    "nights": 2, "room_rate_per_night": 140.00,
    "extras": [{ "name": "Breakfast", "amount": 30.00 }]
  }
}'

# rail — a change of train is two legs
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "rail",
  "external_id": "rail-STP-PAR-2026-03-13",
  "currency": "EUR",
  "occurred_at": "2026-03-13T09:00:00Z",
  "total": 94.00,
  "tax_lines": [],
  "fee_lines": [{ "name": "Booking fee", "amount": 4.00 }],
  "metadata": {
    "legs": [
      { "origin": "London St Pancras", "destination": "Lille Europe",
        "departure": "2026-03-13T09:00:00Z", "arrival": "2026-03-13T10:30:00Z",
        "train_number": "EST9108", "class": "standard" },
      { "origin": "Lille Europe", "destination": "Paris Nord",
        "departure": "2026-03-13T11:00:00Z", "arrival": "2026-03-13T12:05:00Z",
        "train_number": "EST9122", "class": "standard" }
    ]
  }
}'

# trip fee — a first-class transaction, not a line on the flight
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee",
  "external_id": "navan-trip-fee-2026-03-11",
  "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z",
  "total": 25.00,
  "tax_lines": [], "fee_lines": [],
  "metadata": {
    "fee_kind": "trip_fee", "description": "Trip fee",
    "related_external_id": "flight-TXL-LHR-2026-03-11"
  }
}'

# agent call fee — the fee for a support call
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee",
  "external_id": "navan-agent-call-2026-03-11",
  "currency": "EUR",
  "occurred_at": "2026-03-11T18:00:00Z",
  "total": 15.00,
  "tax_lines": [], "fee_lines": [],
  "metadata": {
    "fee_kind": "agent_call_fee", "description": "Agent fee for support call",
    "related_external_id": "hotel-LON-2026-03-11"
  }
}'
```

An unknown `type`, or an unknown `navan_fee.fee_kind`, is a 400 and stores nothing:

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"spaceship","external_id":"x","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z",
       "total":1.00,"tax_lines":[],"fee_lines":[],"metadata":{}}'
# 400 "Unknown transaction type 'spaceship'; expected one of [flight, hotel, rail, navan_fee]"
```

### Duplicate create: post the same `external_id` twice, get one row

```bash
# Same external_id as the flight above.
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' -d '{
  "type": "flight", "external_id": "flight-TXL-LHR-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z", "total": 220.00,
  "tax_lines": [{ "name": "Airport tax", "amount": 15.00 }],
  "fee_lines": [{ "name": "YQ", "amount": 25.00 }],
  "metadata": { "origin": "TXL", "destination": "LHR", "fare": 180.00, "coupons": [] }
}'
# 409, body carries existing_id

# Exactly one row is stored:
curl -s 'localhost:8080/transactions?limit=100' \
  | python3 -c 'import sys,json; items=json.load(sys.stdin)["items"]; \
    print(sum(1 for i in items if i["external_id"]=="flight-TXL-LHR-2026-03-11"), "row(s)")'
# 1 row(s)
```

### Read and list

```bash
FLIGHT_ID=$(curl -s 'localhost:8080/transactions?limit=100' \
  | python3 -c 'import sys,json; print(next(i["id"] for i in json.load(sys.stdin)["items"] if i["type"]=="flight"))')

curl -s "localhost:8080/transactions/$FLIGHT_ID"          # coupons, flight number, tax and fee lines
curl -s 'localhost:8080/transactions?limit=2&offset=0'    # {"items":[...],"limit":2,"offset":0,"has_more":true}
curl -s -o /dev/null -w '%{http_code}\n' 'localhost:8080/transactions?limit=1000'   # 400: max is 100
```

### Invoice by transaction id

```bash
# A whole trip: flight + hotel + rail + both fees. Grand total 685.70 EUR.
IDS=$(curl -s 'localhost:8080/transactions?limit=100' \
  | python3 -c 'import sys,json; print(json.dumps([i["id"] for i in json.load(sys.stdin)["items"]]))')

INVOICE_ID=$(curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\": $IDS}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

curl -s "localhost:8080/invoices/$INVOICE_ID"   # snapshots + tax_rollup + fee_rollup + totals
curl -s 'localhost:8080/invoices?limit=50'      # headers only, never every billed line

# A single id is also accepted:
curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_id\": \"$FLIGHT_ID\"}"
```

### Patch and delete a transaction (409 once it is invoiced)

```bash
# The flight is now billed, so its money is frozen:
curl -s -X PATCH "localhost:8080/transactions/$FLIGHT_ID" \
  -H 'Content-Type: application/json' -d '{"total": 999.00}'
# 409 TRANSACTION_ALREADY_INVOICED, listing the invoice_ids that block it. Nothing is written.

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "localhost:8080/transactions/$FLIGHT_ID"
# 409. The row is still there.

# An unbilled transaction patches and deletes normally:
NEW_ID=$(curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type":"navan_fee","external_id":"navan-trip-fee-2026-04-01","currency":"EUR",
  "occurred_at":"2026-04-01T09:00:00Z","total":25.00,"tax_lines":[],"fee_lines":[],
  "metadata":{"fee_kind":"trip_fee"}}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

curl -s -X PATCH "localhost:8080/transactions/$NEW_ID" -H 'Content-Type: application/json' \
  -d '{"total": 30.00, "tax_lines": [{"name":"VAT","rate":0.19,"amount":4.79}]}'
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "localhost:8080/transactions/$NEW_ID"   # 204
```

### Patch and delete an invoice

```bash
# Replace the billed set; the snapshot is rebuilt from the transactions as they are now.
curl -s -X PATCH "localhost:8080/invoices/$INVOICE_ID" -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\": [\"$FLIGHT_ID\"]}"

# Mixed currencies are refused and the invoice is left exactly as it was:
#   409 MIXED_CURRENCY

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "localhost:8080/invoices/$INVOICE_ID"   # 204
curl -s -o /dev/null -w '%{http_code}\n' "localhost:8080/transactions/$FLIGHT_ID"          # 200 — transactions survive
```

---

## Decisions worth stating

Where the specification offered a choice, this is the choice and the reason.

| Decision | Choice |
|---|---|
| Duplicate `external_id` | **409 Conflict** with `existing_id` in the body. A retry is told plainly that nothing new was stored, rather than being handed a 200 that looks like a fresh write |
| Same transaction twice on one invoice | **409**, not silent de-duplication. Nothing is ever double-counted, and the client learns its request was wrong |
| Money | `BigDecimal`, `DECIMAL(19,2)`, `HALF_UP` only where a value is normalised. A request with more than two decimal places is **rejected (400)**, never silently rounded. Tax rates are `DECIMAL(9,6)` — a rate is not money |
| `limit` above 100 | **400** with the bound in the message, rather than quietly capping, so a client paging by a limit it chose cannot silently skip rows |
| Pagination | `limit`/`offset` with `has_more`. No `total_count`: a `COUNT(*)` on every list request is the first query to hurt as the table grows |
| `PATCH /transactions/{id}` | Applies the fields present; lists are **replaced**, not merged, because merging makes removing a line impossible. `type`, `external_id` and `currency` are immutable and an attempt to change them is a 400 |
| Empty patch body | 400, rather than a no-op 200 that hides a client-side typo |
| Transactions per invoice | Capped at 200. Building an invoice locks every transaction it bills, so an unbounded list is an easy way for one request to stall every other writer |
| Transaction on two invoices | Allowed. The specification does not forbid it, and both invoices keep independent snapshots |
| `metadata` storage | Validated against a typed model per `type`, then stored as the client's own JSON document, so fields the API does not model yet survive the round trip |
| `navan_fee` | The wire contract from the specification is kept verbatim (`navan_fee`, `trip_fee`, `agent_call_fee`). It is the only vendor-specific token in the project |
| Rollup ordering | Sorted by name, and by rate within a name. `expected-invoice.json` happens to list the fees in appearance order; the grouping and the amounts match, the sequence is not part of the contract |
| Charge lines per transaction | Capped at 50 (`CreateTransactionRequest.MAX_CHARGE_LINES`). A list bounded by rows is not bounded at all if one row can be arbitrarily large: without this, one request writes an unbounded number of rows in a single database transaction and a later page carries them all back out |
| `metadata` size | Capped at 64 KB. The column is a CLOB returned verbatim on every read, so an unbounded document is an unbounded row and an unbounded response |
| A contended row lock | **409** with its own `LOCK_TIMEOUT` code, not a 500. The service is healthy and nothing was written — one row is busy, and the identical request will usually succeed on a retry |

## Where the required tests live

| Required test | Test |
|---|---|
| 1. Create each type; GET returns coupons / address / legs / `fee_kind` | `TransactionApiTest.createsFlightWithCoupons`, `createsHotelWithNameAndAddress`, `createsRailWithTwoLegs`, `createsBothFeeKinds` |
| 2. Duplicate `external_id` stores no second row | `TransactionApiTest.rejectsDuplicateExternalId` |
| 3. Trip invoice totals match the published figures (`685.70` EUR) | `InvoiceApiTest.invoicesAWholeTrip` |
| 4. Snapshot: changing the flight afterwards leaves `220.00` billed | `InvoiceApiTest.snapshotIsFrozenAgainstLaterChanges` |
| 5. Mixed currency is 409 | `InvoiceApiTest.refusesMixedCurrencies` |
| 6. Concurrent duplicate create leaves one row | `ConcurrentCreateTest.concurrentCreatesOfTheSameExternalIdStoreOneRow` |
| 7. PATCH an invoiced transaction is 409, totals unchanged | `InvoiceApiTest.refusesToChangeABilledTransaction` |
| 8. DELETE an invoiced transaction is 409, row survives | `InvoiceApiTest.refusesToDeleteABilledTransaction` |
| 9. DELETE invoice keeps the transactions | `InvoiceApiTest.deletingAnInvoiceKeepsTheTransactions` |
| 10. List defaults to 50 and `limit=1000` is refused | `TransactionApiTest.listIsAlwaysBounded`, `InvoiceApiTest.invoiceListIsAlsoBounded` |

Two of the required tests pull in opposite directions: test 7 demands that the API refuse to rewrite
a billed transaction, while test 4 demands that a billed transaction be changed and the invoice stay
put. Test 4 therefore changes the row **directly in the database**, underneath the service. That is
also the stronger assertion: it proves the invoice is a copy rather than a live join, whatever else
touches the table.

Design, data model and the behaviour under load: [`ARCHITECTURE.md`](ARCHITECTURE.md).
