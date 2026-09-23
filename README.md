# receipts-fakturownia

[`receipts-api`](https://github.com/commerce-link/receipts-api) provider that issues Polish fiscal
**e-receipts** (e-paragony) through **Paragony.pl**, the e-receipt module of Fakturownia. Paragony.pl has no API
of its own: receipts are Fakturownia documents (`kind: "receipt"`) created through the regular Fakturownia API
(`https://{prefix}.fakturownia.pl`), and fiscalisation is ordered with `fiscal_print`.

- Descriptor: `FakturowniaReceiptProviderDescriptor`, `name()` = `fakturownia`, `displayName()` = "Paragony.pl (Fakturownia)".
- Issues `ELECTRONIC` receipts only. The adapter never e-mails the buyer: the consumer delivers the e-receipt link.
- `requiresBuyerEmail()` = `true`, `pushesStatusUpdates()` = `true`, `maxLineNameLength()` = configured (default 40).

## How a receipt is fiscalised

A receipt becomes fiscal only when an **online fiscal printer** registers the sale. Fakturownia passes the job to
the Paragony.pl desktop module on a computer connected to the printer. The printer fiscalises the sale and sends
it to the Paragony.pl hub, which publishes the e-receipt (`e_receipt_view_url`, `https://{prefix}.paragony.pl/…`).
If the printer or the module is off, the receipt stays pending, possibly for hours.

`issue` is a state machine resumed by receipt key (the key is stored as the document's `oid`):

1. look the receipt up (`GET /invoices.json?oid=…&kind=receipt&period=all&department_id=…&per_page=100`, exact
   match on our side);
2. create it when absent (`POST /invoices.json` with `oid_unique: "yes"`);
3. re-read the full document by id (`GET /invoices/{id}.json`) and decide on it, never on the list or create
   answer: a fiscalised `fiscal_status` or an e-receipt link → `FISCALISED`; `fiscal_status: "error"` →
   `ReceiptRejectedException` (`fiscal_error`, issue with a new key); any other `fiscal_status` (queued, printing,
   set by the operator or by the account's automatic fiscalisation) or our marker → `PENDING` without ordering;
4. write the marker `commercelink:fiscal-print-ordered` into the private note (`internal_note`), then read the
   document again and continue only if the marker is there;
5. order fiscalisation (`GET /invoices/fiscal_print?id=…&mode=e-receipt[&fiskator_name=…]`, `Accept: */*`) → `PENDING`.

**Fiscalisation is ordered at most once.** A sale registered twice in fiscal memory cannot be undone, while a missing
fiscalisation is fixed by one click in Fakturownia. So the marker is written *before* the order, and a retry that sees
the marker never orders again. The marker must be confirmed by a fresh read before the order: if Fakturownia does not
persist it, `issue` stops with `ReceiptException` and orders nothing. If an order is lost after the marker, the receipt stays `PENDING` and the consumer's
"pending too long" alert sends the operator to Fakturownia. `fiscal_print` is sent through a one-shot HTTP/1.1
connection (`OneShotHttpGet`), because both JDK HTTP clients silently resend a GET when the server closes the
connection before answering.

Callers must not issue the same key concurrently. That includes a queue redelivering the message while the first
`issue` still runs: `issue` makes up to six sequential calls with a 30 s timeout each, so the consumer's queue
visibility timeout must be at least 300 s (or be extended while issuing), and the consumer should hold a
per-key lease. Two concurrent calls can both write the marker and both order fiscalisation.

Fakturownia limits the API to 1000 requests per minute and two concurrent requests **per IP address**, so all
stores and `invoicing-fakturownia` share one budget: the consumer needs one limiter for every call to Fakturownia.

Several receipts under one key (a lost create that surfaced late, or `oid_unique` not enforced) are never ordered:
`issue` and `find` throw `ReceiptOutcomeUnknownException` naming the ids, and the operator keeps one and deletes
the others.

| Fakturownia document | `Receipt` |
|---|---|
| `fiscal_status` `printed` / `er_printed` / `er_fail` / `er_fatal`, or `e_receipt_view_url` present | `FISCALISED`; `documentUrl` = the link, **null when the printer printed on paper** (`printed`, `er_fail`, `er_fatal`) |
| `fiscal_status: "error"` | `FAILED` (`code` = `fiscal_error`, message = `fiscal_print_error`) |
| no `fiscal_status`, and `status: "rejected"` or `cancelled: true` | `FAILED` (`code` = `cancelled`) |
| anything else (`to_print*`, `printing`, unknown value, nothing yet) | `PENDING` |

`fiscal_status` values come from Fakturownia's help article on the online fiscalisation architecture: `to_print`,
`to_print_f`, `to_print_q` (queued), `printing`, `error`, `printed` (paper), `er_printed` (e-receipt), `er_fail`
(fiscalised, e-receipt upload still retried), `er_fatal` (fiscalised, the e-receipt will never be issued). Every
fiscalised value is final: the sale is in the printer's fiscal memory.

`FiscalData` carries neither the receipt number nor the cash register's unique number, because the Fakturownia API
does not expose them. `fiscalisedAt` is the document's `updated_at` when the fiscalised state is first read
(Fakturownia exposes no fiscalisation time; `print_time` is set by PDF printing and e-mailing on any document and
is not used), so it is approximate.

### Failures

| Situation | Exception |
|---|---|
| invalid request, missing buyer e-mail, mixed payment forms, a name with only refused characters, a line worth 0 PLN (fiscal printers refuse it; the consumer leaves free lines out) | `ReceiptValidationException` |
| Fakturownia unreachable, HTTP 401/403/404/429, a failed read of the document, a marker that could not be confirmed | `ReceiptException` (no fiscalisation was ordered in this call; retry with the same key) |
| HTTP 400/422 on create (unless the error names `oid`), 400/422 on `fiscal_print`, a receipt the printer refused (`fiscal_status: "error"`) or, without any fiscal status, one marked rejected/cancelled in Fakturownia | `ReceiptRejectedException` (issue with a new key) |
| timeout or dropped connection after sending; 5xx; any other answer to `fiscal_print` (3xx, other 4xx); a receipt that appeared during create; a create error naming `oid` (the receipt exists but the lookup cannot find it); several receipts under one key | `ReceiptOutcomeUnknownException` (`find`, then retry with the same key) |

`Rejected` makes the consumer issue a new receipt, which is fiscalised again, so it only follows an answer that proves
nothing ran. `fiscal_print` is an undocumented UI-style route: a redirect or a `406` may arrive after the job was
queued, so those leave the marker in place and the receipt waits as `PENDING` for the operator. A receipt that exists
but cannot be found by `oid` stays `OutcomeUnknown` until the lookup finds it or the operator acts.

When the confirming read after the marker fails, the marker is most likely set: every retry then returns `PENDING`
without ordering, and the receipt needs the operator, exactly like a lost `fiscal_print` answer.

After `ReceiptOutcomeUnknownException` the consumer retries `issue` with the same key even when `find` returns the
receipt: a receipt whose create answer was lost exists as `PENDING` but has not been sent for fiscalisation, and
only the retry orders it.

## Account requirements

- An online fiscal printer supporting e-receipts (Posnet, Novitus or STX protocol) connected to Fakturownia through
  the **Paragony.pl module ≥ 3.3.0**, running and online.
- **E-receipts enabled** on the account: *Ustawienia › Ustawienia konta › Inne*.
- **"Automatyczna fiskalizacja paragonów po utworzeniu przez API" must be OFF.** It applies to every receipt created
  through the API on the account, by any integration. The adapter never orders a receipt that already has a
  `fiscal_status`, so with the option on the receipts are fiscalised once (by the account, not by the adapter) and
  still reported correctly. The option cannot be read through the API.
- The printer has every VAT rate that receipts use configured (A=23, B=8, C=5, D=0, E=zw) and the Mazovia code page
  for Polish characters.
- The API token belongs to a user allowed to issue documents: with a read-only role Fakturownia answers `200` to a
  create without creating anything (a known Fakturownia bug), which the adapter reports as an unknown outcome.
- The printer is available to the configured department. Without `printerId`, Fakturownia picks the department's
  default printer, then the account's default, then the oldest printer shared by all departments.

## Configuration

| Field | Required | Meaning |
|---|---|---|
| `apiUrl` | yes | `https://{prefix}.fakturownia.pl`; must be `https` (plain `http` only for `localhost`/`127.0.0.1`/`::1`) and carry no path, query or fragment |
| `apiKey` | yes | API token (*Ustawienia › Ustawienia konta › Integracja › Kod autoryzacyjny API*), sent as `Authorization: Bearer`; visible ASCII only |
| `departmentId` | yes | department (company) the receipts are issued for |
| `printerId` | no | printer id from `https://{prefix}.fakturownia.pl/printers.json`, sent as `fiskator_name`; empty = default printer; Fakturownia's two guides disagree whether `fiskator_name` takes the printer's id or name; leave it empty (the department's default printer) until the first live test settles it |
| `webhookToken` | yes | the "API token" entered in the Fakturownia webhook (see below); surrounding whitespace is ignored |
| `lineNameLength` | no | longest line name, 1–40 (default 40; use 38 when the account adds the VAT letter to product names) |

## Status webhook

In Fakturownia add a webhook (*Ustawienia › Ustawienia konta › Integracja › Webhooki*):

- event `invoice:update`;
- URL: the consumer's webhook endpoint for provider `fakturownia` (binding path `fakturownia`);
- API token: the same value as `webhookToken`.

Fakturownia signs nothing: the only check is the echoed `api_token`, compared in constant time. The body is
used only as a trigger. The adapter re-reads the document through the API and maps it exactly like `fetch`, so a
forged body cannot change a receipt's state. The executor ignores (with `WebhookOutcome.empty()`):

- events about other document kinds;
- receipts that state another department (a document without `department_id` is accepted, the same rule as `find`);
- receipts without a receipt-key `oid`, i.e. issued by hand or by another integration;
- read failures.

Fakturownia retries a failed delivery up to 25 times and then **deactivates the webhook** without notice. The
consumer answers 2xx to every authentic webhook, including one it cannot match to an attempt, and keeps polling
`PENDING` receipts with `fetch`. The body carries no fiscal status, and our own marker `PUT` triggers a webhook as
well — both are harmless because the executor only reads.

## Mapping

- `VatRate`: 23/8/5/0 → `"23"`/`"8"`/`"5"`/`"0"`, `EXEMPT` → `"zw"`. `"np"` is never sent.
- Lines worth 0 PLN are refused (`ReceiptValidationException`): fiscal printers reject them.
- `PaymentForm`: `CASH` → `cash`, `CARD` → `card`, `TRANSFER` → `transfer`. `MOBILE`/`VOUCHER`/`CREDIT`/`OTHER`
  → the payment's label, or "Płatność mobilna"/"Bon"/"Kredyt"/"Inna". Printers show `cash`/`card`/`transfer` as
  Gotówka/Karta/Przelew; any other text is printed as "INNA (text)".
  - Fakturownia records one payment type per document, so payments that map to different types are refused.
- Line names: `&` → "i"; `^ % $ # @ *` removed (the printer refuses them with error [16]); whitespace collapsed; the
  name is cut to `lineNameLength`.
- Buyer: `buyer_email` (required), `buyer_tax_no` when a tax id is given, no names. With a name, Fakturownia treats
  the receipt as a named invoice. Fakturownia may create a client record from the e-mail.
- Amounts are gross with two decimals: `price_gross` and `total_price_gross`, `calculating_strategy.position = keep_gross`.

## Operator procedures

- **Printer rejected the sale** (`fiscal_status: "error"`, message in `fiscal_print_error`, e.g. a VAT rate the
  printer lacks, the VAT "step" rule, a gross value that does not add up): the adapter reports `FAILED`
  (`fiscal_error`) and the consumer may issue a new attempt. Fakturownia never retries a failed fiscalisation on its
  own. Receipts cannot be cancelled in Fakturownia (the option was withdrawn in February 2025). **Never mark as
  rejected a receipt the printer registered**: a fiscalised `fiscal_status` always wins and keeps it `FISCALISED`,
  but a receipt still queued (`to_print`, `printing`) stays `PENDING` even when rejected. Put the distinguishing part
  at the start of shipping line names: printers refuse a name previously sold at a lower VAT rate.
- **Receipt pending for long**: check `fiscal_status` in Fakturownia. `to_print*` / `printing` → the printer or the
  Paragony.pl module is off; start it, the queue has no time limit, but sales must be fiscalised within the month.
  No `fiscal_status` but our marker → the order was lost; order the fiscal print by hand once. Never order by hand a
  receipt that has any `fiscal_status`.
- **`fiscal_print` refused** (400/422, `ReceiptRejectedException`): a non-fiscal receipt with the marker stays in Fakturownia.
  Delete it after fixing the cause (e.g. assign the printer to the department).

## Known limits and open risks

Verified only against a fake backend built from the documentation (no test account). To confirm on an account with a printer:

- the fields holding the fiscal receipt number and the cash register's unique number (none are documented);
- the response of `fiscal_print`, the exact `oid_unique` error, and whether the `oid` filter matches exactly (the
  adapter filters exactly itself);
- the `calculating_strategy` value that matches "zgodnie z kasą fiskalną", and how `payment_type` is printed;
- `Authorization: Bearer` on every endpoint used (documented in the e-receipt guide; `api_token` is the fallback);
- whether `status: "rejected"` can be set on a receipt at all (cancellation cannot); the adapter no longer depends on it;
- whether `internal_note` can be written on a `kind: receipt` document (if not, `issue` never orders fiscalisation and
  says the marker could not be confirmed — visible on the first live receipt), and which fields the list endpoint
  returns (the adapter decides on `GET /invoices/{id}.json` for that reason);
- the status codes of `fiscal_print` (only 400/422 are treated as a refusal);
- the `calculating_strategy` sent (`position: keep_gross`, `sum: sum`): the help centre calls only "sumowanie
  brutto … zgodnie z kasą fiskalną" fiscal-compatible, which is likely `sum: keep_gross`, and one plugin reports
  HTTP 500 when the strategy is sent at all — to settle before production;
- whether `fiscal_status` is set synchronously by `fiscal_print` and by automatic fiscalisation (the adapter's
  guard assumes it is);
- how `fiscal_print` answers a bad token (a redirect to the login page would leave the receipt `PENDING` with the
  marker).

## Build

```
mvn verify
```

Java 21. Depends on `pl.commercelink:receipts-api` (GitHub Packages, repository id `github`) and
`com.fasterxml.jackson.core:jackson-databind` (Maven Central).
