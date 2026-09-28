package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.testing.ReceiptWebhookContractTest;
import pl.commercelink.receipts.api.testing.SignedWebhook;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/** The webhook kit from receipts-api: the executor re-reads each reported document from {@link FakeFakturownia}. */
class FakturowniaReceiptWebhookContractTest extends ReceiptWebhookContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeFakturownia fake = new FakeFakturownia();

    @AfterEach
    void stop() {
        fake.close();
    }

    @Override
    protected ReceiptProviderDescriptor descriptor() {
        return new FakturowniaReceiptProviderDescriptor();
    }

    @Override
    protected Map<String, String> providerConfig() {
        return FakturowniaTestSupport.config(fake);
    }

    @Override
    protected SignedWebhook validWebhook(Receipt receipt) {
        ObjectNode document = JSON.createObjectNode()
                .put("id", receipt.providerReceiptId())
                .put("kind", "receipt")
                .put("oid", receipt.receiptKey())
                .put("department_id", FakeFakturownia.DEPARTMENT_ID)
                .put("status", receipt.state() == ReceiptState.FAILED ? "rejected" : "paid");
        if (receipt.state() == ReceiptState.FISCALISED) {
            document.put("fiscal_status", "er_printed");
            document.put("e_receipt_view_url", receipt.documentUrl());
            document.put("updated_at", receipt.fiscal().fiscalisedAt().atOffset(ZoneOffset.ofHours(2)).toString());
        }
        fake.putInvoice(document);
        return webhookAbout(receipt.providerReceiptId(), "receipt", FakturowniaTestSupport.WEBHOOK_TOKEN);
    }

    @Override
    protected SignedWebhook unauthenticated(SignedWebhook valid) {
        return new SignedWebhook(valid.payload().replace(FakturowniaTestSupport.WEBHOOK_TOKEN, "wrong-token"), valid.headers());
    }

    @Override
    protected Optional<SignedWebhook> irrelevantWebhook() {
        return Optional.of(webhookAbout("555", "vat", FakturowniaTestSupport.WEBHOOK_TOKEN));
    }

    /** The documented {@code invoice:update} body: a "deal" summary plus the echoed {@code api_token}. */
    static SignedWebhook webhookAbout(String id, String kind, String token) {
        ObjectNode body = JSON.createObjectNode().put("id", id).put("app_name", "fakturownia").put("api_token", token).put("locale", "pl");
        ObjectNode deal = body.putObject("deal").put("kind", kind).put("status", "issued").put("currency", "PLN");
        deal.putObject("external_ids").put("fakturownia", id);
        return new SignedWebhook(body.toString(), Map.of("Content-Type", "application/json"));
    }
}
