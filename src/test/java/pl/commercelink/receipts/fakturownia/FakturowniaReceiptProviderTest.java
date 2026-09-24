package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptRejectedException;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Endpoint;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Fault;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.SLOWER_THAN_TIMEOUT_MILLIS;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.request;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.uniqueKey;

class FakturowniaReceiptProviderTest {

    private final FakeFakturownia fake = new FakeFakturownia();
    private final List<Long> sleeps = new ArrayList<>();
    private final FakturowniaReceiptProvider provider = FakturowniaTestSupport.provider(fake, sleeps::add, FakturowniaReceiptConfig.PRINTER_ID, "12");

    private static final String TOO_MANY_REQUESTS = "{\"code\":\"error\",\"message\":\"Too many requests\"}";
    private static final String OPERATOR_NOTE = "Klient odbierze w sobotę";

    @AfterEach
    void stop() {
        fake.close();
    }

    private static <T extends Throwable> T assertExactly(Class<T> type, Throwable thrown) {
        assertEquals(type, thrown.getClass(), "expected exactly " + type.getSimpleName() + " but was " + thrown);
        return type.cast(thrown);
    }

    // ---- happy path ----------------------------------------------------------------------------------

    @Test
    void issueCreatesMarksAndOrdersFiscalisationOnce() {
        // given
        String key = uniqueKey();

        // when
        Receipt receipt = provider.issue(request(key));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals(key, receipt.receiptKey());
        assertEquals(1, fake.fiscalPrintCalls(receipt.providerReceiptId()));
        assertEquals(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER, fake.invoice(receipt.providerReceiptId()).get("internal_note").asText());
        assertTrue(fake.requestLog().getLast().endsWith("&mode=e-receipt&fiskator_name=12"));
    }

    @Test
    void issueSendsTheMappedDocument() {
        // given
        String key = uniqueKey();

        // when
        Receipt receipt = provider.issue(request(key));

        // then
        var stored = fake.invoice(receipt.providerReceiptId());
        assertEquals("receipt", stored.get("kind").asText());
        assertEquals(key, stored.get("oid").asText());
        assertEquals(FakeFakturownia.DEPARTMENT_ID, stored.get("department_id").asText());
        assertEquals("jan@example.com", stored.get("buyer_email").asText());
        assertEquals(2, stored.get("positions").size());
    }

    @Test
    void secondIssueWithSameKeyDoesNotOrderFiscalisationAgain() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt first = provider.issue(request);

        // when
        Receipt second = provider.issue(request);

        // then
        assertEquals(first.providerReceiptId(), second.providerReceiptId());
        assertEquals(ReceiptState.PENDING, second.state());
        assertEquals(1, fake.createCalls());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void issueOfAFiscalisedReceiptReturnsItWithoutAnyWrite() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt pending = provider.issue(request);
        fake.settleFiscalised(pending.providerReceiptId());

        // when
        Receipt again = provider.issue(request);

        // then
        assertEquals(ReceiptState.FISCALISED, again.state());
        assertEquals("https://test.paragony.pl/eR" + pending.providerReceiptId(), again.documentUrl());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void operatorRejectionOfAQueuedReceiptStaysPending() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt pending = provider.issue(request);
        fake.settleCancelled(pending.providerReceiptId());

