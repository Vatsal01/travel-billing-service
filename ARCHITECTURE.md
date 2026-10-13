# Architecture

## Shape

Four seams, each with one job. Nothing in this service is a god class, and nothing holds state in
process memory.

```
web/          HTTP only: bind, validate, map, choose the status code
  dto/        the wire contract, with its own validation constraints
transaction/  identity and the rules on stored transactions
  metadata/   the typed per-type contracts (flight / hotel / rail / fee) and their validation
invoice/      building, freezing and rebuilding invoices
common/       money scale, id generation, error codes, timestamp conversion
```

Dependencies run one way: `web → transaction/invoice → common`, and `invoice → transaction`
(an invoice copies transactions). The one edge that would close a cycle — the transaction module
needing to know whether a transaction has been billed — goes through the `BilledTransactions`
interface, declared in `transaction` and implemented by `InvoiceSnapshotRepository`. The
transaction module never learns what an invoice is.

**Spring JDBC, not an ORM.** Every requirement here is a database-level guarantee: a unique index,
an atomic multi-table write, row locks, a snapshot that must never silently become a live join, and
list queries that must carry a `LIMIT`. Explicit SQL puts those guarantees in the open with no
identity map, lazy loading or dirty-checking between the code and the data. Flyway owns the schema;
there is no auto-DDL anywhere.

**Errors are part of the contract, not an afterthought.** Every failure is an RFC 9457 problem
document carrying a stable `code` beside the human-readable `detail`, so a client branches on the
code and never has to match on prose. Where a failure has a useful payload — the id of the
transaction that already exists, the currencies that clashed, the invoices blocking an edit — it is a
member of the problem document rather than something to parse out of a sentence. `ErrorCode` holds
the complete set with the status each maps to, so the whole error contract reads in one place.

**Timestamps come from an injected `Clock`,** not `Instant.now()`, so a test can pin time and assert
on it instead of asserting around it.

## Tables and models

| Table | Holds |
|---|---|
| `transactions` | id, `external_id`, type, currency, `occurred_at`, gross `total`, `metadata`, timestamps |
| `transaction_tax_lines` | `(transaction_id, line_no)`, name, **rate**, amount |
| `transaction_fee_lines` | `(transaction_id, line_no)`, name, amount |
| `invoices` | id, currency, `total`, `tax_total`, `fee_total`, `line_count`, timestamps |
| `invoice_lines` | **snapshot** of one billed transaction: type, currency, total, `metadata`, `external_id` |
| `invoice_line_tax_lines` | snapshot of that line's tax lines |
| `invoice_line_fee_lines` | snapshot of that line's fee lines |

**Taxes and fees are separate tables, not one extras column.** They are different things: a tax is
levied by an authority and may carry a rate (`VAT @ 0.07`), a fee is charged by a carrier or
merchant and never has one (`YQ`, `Booking fee`). The Java model says the same thing — `TaxLine` has
a rate, `FeeLine` does not — so nothing can accidentally sum one bucket. They stay separate at every
level: on the transaction, in the snapshot, and in the two rollups on the invoice. Ordering is part
of the data, so each line carries a `line_no` and the pair `(transaction_id, line_no)` is the
primary key: no surrogate id, and the key itself indexes lookups by transaction.

**Type-specific data is a validated JSON document in `metadata`.** Everything the service queries,
constrains, sums or indexes — identity, currency, totals, tax and fee lines — is a real column or a
real table. What is left is per-type descriptive detail that is written once and read back whole,
and modelling flight coupons and rail legs as their own tables would add joins and mapping for data
no query filters on.

It is not untyped, though. Each type has a record that is the contract, and a request is parsed and
validated against it before anything is stored:

| `type` | Model | Required shape |
|---|---|---|
| `flight` | `FlightMetadata` | `origin`, `destination`, `fare`, `coupons[]` — each coupon with `flight_number`, `from`, `to`, `departure`, `arrival`, optional `carrier` |
| `hotel` | `HotelMetadata` | `name`, `address{street, city, country}`, `check_in`, `check_out`, `nights`, `room_rate_per_night`, optional `extras[]` |
| `rail` | `RailMetadata` | `legs[]`, at least one — each with `origin`, `destination`, `departure`, `arrival`, optional `train_number`, `class` |
| `navan_fee` | `NavanFeeMetadata` | `fee_kind` ∈ {`trip_fee`, `agent_call_fee`}, optional `description`, `related_external_id` |

