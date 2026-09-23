package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRejectedException;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.ReceiptValidationException;
import pl.commercelink.receipts.api.VatRate;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Endpoint;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Fault;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.SLOWER_THAN_TIMEOUT_MILLIS;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.request;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.uniqueKey;

class FakturowniaReceiptProviderTest {

    private final FakeFakturownia fake = new FakeFakturownia();
    private final FakturowniaReceiptProvider provider = FakturowniaTestSupport.provider(fake, FakturowniaReceiptConfig.PRINTER_ID, "12");

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
        assertTrue(unknown.getMessage().contains("1") && unknown.getMessage().contains("1000"), unknown.getMessage());
        assertThrows(ReceiptOutcomeUnknownException.class, () -> provider.find(key));
    }

    @Test
    void zeroValueLineIsRefusedBeforeAnyRemoteCall() {
        // given
        ReceiptRequest request = ReceiptRequest.builder()
                .receiptKey(uniqueKey())
                .orderId("order-zero")
                .saleDate(LocalDateTime.of(2026, 9, 22, 12, 0))
                .line(ReceiptLine.goods("Kabel", BigDecimal.ONE, Money.ofGrosze(1000), VatRate.VAT_23))
                .line(ReceiptLine.shipping("Dostawa gratis", Money.ZERO, VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(1000)))
                .buyer(ReceiptBuyer.builder().email("jan@example.com").build())
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> provider.issue(request));
        assertEquals(0, fake.requests());
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
    void lostMarkerResponseLeavesReceiptPendingWithoutOrdering() {
        // given
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.UPDATE, new Fault.DropAfterApplying());
        assertExactly(ReceiptOutcomeUnknownException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(0, fake.fiscalPrintCalls(), "the marker was applied, so the adapter must not order fiscalisation");
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
    void failedMarkerConfirmationIsReceiptExceptionAndTheRetryDoesNotOrder() {
        // given: the read before the marker passes, the confirming read after it fails
        ReceiptRequest request = request(uniqueKey());
        fake.failNext(Endpoint.GET, new Fault.Pass());
        fake.failNext(Endpoint.GET, new Fault.Status(503, "busy"));
        assertExactly(ReceiptException.class, assertThrows(ReceiptException.class, () -> provider.issue(request)));

        // when
        Receipt retried = provider.issue(request);

        // then: the marker is set, so the receipt waits for the operator instead of being ordered
        assertEquals(ReceiptState.PENDING, retried.state());
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
        assertTrue(shortNames.pushesStatusUpdates());
    }
}