        // when
        Receipt reissued = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, reissued.state(), "the printer may still register a queued sale");
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void accountAutoFiscalisationIsDetectedAndNothingIsOrdered() {
        // given
        fake.autoFiscalisation();
        String key = uniqueKey();

        // when
        Receipt receipt = provider.issue(request(key));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals(0, fake.fiscalPrintCalls(), "the account already queued it: a second order fiscalises the sale twice");
        assertNull(fake.invoice(receipt.providerReceiptId()).get("internal_note"), "nothing is written either");
    }

    @Test
    void receiptQueuedByTheOperatorIsNotOrderedAgain() {
        // given: fiscal_print certainly did not run, so the marker was removed ...
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(401, "{\"code\":\"error\",\"message\":\"Unauthorized\"}"));
        ReceiptRequest request = request(uniqueKey());
        assertThrows(ReceiptException.class, () -> provider.issue(request));
        String id = fake.requestLog().stream().filter(line -> line.startsWith("GET /invoices/fiscal_print"))
                .findFirst().orElseThrow().replaceAll(".*[?&]id=([^&]+).*", "$1");
        // ... and before the retry the operator clicked "fiscal print" in Fakturownia
        fake.settleFiscalStatus(id, "to_print");

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void paperFiscalisedReceiptIsFiscalisedWithoutLinkAndNeverOrderedAgain() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt pending = provider.issue(request);
        fake.settleFiscalStatus(pending.providerReceiptId(), "er_fatal");

        // when
        Receipt fetched = provider.fetch(pending.providerReceiptId());
        Receipt reissued = provider.issue(request);

        // then
        assertEquals(ReceiptState.FISCALISED, fetched.state());
        assertNull(fetched.documentUrl());
        assertEquals(ReceiptState.FISCALISED, reissued.state());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void printerErrorFailsTheAttemptWithThePrinterMessage() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt pending = provider.issue(request);
        fake.settleFiscalError(pending.providerReceiptId(), "Niepoprawna wartość brutto na dokumencie");

        // when
        Receipt fetched = provider.fetch(pending.providerReceiptId());
        ReceiptRejectedException rejected = assertThrows(ReceiptRejectedException.class, () -> provider.issue(request));

        // then
        assertEquals(ReceiptState.FAILED, fetched.state());
        assertEquals("fiscal_error", fetched.failure().code());
        assertEquals("fiscal_error", rejected.code());
        assertTrue(rejected.getMessage().contains("Niepoprawna wartość brutto"), rejected.getMessage());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void operatorRejectionOfAPaperFiscalisedReceiptIsNeverFailed() {
        // given
        ReceiptRequest request = request(uniqueKey());
        Receipt pending = provider.issue(request);
        fake.settleFiscalStatus(pending.providerReceiptId(), "printed");
        fake.settleCancelled(pending.providerReceiptId());

        // when
        Receipt reissued = provider.issue(request);

        // then
        assertEquals(ReceiptState.FISCALISED, reissued.state(), "FAILED would make the app issue the sale again");
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void duplicateReceiptsUnderOneKeyAreOutcomeUnknownAndNothingIsOrdered() {
        // given: the receipt exists and was ordered; an earlier, lost create surfaces later under the same oid
        String key = uniqueKey();
        provider.issue(request(key));
        ObjectNode older = new ObjectMapper().createObjectNode().put("id", 1).put("kind", "receipt").put("oid", key)
                .put("department_id", FakeFakturownia.DEPARTMENT_ID);
        older.putNull("e_receipt_view_url");
        older.putNull("fiscal_status");
        fake.putInvoice(older);

        // when
        ReceiptOutcomeUnknownException unknown = assertThrows(ReceiptOutcomeUnknownException.class, () -> provider.issue(request(key)));

        // then
        assertEquals(1, fake.fiscalPrintCalls(), "neither document may be ordered again");
        assertTrue(unknown.getMessage().contains("(ids 1000, 1)"), unknown.getMessage());
        assertThrows(ReceiptOutcomeUnknownException.class, () -> provider.find(key));
    }

    // ---- lost responses: fiscalisation at most once ---------------------------------------------------

    @Test
    void lostCreateResponseRecoversAndOrdersFiscalisationOnce() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.CREATE, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.createCalls());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void lostFiscalPrintResponseNeverOrdersFiscalisationTwice() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));
        assertEquals(1, fake.fiscalPrintCalls(), "the HTTP client must not silently resend the fiscal print");

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintTimeoutNeverOrdersFiscalisationTwice() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.SlowAnswer(SLOWER_THAN_TIMEOUT_MILLIS));
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        provider.issue(request);

        // then
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void lostMarkerWriteAnswerStillNeverOrdersTwice() {
        // given: the marker PUT is persisted but its answer is lost
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.UPDATE, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(0, fiscalPrintRequests(), "the marker was applied, so the adapter must not order fiscalisation");
        assertEquals(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER, fake.invoice(retried.providerReceiptId()).get("internal_note").asText());
    }

    @Test
    void fiscalPrintThatCertainlyDidNotRunClearsTheMarkerSoRetryOrdersIt() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(429, "{\"code\":\"error\",\"message\":\"Too many requests\"}"));
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void lostFiscalPrintResponseIsNotOrderedAgainWhenTheListOmitsTheMarker() {
        // given: the list endpoint does not return internal_note, so only the full document shows the marker
        fake.listOmitsInternalNote();
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void markerThatTheServerDoesNotPersistIsReceiptExceptionAndNothingIsOrdered() {
        // given: the PUT answers 200 but internal_note is not stored
        fake.ignoreInternalNoteUpdates();
        ReceiptRequest request = request(uniqueKey());

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));
        assertThrows(ReceiptException.class, () -> provider.issue(request));

        // then
        assertExactly(ReceiptException.class, thrown);
        assertTrue(thrown.getMessage().contains("could not be confirmed"), thrown.getMessage());
        assertEquals(0, fiscalPrintRequests());
        assertEquals(1, fake.createCalls());
    }

    @Test
    void failedConfirmingReadClearsTheMarkerSoTheRetryOrders() {
        // given: the read before the marker passes, the confirming read is rate-limited on every try
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(429, TOO_MANY_REQUESTS));
        fake.failNext(Endpoint.GET, new Fault.Status(429, TOO_MANY_REQUESTS));
        fake.failNext(Endpoint.GET, new Fault.Status(429, TOO_MANY_REQUESTS));
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));
        String id = onlyInvoiceId();

        // then: nothing was ordered and the marker is gone
        assertExactly(ReceiptException.class, thrown);
        assertTrue(thrown.getMessage().contains("the marker was removed"), thrown.getMessage());
        assertEquals(0, fiscalPrintRequests());
        assertMarkerGone(id);
        assertEquals(List.of(1000L, 2000L), sleeps, "three tries, 1 s then 2 s apart");

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.fiscalPrintCalls(id));
        assertEquals(1, fiscalPrintRequests());
    }

    @Test
    void confirmingReadThatFailsWithoutRetryAlsoClearsTheMarker() {
        // given: a 503 is not retried; the confirming read fails at once
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(503, "busy"));
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));
        assertMarkerGone(onlyInvoiceId());
        assertEquals(List.of(), sleeps);

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fiscalPrintRequests());
    }

    @Test
    void singleRateLimitOnTheConfirmingReadIsRetriedAndOrdersOnce() {
        // given
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(429, TOO_MANY_REQUESTS));

        // when
        Receipt receipt = provider.issue(request(uniqueKey()));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals(1, fiscalPrintRequests());
        assertEquals(List.of(1000L), sleeps);
    }

    @Test
    void failedConfirmingReadWhoseMarkerCannotBeRemovedKeepsTheReceiptPendingWithoutOrdering() {
        // given: the confirming read fails and so does the marker removal (500 is not retried)
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(503, "busy"));
        fake.failNext(Endpoint.UPDATE, new Fault.Pass());
        fake.failNext(Endpoint.UPDATE, new Fault.Status(500, "boom"));
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));

        // when
        Receipt retried = provider.issue(request);

        // then: the marker is most likely set, so the receipt waits for the operator instead of being ordered
        assertExactly(ReceiptException.class, thrown);
        assertTrue(thrown.getMessage().contains("the marker stays"), thrown.getMessage());
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(0, fiscalPrintRequests());
    }

    @Test
    void interruptedBackOffStopsRetryingAndNeverOrders() {
        // given: the confirming read is rate-limited and the pause before the next try is interrupted
        FakturowniaReceiptProvider interrupted = FakturowniaTestSupport.provider(fake, millis -> {
            throw new InterruptedException("shutdown");
        }, FakturowniaReceiptConfig.PRINTER_ID, "12");
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(429, TOO_MANY_REQUESTS));

        // when
        ReceiptException thrown;
        try {
            thrown = assertThrows(ReceiptException.class, () -> interrupted.issue(request(uniqueKey())));
        } finally {
            // then: the interrupt flag is restored (and cleared here so it does not leak into other tests)
            assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
        }
        assertExactly(ReceiptException.class, thrown);
        assertEquals(2, fake.requestLog().stream().filter(line -> line.startsWith("GET /invoices/1")).count(),
                "no further read after the interrupted pause");
        assertEquals(0, fiscalPrintRequests());
    }

    @Test
    void receiptQueuedWhileMarkingIsNotOrdered() {
        // given: someone queues the receipt between the read before the marker and the confirming read
        fake.queueOnMarker();
        ReceiptRequest request = request(uniqueKey());

        // when
        Receipt receipt = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals(0, fake.fiscalPrintCalls());
        assertEquals(0, fiscalPrintRequests());
    }

    @Test
    void failedReadBeforeTheMarkerIsReceiptExceptionAndTheRetryResumes() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.GET, new Fault.Status(503, "busy"));
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));
        assertEquals(0, fiscalPrintRequests());

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.createCalls());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintRedirectIsOutcomeUnknownAndTheRetryDoesNotOrderAgain() {
        assertAmbiguousFiscalPrintAnswerKeepsTheMarker(302, "<html><body>You are being redirected.</body></html>");
    }

    @Test
    void fiscalPrintNotAcceptableIsOutcomeUnknownAndTheRetryDoesNotOrderAgain() {
        assertAmbiguousFiscalPrintAnswerKeepsTheMarker(406, "Not Acceptable");
    }

    private void assertAmbiguousFiscalPrintAnswerKeepsTheMarker(int status, String body) {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(status, body));

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));
        Receipt retried = provider.issue(request);

        // then
        assertExactly(ReceiptOutcomeUnknownException.class, thrown);
        assertEquals(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER, fake.invoice(retried.providerReceiptId()).get("internal_note").asText());
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fiscalPrintRequests(), "an ambiguous answer must never lead to a second fiscal order");
    }

    @Test
    void fiscalPrintIsSentWithAcceptAnything() {
        // when
        provider.issue(request(uniqueKey()));

        // then
        assertEquals("*/*", fake.lastFiscalPrintAccept());
    }

    private int fiscalPrintRequests() {
        return (int) fake.requestLog().stream().filter(line -> line.startsWith("GET /invoices/fiscal_print")).count();
    }

    @Test
    void transient429OnClearMarkerIsRetried() {
        // given: fiscal_print certainly did not run (401); the marker removal is rate-limited twice, then succeeds
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(401, "{\"code\":\"error\",\"message\":\"Unauthorized\"}"));
        fake.failNext(Endpoint.UPDATE, new Fault.Pass());
        fake.failNext(Endpoint.UPDATE, new Fault.Status(429, TOO_MANY_REQUESTS));
        fake.failNext(Endpoint.UPDATE, new Fault.Status(429, TOO_MANY_REQUESTS));
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));
        String id = onlyInvoiceId();

        // then
        assertExactly(ReceiptException.class, thrown);
        assertTrue(thrown.getMessage().contains("the marker was removed"), thrown.getMessage());
        assertMarkerGone(id);
        assertEquals(List.of(1000L, 2000L), sleeps);

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(1, fake.fiscalPrintCalls(id));
        assertEquals(2, fiscalPrintRequests(), "the refused request and the one order");
    }

    @Test
    void operatorNoteSurvivesTheMarker() {
        // given: two receipts that already exist with the operator's private note
        String ordered = uniqueKey();
        String refused = uniqueKey();
        storeReceipt(5001, ordered, OPERATOR_NOTE);
        storeReceipt(5002, refused, OPERATOR_NOTE);

        // when: the first is ordered, the second's fiscal_print is rate-limited
        Receipt receipt = provider.issue(request(ordered));
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(429, TOO_MANY_REQUESTS));
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(refused))));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        String note = fake.invoice("5001").get("internal_note").asText();
        assertTrue(note.contains(OPERATOR_NOTE), note);
        assertTrue(note.contains(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER), note);
        assertEquals(OPERATOR_NOTE, fake.invoice("5002").get("internal_note").asText());
        assertEquals(0, fake.createCalls());
    }

    @Test
    void fullLookupPageIsInconclusiveAndCreatesNothing() {
        // given: the oid filter matches 100 other receipts (substring matches), none with this exact key
        String key = uniqueKey();
        for (int i = 0; i < 100; i++) {
            storeReceipt(6000 + i, key + "-" + i, null);
        }

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(key)));

        // then
        assertExactly(ReceiptOutcomeUnknownException.class, thrown);
        assertEquals(0, fake.createCalls());
        assertEquals(0, fiscalPrintRequests());
        assertThrows(ReceiptOutcomeUnknownException.class, () -> provider.find(key));
    }

    private void storeReceipt(long id, String key, String note) {
        ObjectNode stored = new ObjectMapper().createObjectNode().put("id", id).put("kind", "receipt").put("oid", key)
                .put("department_id", FakeFakturownia.DEPARTMENT_ID).put("status", "paid");
        stored.putNull("e_receipt_view_url");
        stored.putNull("fiscal_status");
        if (note != null) {
            stored.put("internal_note", note);
        }
        fake.putInvoice(stored);
    }

    private String onlyInvoiceId() {
        assertEquals(1, fake.invoiceCount());
        return fake.requestLog().stream().filter(line -> line.startsWith("GET /invoices/1")).findFirst()
                .map(line -> line.substring("GET /invoices/".length(), line.indexOf(".json"))).orElseThrow();
    }

    private void assertMarkerGone(String id) {
        assertFalse(FakturowniaReceiptMapper.hasFiscalPrintMarker(fake.invoice(id)), "marker still set: " + fake.invoice(id));
    }

    // ---- error classification -----------------------------------------------------------------------

    @Test
    void createValidationErrorIsRejectedWithFakturowniaMessage() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(422, "{\"code\":\"error\",\"message\":{\"buyer_email\":[\"jest nieprawidłowy\"]}}"));

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey())));

        // then
        ReceiptRejectedException rejected = assertExactly(ReceiptRejectedException.class, thrown);
        assertEquals("error", rejected.code());
        assertEquals("buyer_email: jest nieprawidłowy", rejected.getMessage());
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void createBadRequestIsRejected() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(400, "{\"code\":\"error\",\"message\":\"Bad request\"}"));

        // when / then
        assertExactly(ReceiptRejectedException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void receiptThatAppearsDuringCreateIsOutcomeUnknownAndTheRetryOrdersFiscalisationOnce() {
        // given: the receipt already exists but the first lookup missed it (a concurrent call or a lagging search)
        ReceiptRequest request = request(uniqueKey());
        FakturowniaReceiptsApi api = new FakturowniaReceiptsApi(fake.url(), FakeFakturownia.API_KEY, FakturowniaTestSupport.SHORT_TIMEOUT);
        String existingId = api.createReceipt(new FakturowniaReceiptMapper(FakeFakturownia.DEPARTMENT_ID, 40).toInvoice(request))
                .get("id").asText();
        fake.failNext(Endpoint.LIST, new Fault.Status(200, "[]"));

        // when
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));
        Receipt retried = provider.issue(request);

        // then
        assertEquals(existingId, retried.providerReceiptId());
        assertEquals(1, fake.createCalls());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void findSkipsADocumentThatStatesAnotherDepartment() {
        // given
        String key = uniqueKey();
        Receipt issued = provider.issue(request(key));
        fake.invoice(issued.providerReceiptId()).put("department_id", "8");
        fake.failNext(Endpoint.LIST, new Fault.Status(200, "[" + fake.invoice(issued.providerReceiptId()) + "]"));

        // when
        Optional<Receipt> found = provider.find(key);

        // then
        assertTrue(found.isEmpty());
    }

    @Test
    void findAcceptsADocumentWithoutDepartment() {
        // given
        String key = uniqueKey();
        Receipt issued = provider.issue(request(key));
        fake.invoice(issued.providerReceiptId()).remove("department_id");
        fake.failNext(Endpoint.LIST, new Fault.Status(200, "[" + fake.invoice(issued.providerReceiptId()) + "]"));

        // when
        Optional<Receipt> found = provider.find(key);

        // then
        assertEquals(issued.providerReceiptId(), found.orElseThrow().providerReceiptId());
    }

    @Test
    void createConflictWithoutAMatchingReceiptIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(409, "{\"code\":\"error\",\"message\":\"Conflict\"}"));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createServerErrorIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(503, "Service Unavailable"));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createTimeoutIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.SlowAnswer(SLOWER_THAN_TIMEOUT_MILLIS));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createAuthenticationFailureIsPlainReceiptException() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(401, "{\"code\":\"error\",\"message\":\"Unauthorized\"}"));

        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createRateLimitIsPlainReceiptException() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(429, "{\"code\":\"error\",\"message\":\"Too many requests\"}"));

        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void lookupFailureBeforeCreateIsPlainReceiptException() {
        // given
        fake.failNext(Endpoint.LIST, new Fault.Status(500, "boom"));

        // when
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));

        // then
        assertEquals(0, fake.createCalls());
    }

    @Test
    void unreachableFakturowniaIsPlainReceiptException() throws Exception {
        // given
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(FakturowniaTestSupport.config(fake,
                FakturowniaReceiptConfig.API_URL, "http://127.0.0.1:" + closedPort));
        FakturowniaReceiptProvider unreachable = new FakturowniaReceiptProvider(
                new FakturowniaReceiptsApi(config.apiUrl(), config.apiKey(), FakturowniaTestSupport.SHORT_TIMEOUT), config, FakturowniaTestSupport.CLOCK);

        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> unreachable.issue(request(uniqueKey()))));
    }

    @Test
    void markerServerErrorIsOutcomeUnknownAndNothingIsOrdered() {
        // given
        fake.failNext(Endpoint.UPDATE, new Fault.Status(500, "boom"));

        // when
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));

        // then
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintRefusalIsRejected() {
        // given
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422,
                "{\"code\":\"error\",\"message\":\"Drukarka nie jest przypisana do wszystkich działów\"}"));

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey())));

        // then
        ReceiptRejectedException rejected = assertExactly(ReceiptRejectedException.class, thrown);
        assertTrue(rejected.getMessage().contains("Drukarka nie jest przypisana"));
    }

    @Test
    void fiscalPrintBadRequestIsRejected() {
        // given
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(400, "{\"code\":\"error\",\"message\":\"Nieprawidłowe parametry\"}"));

        // when / then
        assertExactly(ReceiptRejectedException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void fiscalPrintRefusalOfAReceiptQueuedMeanwhileIsPending() {
        // given: the receipt was queued after our last read, and Fakturownia refuses the second order
        fake.settleBeforeNextFiscalPrint("to_print");
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Paragon jest już w kolejce\"}"));
        ReceiptRequest request = request(uniqueKey());

        // when
        Receipt receipt = provider.issue(request);

        // then: a refused re-order of a queued receipt is not a failure, so the consumer keeps the key
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals(0, fake.fiscalPrintCalls());
        assertEquals(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER, fake.invoice(receipt.providerReceiptId()).get("internal_note").asText());
    }

    @Test
    void fiscalPrintRefusalOfAReceiptFiscalisedMeanwhileIsFiscalised() {
        // given
        fake.settleBeforeNextFiscalPrint("er_fatal");
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Paragon jest już zafiskalizowany\"}"));

        // when
        Receipt receipt = provider.issue(request(uniqueKey()));

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintRefusalOfAReceiptThePrinterRefusedMeanwhileIsRejectedAsPrinterError() {
        // given
        fake.settleBeforeNextFiscalPrint("error");
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Paragon ma już status\"}"));

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey())));

        // then: the printer's failure decides, exactly as for a FAILED receipt found before the order
        ReceiptRejectedException rejected = assertExactly(ReceiptRejectedException.class, thrown);
        assertEquals("fiscal_error", rejected.code());
        assertTrue(rejected.getMessage().contains("will not be fiscalised"), rejected.getMessage());
    }

    @Test
    void fiscalPrintRefusalOfAReceiptWithALinkIsFiscalisedNotRejected() {
        // given: the e-receipt link was published after our last read, but fiscal_status was not set
        fake.settleLinkBeforeNextFiscalPrint("https://test.paragony.pl/eRlink");
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Paragon jest już zafiskalizowany\"}"));

        // when
        Receipt receipt = provider.issue(request(uniqueKey()));

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintRefusalFollowedByAFailedReReadIsOutcomeUnknownAndKeepsTheMarker() {
        // given: the read before the marker and the confirming read pass, the re-read after the refusal fails
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Odmowa\"}"));
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(500, "{\"code\":\"error\",\"message\":\"boom\"}"));
        String key = uniqueKey();

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(key)));

        // then
        assertExactly(ReceiptOutcomeUnknownException.class, thrown);
        String id = provider.find(key).orElseThrow().providerReceiptId();
        assertEquals(FakturowniaReceiptMapper.FISCAL_PRINT_MARKER, fake.invoice(id).get("internal_note").asText());
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void oidConflictIsOutcomeUnknownEvenWhenTheLookupMissesTheReceipt() {
        // given: fiscalisation was ordered but its answer lost; afterwards the oid search stops finding the receipt
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));
        fake.failNext(Endpoint.LIST, new Fault.Status(200, "[]"));
        fake.failNext(Endpoint.LIST, new Fault.Status(200, "[]"));

        // when: the retry's create hits oid_unique (422 {"message":{"oid":[...]}})
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request));

        // then
        assertExactly(ReceiptOutcomeUnknownException.class, thrown);
        assertEquals(1, fake.createCalls());
        assertEquals(1, fake.fiscalPrintCalls());
    }

    @Test
    void createErrorNamingOidInPlainTextIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(422, "{\"code\":\"error\",\"message\":\"Oid jest już zajęte\"}"));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createErrorKeyedOidUniqueIsOutcomeUnknownEvenWhenTheLookupMisses() {
        // given: Fakturownia keys the duplicate-oid error "oid_unique" (the real format is undocumented) and the lookup misses it
        fake.failNext(Endpoint.CREATE, new Fault.Status(422, "{\"code\":\"error\",\"message\":{\"oid_unique\":[\"jest już zajęte\"]}}"));

        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey())));

        // then
        assertExactly(ReceiptOutcomeUnknownException.class, thrown);
        assertEquals(0, fake.fiscalPrintCalls());
    }

    @Test
    void fiscalPrintServerErrorIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.FISCAL_PRINT, new Fault.Status(502, "Bad gateway"));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    @Test
    void createdDocumentWithoutIdIsOutcomeUnknown() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.Status(201, "{\"kind\":\"receipt\"}"));

        // when / then
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request(uniqueKey()))));
    }

    // ---- find / fetch -------------------------------------------------------------------------------

    @Test
    void findMatchesTheExactKeyOnly() {
        // given
        String key = uniqueKey();
        provider.issue(request(key + "0"));

        // when
        Optional<Receipt> found = provider.find(key);

        // then
        assertTrue(found.isEmpty(), "the backend's substring match must not leak into find");
    }

    @Test
    void findIgnoresOtherDepartments() {
        // given
        String key = uniqueKey();
        FakturowniaReceiptProvider otherDepartment = FakturowniaTestSupport.provider(fake, FakturowniaReceiptConfig.DEPARTMENT_ID, "8");
        otherDepartment.issue(request(key));

        // when
        Optional<Receipt> found = provider.find(key);

        // then
        assertTrue(found.isEmpty());
    }

    @Test
    void findTransportFailureIsReceiptExceptionNotEmpty() {
        // given
        fake.failNext(Endpoint.LIST, new Fault.SlowAnswer(SLOWER_THAN_TIMEOUT_MILLIS));

        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.find(uniqueKey())));
    }

    @Test
    void fetchCarriesTheKeyAndFiscalData() {
        // given
        String key = uniqueKey();
        Receipt pending = provider.issue(request(key));
        fake.settleFiscalised(pending.providerReceiptId());

        // when
        Receipt fetched = provider.fetch(pending.providerReceiptId());

        // then
        assertEquals(key, fetched.receiptKey());
        assertEquals(ReceiptState.FISCALISED, fetched.state());
        assertNull(fetched.fiscal().receiptNumber());
        assertNull(fetched.fiscal().cashRegisterUniqueNumber());
        assertEquals(java.time.Instant.parse("2026-09-22T10:05:01Z"), fetched.fiscal().fiscalisedAt());
    }

    @Test
    void fetchOfUnknownIdIsReceiptException() {
        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.fetch("999999")));
    }

    @Test
    void fetchOfAnInvoiceThatIsNotAReceiptIsReceiptException() {
        // given
        fake.putInvoice(new ObjectMapper().createObjectNode().put("id", 4242).put("kind", "vat"));

        // when / then
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.fetch("4242")));
    }

    @Test
    void fetchOfBlankIdFailsWithoutRemoteCall() {
        // when
        ReceiptException thrown = assertThrows(ReceiptException.class, () -> provider.fetch(" "));

        // then
        assertInstanceOf(ReceiptException.class, thrown);
        assertEquals(0, fake.requests());
    }

    @Test
    void capabilitiesFollowConfiguration() {
        // given
        FakturowniaReceiptProvider shortNames = FakturowniaTestSupport.provider(fake, FakturowniaReceiptConfig.LINE_NAME_LENGTH, "38");

        // when / then
        assertEquals(38, shortNames.maxLineNameLength());
        assertTrue(shortNames.requiresBuyerEmail());
    }
}
