package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.provider.api.WebhookContext;
import pl.commercelink.provider.api.WebhookOutcome;
import pl.commercelink.provider.api.WebhookStatusResponse;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.testing.SignedWebhook;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Endpoint;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Fault;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static pl.commercelink.receipts.fakturownia.FakturowniaTestSupport.WEBHOOK_TOKEN;

class FakturowniaReceiptWebhookExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeFakturownia fake = new FakeFakturownia();
    private final FakturowniaReceiptWebhookExecutor executor =
            new FakturowniaReceiptWebhookExecutor(FakturowniaTestSupport.CLOCK, FakturowniaTestSupport.SHORT_TIMEOUT);

    @AfterEach
    void stop() {
        fake.close();
    }

    private ObjectNode storedReceipt(String id) {
        ObjectNode document = JSON.createObjectNode().put("id", Long.parseLong(id)).put("kind", "receipt").put("oid", "order-9:R1")
                .put("department_id", FakeFakturownia.DEPARTMENT_ID).put("status", "paid")
                .put("e_receipt_view_url", "https://shop.paragony.pl/eR" + id)
                .put("fiscal_status", "er_printed").put("updated_at", "2026-09-22T12:05:00.000+02:00");
        fake.putInvoice(document);
        return document;
    }

    private WebhookOutcome<Receipt> execute(String payload) {
        return executor.execute(payload, new WebhookContext(Map.of(), FakturowniaTestSupport.config(fake)));
    }

    private static void assertRejected(WebhookOutcome<Receipt> outcome) {
        assertNull(outcome.result());
        assertEquals(new WebhookStatusResponse("REJECTED"), outcome.responseBody());
    }

    private static void assertEmpty(WebhookOutcome<Receipt> outcome) {
        assertNull(outcome.result());
        assertNull(outcome.responseBody());
    }

    @Test
    void authenticWebhookReReadsTheReceiptFromTheApi() {
        // given
        storedReceipt("2001");
        SignedWebhook webhook = FakturowniaReceiptWebhookContractTest.webhookAbout("2001", "receipt", WEBHOOK_TOKEN);

        // when
        WebhookOutcome<Receipt> outcome = execute(webhook.payload());

        // then
        assertEquals(ReceiptState.FISCALISED, outcome.result().state());
        assertEquals("order-9:R1", outcome.result().receiptKey());
        assertEquals("https://shop.paragony.pl/eR2001", outcome.result().documentUrl());
        assertEquals("GET /invoices/2001.json", fake.requestLog().getLast());
    }

    @Test
    void idIsTakenFromTheDealWhenMissingAtTheRoot() {
        // given
        storedReceipt("2002");
        ObjectNode body = (ObjectNode) readTree(FakturowniaReceiptWebhookContractTest.webhookAbout("2002", "receipt", WEBHOOK_TOKEN).payload());
        body.remove("id");

        // when
        WebhookOutcome<Receipt> outcome = execute(body.toString());

        // then
        assertEquals("2002", outcome.result().providerReceiptId());
    }

    @Test
    void wrongTokenIsRejected() {
        assertRejected(execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2001", "receipt", "nope").payload()));
    }

    @Test
    void missingTokenIsRejected() {
        assertRejected(execute("{\"id\":2001,\"deal\":{\"kind\":\"receipt\"}}"));
    }

    @Test
    void unconfiguredTokenRejectsEverything() {
        // given
        Map<String, String> config = new java.util.HashMap<>(FakturowniaTestSupport.config(fake));
        config.remove(FakturowniaReceiptConfig.WEBHOOK_TOKEN);

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(
                FakturowniaReceiptWebhookContractTest.webhookAbout("2001", "receipt", "").payload(), new WebhookContext(Map.of(), config));

        // then
        assertRejected(outcome);
    }

    @Test
    void configuredTokenIsStrippedBeforeComparing() {
        // given
        storedReceipt("2008");
        Map<String, String> config = new java.util.HashMap<>(FakturowniaTestSupport.config(fake));
        config.put(FakturowniaReceiptConfig.WEBHOOK_TOKEN, " " + WEBHOOK_TOKEN + "\n");

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(
                FakturowniaReceiptWebhookContractTest.webhookAbout("2008", "receipt", WEBHOOK_TOKEN).payload(), new WebhookContext(Map.of(), config));

        // then
        assertEquals("2008", outcome.result().providerReceiptId());
    }

    @Test
    void blankConfiguredTokenRejectsEverything() {
        // given
        Map<String, String> config = new java.util.HashMap<>(FakturowniaTestSupport.config(fake));
        config.put(FakturowniaReceiptConfig.WEBHOOK_TOKEN, " \n");

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(
                FakturowniaReceiptWebhookContractTest.webhookAbout("2001", "receipt", "").payload(), new WebhookContext(Map.of(), config));

        // then
        assertRejected(outcome);
    }

    @Test
    void unreadableBodyIsRejected() {
        assertRejected(execute("not json"));
        assertRejected(execute("[]"));
        assertRejected(execute(null));
    }

    @Test
    void otherDocumentKindsAreIgnoredWithoutReading() {
        // when
        WebhookOutcome<Receipt> outcome = execute(FakturowniaReceiptWebhookContractTest.webhookAbout("3001", "vat", WEBHOOK_TOKEN).payload());

        // then
        assertEmpty(outcome);
        assertEquals(0, fake.requests());
    }

    @Test
    void webhookWithoutAnyIdIsIgnored() {
        assertEmpty(execute("{\"api_token\":\"" + WEBHOOK_TOKEN + "\",\"deal\":{\"kind\":\"receipt\"}}"));
    }

    @Test
    void receiptOfAnotherDepartmentIsIgnored() {
        // given
        storedReceipt("2003").put("department_id", "8");

        // when / then
        assertEmpty(execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2003", "receipt", WEBHOOK_TOKEN).payload()));
    }

    @Test
    void receiptWithoutDepartmentIsAcceptedLikeInFind() {
        // given
        storedReceipt("2009").remove("department_id");

        // when
        WebhookOutcome<Receipt> outcome = execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2009", "receipt", WEBHOOK_TOKEN).payload());

        // then
        assertEquals("2009", outcome.result().providerReceiptId());
    }

    @Test
    void receiptNotIssuedByUsIsIgnored() {
        // given
        storedReceipt("2004").put("oid", "zamówienie ręczne 17");
        storedReceipt("2005").remove("oid");

        // when / then
        assertEmpty(execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2004", "receipt", WEBHOOK_TOKEN).payload()));
        assertEmpty(execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2005", "receipt", WEBHOOK_TOKEN).payload()));
    }

    @Test
    void readFailureIsIgnoredBecauseTheConsumerPolls() {
        // given
        storedReceipt("2006");
        fake.failNext(Endpoint.GET, new Fault.Status(503, "busy"));

        // when / then
        assertEmpty(execute(FakturowniaReceiptWebhookContractTest.webhookAbout("2006", "receipt", WEBHOOK_TOKEN).payload()));
    }

    @Test
    void readOfAnInvoiceThatTurnsOutNotToBeAReceiptIsIgnored() {
        // given
        storedReceipt("2007").put("kind", "vat");
        ObjectNode body = (ObjectNode) readTree(FakturowniaReceiptWebhookContractTest.webhookAbout("2007", "receipt", WEBHOOK_TOKEN).payload());
        ((ObjectNode) body.get("deal")).remove("kind");

        // when / then
        assertEmpty(execute(body.toString()));
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
