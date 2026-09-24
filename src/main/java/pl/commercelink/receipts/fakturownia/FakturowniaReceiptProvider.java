package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptKeys;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptRejectedException;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Issues e-receipts through Fakturownia (paragony.pl). {@link #issue} is a state machine resumed by receipt key:
 * look the receipt up by {@code oid}, create it when absent, re-read the full document by id, then — unless it
 * already has a fiscal status or carries the fiscal-print marker — write the marker, confirm it with another read
 * and order fiscalisation. The marker is written and confirmed before the order, so a retry after any failure
 * never orders fiscalisation twice (at most once; a lost order leaves the receipt PENDING for the operator).
 * Several receipts under one key are never ordered. Stateless and thread-safe; callers must not issue the same key
 * concurrently — including a queue redelivering a message while the first {@code issue} still runs.
 */
public final class FakturowniaReceiptProvider implements ReceiptProvider {

    /** Tries of a call that is safe to repeat, and the pauses between them (see {@link #withRetries}). */
    private static final int TRIES = 3;
    private static final long[] PAUSES_MILLIS = {1000, 2000};

    private final FakturowniaReceiptsApi api;
    private final FakturowniaReceiptConfig config;
    private final FakturowniaReceiptMapper mapper;
    private final Clock clock;
    private final Sleeper sleeper;

    /** Pauses between tries of a retried call; injectable so tests do not wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    FakturowniaReceiptProvider(FakturowniaReceiptsApi api, FakturowniaReceiptConfig config, Clock clock) {
        this(api, config, clock, Thread::sleep);
    }

    FakturowniaReceiptProvider(FakturowniaReceiptsApi api, FakturowniaReceiptConfig config, Clock clock, Sleeper sleeper) {
        this.api = api;
        this.config = config;
        this.mapper = new FakturowniaReceiptMapper(config.departmentId(), config.lineNameLength());
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override
    public Receipt issue(ReceiptRequest request) {
        if (request == null) {
            throw new ReceiptValidationException("request is required");
        }
        ObjectNode invoice = mapper.toInvoice(request);
        String key = request.receiptKey();

        String id = lookup(key)
                .map(found -> toReceipt(found, true).providerReceiptId())
                .orElseGet(() -> toReceipt(create(key, invoice), true).providerReceiptId());

        // Decide on the full document (GET /invoices/{id}.json), never on the list or create answer: only the
        // full document is documented to carry e_receipt_view_url, and the list may omit internal_note. A failed
        // read is a plain ReceiptException: nothing was written in this step, and a receipt created in this call
        // is found by the lookup on the retry.
        JsonNode document = read(id, "Reading receipt " + id + " before fiscalisation");
        Receipt current = toReceipt(document, true);
        if (current.state() == ReceiptState.FISCALISED) {
            return current;
        }
        if (current.state() == ReceiptState.FAILED) {
            throw willNotBeFiscalised(key, id, current);
        }
        if (FakturowniaReceiptMapper.hasFiscalPrintMarker(document) || FakturowniaReceiptMapper.hasFiscalStatus(document)) {
            // Already ordered — by us, by the operator in Fakturownia or by the account's automatic fiscalisation.
            // Fakturownia does not refuse a second order for a queued receipt, so ordering again fiscalises twice.
            return current;
        }
        // The marker is appended to the operator's private note, which is restored whenever the marker is removed.
        // A marker PUT whose answer is lost stays an unknown outcome: the marker is assumed set.
        String originalNote = Optional.ofNullable(FakturowniaReceiptMapper.text(document, "internal_note")).orElse("");
        try {
            api.updateInternalNote(id, FakturowniaReceiptMapper.withMarker(originalNote));
        } catch (FakturowniaApiException e) {
            throw afterSending("Marking receipt " + id + " before fiscalisation", e);
        }
        // Order fiscalisation only once the marker is confirmed by a fresh read, so a PUT that Fakturownia
        // accepted but did not persist can never lead to a second order on a retry. The read is retried on a
        // rate limit; if it still fails, nothing was ordered, so the marker is removed and the retry orders. Only
        // when the removal fails too does the marker stay, and the receipt then waits PENDING for the operator.
        JsonNode marked;
        try {
            marked = withRetries(() -> api.getReceipt(id));
        } catch (FakturowniaApiException e) {
            if (clearMarker(id, originalNote)) {
                throw new ReceiptException("Confirming the fiscal-print marker of receipt " + id + " failed;"
                        + " fiscalisation was not ordered and the marker was removed, retry with the same key", e);
            }
            throw new ReceiptException("Confirming the fiscal-print marker of receipt " + id + " (fiscalisation was not"
                    + " ordered) failed: " + e.getMessage() + "; removing the marker failed too, so the marker stays and"
                    + " the receipt waits PENDING for the operator", e);
        }
        if (!FakturowniaReceiptMapper.hasFiscalPrintMarker(marked)) {
            throw new ReceiptException("The fiscal-print marker of receipt " + id
                    + " could not be confirmed; fiscalisation was not ordered");
        }
        if (FakturowniaReceiptMapper.hasFiscalStatus(marked)) {
            // queued by someone else between the two reads
            return Receipt.pending(key, id);
        }
        return orderFiscalPrint(key, id, originalNote);
    }

    private static ReceiptRejectedException willNotBeFiscalised(String key, String id, Receipt failed) {
        return new ReceiptRejectedException(failed.failure().code(), "Receipt " + id + " for key " + key
                + " will not be fiscalised (" + failed.failure().message() + "); issue with a new receipt key");
    }

    /**
     * Read-only lookup by exact {@code oid}. Throws {@link ReceiptOutcomeUnknownException} when several receipts
     * share the key (see {@link #lookup}); a PENDING result does not tell whether fiscalisation was ordered, so
     * after an unknown outcome the consumer retries {@link #issue} with the same key.
     */
    @Override
    public Optional<Receipt> find(String receiptKey) {
        ReceiptKeys.requireValid(receiptKey);
        return lookup(receiptKey).map(document -> toReceipt(document, false));
    }

    @Override
    public Receipt fetch(String providerReceiptId) {
        if (providerReceiptId == null || providerReceiptId.isBlank()) {
            throw new ReceiptException("providerReceiptId is required");
        }
        JsonNode document;
        try {
            document = api.getReceipt(providerReceiptId);
        } catch (FakturowniaApiException e) {
            if (e.kind() == FakturowniaApiException.Kind.HTTP && e.status() == 404) {
                throw new ReceiptException("Fakturownia has no document " + providerReceiptId, e);
            }
            throw new ReceiptException("Reading receipt " + providerReceiptId + " failed: " + e.getMessage(), e);
        }
        if (!FakturowniaReceiptMapper.isReceipt(document)) {
            throw new ReceiptException("Fakturownia document " + providerReceiptId + " is not a receipt");
        }
        return toReceipt(document, false);
    }

    @Override
    public int maxLineNameLength() {
        return config.lineNameLength();
    }

    @Override
    public boolean requiresBuyerEmail() {
        return true;
    }

    /**
     * The receipt stored under this exact {@code oid} in the configured department. More than one is an anomaly
     * (a lost create that surfaced late, or {@code oid_unique} not enforced): which one to fiscalise is the
     * operator's call, so it is {@link ReceiptOutcomeUnknownException} and nothing is ordered.
     */
    private Optional<JsonNode> lookup(String key) {
        List<JsonNode> matches;
        try {
            matches = api.findReceiptsByOid(key, config.departmentId()).stream()
                    .filter(document -> key.equals(FakturowniaReceiptMapper.text(document, "oid")))
                    .filter(FakturowniaReceiptMapper::isReceipt)
                    .filter(this::inConfiguredDepartment)
                    .toList();
        } catch (FakturowniaApiException e) {
            if (e.kind() == FakturowniaApiException.Kind.SENT_NO_ANSWER
                    && FakturowniaReceiptsApi.FULL_LOOKUP_PAGE.equals(e.getMessage())) {
                // Retrying cannot help: the same query fills the page again. Creating could duplicate the receipt.
                throw new ReceiptOutcomeUnknownException("Looking up receipt " + key + " is inconclusive: "
                        + e.getMessage() + "; nothing is created or ordered until the operator resolves it", e);
            }
            throw new ReceiptException("Looking up receipt " + key + " failed: " + e.getMessage(), e);
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(document -> document.path("id").asText()).collect(Collectors.joining(", "));
            throw new ReceiptOutcomeUnknownException("Fakturownia holds " + matches.size() + " receipts under key " + key
                    + " (ids " + ids + "); fiscalisation is not ordered — keep the document that has a fiscal status or"
                    + " the commercelink marker, delete only documents with neither, and escalate if several have one");
        }
        return matches.stream().findFirst();
    }

    /** The list call already filters by department; a document that states another department is still skipped. */
    private boolean inConfiguredDepartment(JsonNode document) {
        return FakturowniaReceiptMapper.inDepartment(document, config.departmentId());
    }

    /** {@code GET /invoices/{id}.json} during issue; any failure is a plain {@link ReceiptException} (a read changes nothing). */
    private JsonNode read(String id, String step) {
        try {
            return api.getReceipt(id);
        } catch (FakturowniaApiException e) {
            throw new ReceiptException(step + " failed: " + e.getMessage(), e);
        }
    }

    private JsonNode create(String key, ObjectNode invoice) {
        try {
            return api.createReceipt(invoice);
        } catch (FakturowniaApiException e) {
            if (e.kind() != FakturowniaApiException.Kind.HTTP || nothingHappened(e.status()) || e.status() >= 500) {
                throw afterSending("Creating receipt " + key, e);
            }
            Optional<JsonNode> existing;
            try {
                existing = lookup(key);
            } catch (ReceiptException lookupFailure) {
                throw new ReceiptOutcomeUnknownException("Creating receipt " + key + " was refused with HTTP " + e.status()
                        + " and the follow-up lookup failed", lookupFailure);
            }
            if (existing.isPresent()) {
                // Someone else created it (a concurrent call, or a lookup that lagged behind an earlier attempt).
                // Only the creator, or a retry whose first lookup sees the receipt, may order fiscalisation.
                throw new ReceiptOutcomeUnknownException("Receipt " + key + " appeared while creating it (HTTP " + e.status()
                        + "); retry with the same key to resume", e);
            }
            if (e.mentionsField("oid")) {
                // A conflict on oid (oid_unique) proves a receipt under this key exists, even though the lookup
                // cannot see it; a Rejected would make the consumer issue a second receipt for the same sale.
                throw new ReceiptOutcomeUnknownException("Receipt " + key + " exists in Fakturownia (HTTP " + e.status()
                        + ": " + e.providerMessage() + ") but cannot be found by oid; it stays unresolved until"
                        + " the lookup finds it or the operator acts", e);
            }
            if (e.status() == 400 || e.status() == 422) {
                throw new ReceiptRejectedException(e.providerCode(), e.providerMessage(), e);
            }
            throw new ReceiptOutcomeUnknownException("Creating receipt " + key + " failed with HTTP " + e.status(), e);
        }
    }

    /**
     * Orders fiscalisation and returns the receipt as PENDING; never retried. When the order certainly did not run
     * (not sent, 401/403/404/429), the marker is removed (see {@link #clearMarker}) so a retry with the same key can
     * order again.
     * A 400/422 is checked against a fresh read first (see {@link #afterFiscalPrintRefusal}). Every other answer —
     * a redirect, any other 4xx, 5xx or no answer — may have been sent after the job was queued, so the marker
     * stays and the outcome is unknown: the receipt stays PENDING for the operator rather than risking a second
     * fiscalisation.
     */
    private Receipt orderFiscalPrint(String key, String id, String originalNote) {
        try {
            api.orderFiscalPrint(id, config.printerId());
            return Receipt.pending(key, id);
        } catch (FakturowniaApiException e) {
            boolean http = e.kind() == FakturowniaApiException.Kind.HTTP;
            boolean certainlyNotRun = e.kind() == FakturowniaApiException.Kind.NOT_SENT
                    || (http && nothingHappened(e.status()));
            if (certainlyNotRun) {
                String marker = clearMarker(id, originalNote)
                        ? "the marker was removed, retry with the same key"
                        : "removing the marker failed, so the marker stays and the receipt waits PENDING for the operator";
                throw new ReceiptException("Ordering fiscalisation of receipt " + id + " did not run: " + e.getMessage()
                        + "; " + marker, e);
            }
            if (http && (e.status() == 400 || e.status() == 422)) {
                return afterFiscalPrintRefusal(key, id, e);
            }
            throw new ReceiptOutcomeUnknownException("Ordering fiscalisation of receipt " + id + ": " + e.getMessage()
                    + "; the marker stays", e);
        }
    }

    /**
     * A 400/422 from {@code fiscal_print} does not prove that nothing was fiscalised: Fakturownia also refuses to
     * order a receipt that is already queued or fiscal (with an undocumented status), and the receipt may have
     * been queued after our last read. A rejection would make the consumer issue the sale again under a new key,
     * so the document is re-read first. With a fiscal status or an e-receipt link, the refusal is ignored and the
     * document decides: FISCALISED or PENDING is returned, FAILED is rejected exactly as in {@link #issue}. With
     * neither, the refusal is final. If the re-read fails, the outcome is unknown and the marker stays.
     */
    private Receipt afterFiscalPrintRefusal(String key, String id, FakturowniaApiException refusal) {
        String refused = "Fakturownia refused to fiscalise receipt " + id + " (HTTP " + refusal.status() + ": "
                + refusal.providerMessage() + ")";
        JsonNode document;
        try {
            document = api.getReceipt(id);
        } catch (FakturowniaApiException e) {
            throw new ReceiptOutcomeUnknownException(refused + " and re-reading it failed: " + e.getMessage()
                    + "; the marker stays", e);
        }
        if (!FakturowniaReceiptMapper.hasFiscalStatus(document)
                && FakturowniaReceiptMapper.text(document, "e_receipt_view_url") == null) {
            throw new ReceiptRejectedException(refusal.providerCode(), "Fakturownia refused to fiscalise receipt " + id
                    + ": " + refusal.providerMessage(), refusal);
        }
        Receipt current = toReceipt(document, true);
        if (current.state() == ReceiptState.FAILED) {
            throw willNotBeFiscalised(key, id, current);
        }
        return current;
    }

    /**
     * Removes the marker once fiscalisation certainly was not ordered, restoring the operator's note as read before
     * the marker was written. Returns whether the removal was confirmed by Fakturownia; when it was not, the marker
     * stays (or its removal is unknown) and the receipt remains PENDING until the operator fiscalises it by hand.
     */
    private boolean clearMarker(String id, String originalNote) {
        try {
            withRetries(() -> {
                api.updateInternalNote(id, FakturowniaReceiptMapper.withoutMarker(originalNote));
                return null;
            });
            return true;
        } catch (FakturowniaApiException e) {
            return false;
        }
    }

    /**
     * Runs a call that is safe to repeat (a read, or the marker removal) up to {@link #TRIES} times, pausing
     * {@link #PAUSES_MILLIS} between tries, but only after a failure that certainly changed nothing and may pass on
     * its own: the per-IP rate limit (HTTP 429) or a request that was never sent. Fakturownia allows two concurrent
     * requests per IP, so 429s are expected under load. An interrupted pause restores the flag and stops retrying.
     * Never used for creating a receipt, writing the marker or ordering fiscalisation.
     */
    private <T> T withRetries(Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (FakturowniaApiException e) {
                if (attempt >= TRIES || !transientFailure(e) || !pause(PAUSES_MILLIS[attempt - 1])) {
                    throw e;
                }
            }
        }
    }

    private static boolean transientFailure(FakturowniaApiException e) {
        return e.kind() == FakturowniaApiException.Kind.NOT_SENT
                || (e.kind() == FakturowniaApiException.Kind.HTTP && e.status() == 429);
    }

    private boolean pause(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ReceiptException afterSending(String step, FakturowniaApiException e) {
        return switch (e.kind()) {
            case NOT_SENT -> new ReceiptException(step + " failed before sending: " + e.getMessage(), e);
            case SENT_NO_ANSWER -> new ReceiptOutcomeUnknownException(step + ": " + e.getMessage(), e);
            case HTTP -> nothingHappened(e.status())
                    ? new ReceiptException(step + " refused with HTTP " + e.status() + ": " + e.providerMessage(), e)
                    : new ReceiptOutcomeUnknownException(step + " failed with HTTP " + e.status() + ": " + e.providerMessage(), e);
        };
    }

    /** Statuses after which Fakturownia certainly did nothing: authentication, wrong URL, rate limit. */
    private static boolean nothingHappened(int status) {
        return status == 401 || status == 403 || status == 404 || status == 429;
    }

    private Receipt toReceipt(JsonNode document, boolean duringIssue) {
        try {
            return FakturowniaReceiptMapper.toReceipt(document, clock);
        } catch (RuntimeException e) {
            String message = "Unusable receipt document from Fakturownia: " + e.getMessage();
            throw duringIssue ? new ReceiptOutcomeUnknownException(message, e) : new ReceiptException(message, e);
        }
    }
}
