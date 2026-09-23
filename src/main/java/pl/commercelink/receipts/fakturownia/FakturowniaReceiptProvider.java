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

    private final FakturowniaReceiptsApi api;
    private final FakturowniaReceiptConfig config;
    private final FakturowniaReceiptMapper mapper;
    private final Clock clock;

    FakturowniaReceiptProvider(FakturowniaReceiptsApi api, FakturowniaReceiptConfig config, Clock clock) {
        this.api = api;
        this.config = config;
        this.mapper = new FakturowniaReceiptMapper(config.departmentId(), config.lineNameLength());
        this.clock = clock;
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
            throw new ReceiptRejectedException(current.failure().code(), "Receipt " + id + " for key " + key
                    + " will not be fiscalised (" + current.failure().message() + "); issue with a new receipt key");
        }
        if (FakturowniaReceiptMapper.hasFiscalPrintMarker(document) || FakturowniaReceiptMapper.hasFiscalStatus(document)) {
            // Already ordered — by us, by the operator in Fakturownia or by the account's automatic fiscalisation.
            // Fakturownia does not refuse a second order for a queued receipt, so ordering again fiscalises twice.
            return current;
        }
        try {
            api.updateInternalNote(id, FakturowniaReceiptMapper.FISCAL_PRINT_MARKER);
        } catch (FakturowniaApiException e) {
            throw afterSending("Marking receipt " + id + " before fiscalisation", e);
        }
        // Order fiscalisation only once the marker is confirmed by a fresh read, so a PUT that Fakturownia
        // accepted but did not persist can never lead to a second order on a retry. If this read fails, the
        // marker is most likely set, so every retry returns PENDING without ordering: the receipt then waits
        // for the operator, exactly like a lost fiscal_print answer. That is the accepted price of at most once.
        JsonNode marked = read(id, "Confirming the fiscal-print marker of receipt " + id
                + " (fiscalisation was not ordered)");
        if (!FakturowniaReceiptMapper.hasFiscalPrintMarker(marked)) {
            throw new ReceiptException("The fiscal-print marker of receipt " + id
                    + " could not be confirmed; fiscalisation was not ordered");
        }
        if (FakturowniaReceiptMapper.hasFiscalStatus(marked)) {
            // queued by someone else between the two reads
            return Receipt.pending(key, id);
        }
        orderFiscalPrint(id);
        return Receipt.pending(key, id);
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

    @Override
    public boolean pushesStatusUpdates() {
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
            throw new ReceiptException("Looking up receipt " + key + " failed: " + e.getMessage(), e);
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(document -> document.path("id").asText()).collect(Collectors.joining(", "));
            throw new ReceiptOutcomeUnknownException("Fakturownia holds " + matches.size() + " receipts under key " + key
                    + " (ids " + ids + "); fiscalisation is not ordered — keep the fiscalised one and delete the others"
                    + " in Fakturownia before retrying");
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
     * Orders fiscalisation. When the order certainly did not run (not sent, 401/403/404/429), the marker is
     * removed on a best-effort basis so a retry with the same key can order again. Only a 400/422 is a
     * rejection (the consumer then issues a new receipt key). Every other answer — a redirect, any other 4xx,
     * 5xx or no answer — may have been sent after the job was queued, so the marker stays and the outcome is
     * unknown: the receipt stays PENDING for the operator rather than risking a second fiscalisation.
     */
    private void orderFiscalPrint(String id) {
        try {
            api.orderFiscalPrint(id, config.printerId());
        } catch (FakturowniaApiException e) {
            boolean http = e.kind() == FakturowniaApiException.Kind.HTTP;
            boolean certainlyNotRun = e.kind() == FakturowniaApiException.Kind.NOT_SENT
                    || (http && nothingHappened(e.status()));
            if (certainlyNotRun) {
                clearMarker(id);
                throw new ReceiptException("Ordering fiscalisation of receipt " + id + " did not run: " + e.getMessage(), e);
            }
            if (http && (e.status() == 400 || e.status() == 422)) {
                throw new ReceiptRejectedException(e.providerCode(), "Fakturownia refused to fiscalise receipt " + id
                        + ": " + e.providerMessage(), e);
            }
            throw new ReceiptOutcomeUnknownException("Ordering fiscalisation of receipt " + id + ": " + e.getMessage(), e);
        }
    }

    private void clearMarker(String id) {
        try {
            api.updateInternalNote(id, "");
        } catch (FakturowniaApiException ignored) {
            // the marker stays: the receipt remains PENDING until the operator fiscalises it by hand
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
