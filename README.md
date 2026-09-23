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
3. re-read the full document by id (`GET /invoices/{id}.json`) and decide on it, never on the list or create answer:
   an e-receipt link → `FISCALISED`; cancelled/rejected → `ReceiptRejectedException` (`cancelled`, issue with a new
   key); our fiscal-print marker → `PENDING` without ordering again;
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

Callers must not issue the same key concurrently. The consumer serialises issuing per store, which also keeps
within Fakturownia's limit of two concurrent requests per account.

| Fakturownia document | `Receipt` |
|---|---|
| `e_receipt_view_url` present | `FISCALISED`; `documentUrl` = that link |
| no link, and `status: "rejected"` or `cancelled: true` | `FAILED` (`code` = `cancelled`) |
| anything else | `PENDING` |

`FiscalData` carries neither the receipt number nor the cash register's unique number, because the Fakturownia API
does not expose them. `fiscalisedAt` is `print_time`, or `updated_at` when that is missing, so it is approximate.

### Failures

| Situation | Exception |
|---|---|
| invalid request, missing buyer e-mail, mixed payment forms, a name with only refused characters | `ReceiptValidationException` |
| Fakturownia unreachable, HTTP 401/403/404/429, a failed read of the document, a marker that could not be confirmed | `ReceiptException` (no fiscalisation was ordered in this call; retry with the same key) |
| HTTP 400/422 on create (unless the error names `oid`), 400/422 on `fiscal_print`, a receipt cancelled in Fakturownia | `ReceiptRejectedException` (issue with a new key) |
| timeout or dropped connection after sending; 5xx; any other answer to `fiscal_print` (3xx, other 4xx); a receipt that appeared during create; a create error naming `oid` (the receipt exists but the lookup cannot find it) | `ReceiptOutcomeUnknownException` (`find`, then retry with the same key) |

`Rejected` makes the consumer issue a new receipt, which is fiscalised again, so it only follows an answer that proves
nothing ran. `fiscal_print` is an undocumented UI-style route: a redirect or a `406` may arrive after the job was
queued, so those leave the marker in place and the receipt waits as `PENDING` for the operator. A receipt that exists
but cannot be found by `oid` stays `OutcomeUnknown` until the lookup finds it or the operator acts.

When the confirming read after the marker fails, the marker is most likely set: every retry then returns `PENDING`
without ordering, and the receipt needs the operator, exactly like a lost `fiscal_print` answer.

## Account requirements

- An online fiscal printer supporting e-receipts (Posnet, Novitus or STX protocol) connected to Fakturownia through
  the **Paragony.pl module ≥ 3.3.0**, running and online.
- **E-receipts enabled** on the account: *Ustawienia › Ustawienia konta › Inne*.
- **"Automatyczna fiskalizacja paragonów po utworzeniu przez API" must be OFF.** This adapter orders fiscalisation
  itself. With the account option on, every receipt is fiscalised twice. Fakturownia warns about this itself.
- The printer is available to the configured department. Without `printerId`, Fakturownia picks the department's
  default printer, then the account's default, then the oldest printer shared by all departments.

## Configuration

| Field | Required | Meaning |
|---|---|---|
| `apiUrl` | yes | `https://{prefix}.fakturownia.pl`; must be `https` (plain `http` only for `localhost`/`127.0.0.1`/`::1`) and carry no path, query or fragment |
| `apiKey` | yes | API token (*Ustawienia › Ustawienia konta › Integracja › Kod autoryzacyjny API*), sent as `Authorization: Bearer`; visible ASCII only |
| `departmentId` | yes | department (company) the receipts are issued for |
| `printerId` | no | printer id from `https://{prefix}.fakturownia.pl/printers.json`, sent as `fiskator_name`; empty = default printer |
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

Fakturownia does not retry webhooks, so the consumer keeps polling `PENDING` receipts with `fetch`.

## Mapping

- `VatRate`: 23/8/5/0 → `"23"`/`"8"`/`"5"`/`"0"`, `EXEMPT` → `"zw"`. `"np"` is never sent.
- `PaymentForm`: `CASH` → `cash`, `CARD` → `card`, `TRANSFER` → `transfer`. `MOBILE`/`VOUCHER`/`CREDIT`/`OTHER`
  → the payment's label, or "Płatność mobilna"/"Bon"/"Kredyt"/"Inna".
  - Fakturownia records one payment type per document, so payments that map to different types are refused.
- Line names: `&` → "i"; `^ % $ # @ *` removed (the printer refuses them with error [16]); whitespace collapsed; the
  name is cut to `lineNameLength`.
- Buyer: `buyer_email` (required), `buyer_tax_no` when a tax id is given, no names. With a name, Fakturownia treats
  the receipt as a named invoice. Fakturownia may create a client record from the e-mail.
- Amounts are gross with two decimals: `price_gross` and `total_price_gross`, `calculating_strategy.position = keep_gross`.

## Operator procedures

- **Printer rejected the sale** (e.g. wrong VAT rate letter, the VAT "step" rule for a product name sold earlier at a
  lower rate): mark the receipt as rejected/cancelled in Fakturownia. The next poll or webhook reports `FAILED` and
  the consumer can issue a new attempt. **Only mark a receipt rejected/cancelled when the printer did NOT register the
  sale** — check the printer's fiscal report first. `FAILED` means "certainly not fiscalised": the next attempt
  fiscalises the sale again, so cancelling a receipt the printer did register (including one fiscalised on paper
  without an e-receipt link) records the sale twice in fiscal memory. Put the distinguishing part at the start of shipping line names: printers
  refuse a name previously sold at a lower VAT rate.
- **Receipt pending for long**: check the printer and the Paragony.pl module. If the receipt carries the marker but was
  never fiscalised, order the fiscal print by hand in Fakturownia. This also covers `fiscal_print` answers the adapter
  could not interpret (`ReceiptOutcomeUnknownException`, marker kept).
- **`fiscal_print` refused** (400/422, `ReceiptRejectedException`): a non-fiscal receipt with the marker stays in Fakturownia.
  Delete it after fixing the cause (e.g. assign the printer to the department).

## Known limits and open risks

Verified only against a fake backend built from the documentation (no test account). To confirm on an account with a printer:

- the fields holding the fiscal receipt number and the cash register's unique number (none are documented);
- the states "e-paragon będzie ponawiany" / "nie zostanie wystawiony" (fiscalised on paper, no link) stay `PENDING`;
- the response of `fiscal_print`, the exact `oid_unique` error, and whether the `oid` filter matches exactly (the
  adapter filters exactly itself);
- the `calculating_strategy` value that matches "zgodnie z kasą fiskalną", and how `payment_type` is printed;
- `Authorization: Bearer` on every endpoint used (documented in the e-receipt guide; `api_token` is the fallback);
- `status: "rejected"` / `cancelled` as the operator's way to mark a dead attempt;
- whether `internal_note` can be written on a `kind: receipt` document (if not, `issue` never orders fiscalisation and
  says the marker could not be confirmed — visible on the first live receipt), and which fields the list endpoint
  returns (the adapter decides on `GET /invoices/{id}.json` for that reason);
- the status codes of `fiscal_print` (only 400/422 are treated as a refusal).

## Build

```
mvn verify
```

Java 21. Depends on `pl.commercelink:receipts-api` (GitHub Packages, repository id `github`) and
`com.fasterxml.jackson.core:jackson-databind` (Maven Central).