So an unknown `type`, an unknown `fee_kind`, a rail journey with no legs or a hotel with no address
is a 400 and stores nothing — never an empty `{}` and never a flight stored as a hotel blob. What is
then persisted is the client's own document rather than a re-serialisation of the parsed record, so
a field the API does not model yet (an airline's fare basis, say) survives the round trip instead of
being quietly dropped. `coupons` is required but may be **empty**, because the published duplicate
fixture posts an empty coupon list and turning that into a validation error would hide the
duplicate-detection case it exists to test.

The set of types is closed in four places that cannot drift apart: the `TransactionType` enum, the
`ck_transactions_type` check constraint, the `MODELS` map in `MetadataCodec`, and the
`TransactionMetadata` interface, which is **sealed** to exactly those four records. A static
initialiser in `MetadataCodec` fails service startup if a type has no model declared, so adding an
enum constant and forgetting its contract stops the service rather than surfacing as a failed
request months later.

`related_external_id` on a fee is deliberately the client's id and not a foreign key: a trip fee can
arrive before, or without, the booking it refers to, and a hard reference would make that unstorable.

## One row per `external_id`

The guarantee is `CREATE UNIQUE INDEX ux_transactions_external_id`. Everything else is an
optimisation of the error path.

`TransactionService.create` looks the `external_id` up first, so the ordinary "client retried" case
costs one indexed read and raises no exception. That lookup is not what makes the rule true —
two requests can both pass it. When they do, one `INSERT` wins and the other comes back as a
`DuplicateKeyException`; the loser reads the winner's row and answers **409** with `existing_id`, so
a retrying client always lands on the one stored transaction.

`create` is therefore deliberately **not** `@Transactional`. The loser of the race has to read the
winner's row, which it could not do from inside a transaction that the constraint violation had
already marked rollback-only. The transaction boundary instead sits on
`TransactionRepository.insert`, which writes the header and both charge-line lists as one unit:
either the whole transaction is stored or none of it is.

`ConcurrentCreateTest` releases eight requests with the same `external_id` through a barrier and
asserts exactly one 201, seven 409s pointing at the same id, and one row. With the unique index
downgraded to a plain index the same test stores eight rows — the constraint is what holds the line,
and the test proves it.

No Redis, no Kafka, no lock service: the database already serialises this.

## The invoice snapshot

`POST /invoices` copies type, currency, `occurred_at`, total, every tax line, every fee line and the
whole `metadata` document into `invoice_lines` and its two child tables. Reading an invoice touches
**only** those tables — there is no join back to `transactions`, so there is no path by which a
later edit could change what was billed. `transaction_id` is kept for traceability and to answer
"is this billed?", never as a source of values.

Totals are computed once at build time by `InvoiceTotals`, a pure function over the lines, and
stored on the header, so listing invoices needs no joins. The two rollups are a `GROUP BY` over the
frozen snapshot rows: taxes by `(name, rate)` — the same name at a different rate is a different tax
— and fees by `name`. Deriving them from immutable rows is still frozen, and it keeps the database
doing the aggregation.

`total` is the sum of the billed transactions' gross totals; `tax_total` and `fee_total` are the
components **already contained** in that sum, reported rather than added on top.

Building an invoice locks each transaction row with `SELECT … FOR UPDATE`, one at a time **in id
order**. The lock makes a concurrent `PATCH` or `DELETE` of the same transaction wait, so a
transaction cannot be edited into or out of a snapshot halfway through; the fixed order means two
invoices over overlapping sets queue up instead of deadlocking. The lines themselves are written in
the order the client asked for, which is what the invoice reads like.

## Update and delete

A `PATCH` or `DELETE` of a transaction locks the row, then refuses with **409** if
`invoice_lines` holds it, naming the blocking `invoice_ids`. Because the check runs inside the
transaction that holds the lock, nothing is written on the refusal — the invoice's money cannot be
rewritten underneath it. The foreign key from `invoice_lines` to `transactions` is deliberately
**restricting**, so even a write that bypassed the service could not delete a billed transaction.

`PATCH /invoices/{id}` locks the header, drops the old snapshot and writes the new one in one
transaction: a reader sees the old invoice or the new one, never a half-rebuilt list. If the new set
is invalid — unknown id, mixed currency — the whole rebuild rolls back and the invoice is untouched,
which `InvoiceApiTest.failedPatchLeavesInvoiceUnchanged` asserts.

`DELETE /invoices/{id}` removes the header and, by cascade, the snapshot. The transactions stay, and
become editable again because nothing bills them any more.

A write that cannot take the row lock within `LOCK_TIMEOUT` is answered **409** with a
`LOCK_TIMEOUT` code, not a 500: the service is healthy and nothing was written, only that one row is
busy, and the identical request will usually succeed on a retry. `LockContentionTest` holds the lock
from outside the service and asserts exactly that.

Concurrent writes to one row are serialised by its lock, so last-write-wins is the outcome, and it
is still atomic: a torn `tax_lines` list is impossible because the lists are rewritten inside the
same transaction as the header.

## Money

`BigDecimal` end to end, `DECIMAL(19,2)` in the schema, never a `double`. Values are normalised to
scale 2 on the way into the database and on the way out to the wire — including aggregates, since
`SUM()` returns whatever scale the database chooses. Input carrying more than two decimal places is
**rejected** with a 400 rather than rounded, so the service never quietly changes a client's number.
Tax rates are `DECIMAL(9,6)` and rendered without the padding zeros the column adds.

Ids are `txn_`/`inv_` plus a UUID version 7: opaque, so no row counts leak, and time-ordered, so
inserts stay at the right-hand edge of the primary key index instead of scattering like UUIDv4.

## High volume

What breaks first, and what is already in place.

**Duplicate `POST` storms.** The first thing to go without an index would be the `external_id`
lookup, and without the unique index, correctness itself. Both are covered by
`ux_transactions_external_id`, which serves the lookup and the guarantee. The contended path raises
one exception per losing request, which is bounded by the duplicate rate, not by table size.

**Large lists.** Unbounded reads are the classic way this API falls over, so no endpoint can perform
one: `limit` defaults to 50, is capped at 100 and is refused above it, and every list query carries
`LIMIT`/`OFFSET` in SQL. Nothing is paginated in memory. `has_more` comes from reading one row
beyond the limit, because a `COUNT(*)` total on every page is a scan that gets slower exactly as the
table grows. `ix_transactions_created_at_id` and `ix_invoices_created_at_id` match the
`ORDER BY created_at DESC, id DESC`, so a page is an index read, not a sort. A page of transactions
loads its charge lines in **two** further queries with `WHERE transaction_id IN (…)`, not two per
row, and a page of invoices carries headers only — `line_count` on the header answers the one
question a list view asks about the snapshot.

**One large row.** Bounding a list by rows is worth nothing if one row can be arbitrarily large, so the
row itself is bounded too: at most 50 tax or fee lines per transaction
(`CreateTransactionRequest.MAX_CHARGE_LINES`) and at most 64 KB of `metadata`
(`MetadataCodec.MAX_METADATA_CHARS`). Without those, one `POST` writes an unbounded number of child
rows inside a single transaction, every later page of transactions carries them all back out, and an
invoice over that transaction copies the lot into the snapshot and rolls it up. Both bounds are
rejected with a 400 naming the limit, the same way an oversized `limit` is.

**Deep paging is the next thing that hurts.** `OFFSET 500000` still walks the rows it skips. The
honest limit of offset pagination, and the reason the index is on `(created_at, id)` and ids are
time-ordered: switching to a keyset cursor (`WHERE (created_at, id) < (?, ?)`) is a change to one
query, with no schema migration.

**Concurrent `PATCH` plus invoicing.** Serialised by row locks taken in id order. The cost is
waiting, not corruption, and the fixed lock order rules out the deadlock that would otherwise show
up under load. `ix_invoice_lines_transaction_id` makes the "is this billed?" check an index seek
rather than a scan of the snapshot — that check runs on every transaction update, so it matters.
`ux_invoice_lines_invoice_transaction` stops the same transaction being billed twice on one invoice
at the database level, not just in the service.

**One request holding many locks.** An invoice locks every transaction it bills, so the number of
transactions on one invoice is capped (`InvoiceRequest.MAX_TRANSACTIONS_PER_INVOICE`, 200). Without
it, a single request could hold an unbounded number of row locks for as long as the snapshot takes
to write, which is the cheapest way for one caller to stall every other writer.

**Throughput shape.** Request threads are virtual (`spring.threads.virtual.enabled`), so concurrency
is bounded by the connection pool rather than by a servlet thread pool — the right shape for a
service whose work is short blocking database calls.

No Prometheus, nginx or load-test harness stands in for any of the above.

## Trade-offs and what I would change next

- **H2, file-backed.** A real database on disk with MVCC row locking, no install step for whoever
  reviews this, and explicitly allowed by the brief. The SQL is ANSI and the schema has no H2-only
  types, so moving to Postgres is a datasource URL, a Flyway run and `CLOB` → `text`. Nothing in the
  design depends on the engine beyond `FOR UPDATE` and a unique index, which both have.
- **No optimistic-locking `version` column.** Pessimistic row locks already make the write paths
  atomic and the specification accepts last-write-wins. A `version` column would be the right answer
  the moment clients need to detect that someone else changed a transaction between their read and
  their write.
- **No invoice status or immutability flag.** An invoice is frozen by being a copy, not by a flag,
  so there is no state machine to get wrong. Issuing, voiding and crediting would add one.
- **`metadata` as JSON.** The cost is that it cannot be filtered on cheaply. If reporting ever needs
  "all flights through LHR", the answer is a generated column plus an index, or promoting those few
  fields to columns — not unpicking the whole document into tables.
