package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pl.commercelink.provider.api.WebhookContext;
import pl.commercelink.provider.api.WebhookExecutor;
import pl.commercelink.provider.api.WebhookOutcome;
import pl.commercelink.provider.api.WebhookStatusResponse;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptKeys;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

/**
 * Handles Fakturownia's {@code invoice:update} webhook. Fakturownia signs nothing: the only proof of origin is
 * the {@code api_token} it echoes in the body, compared with the store's {@code webhookToken}. The body is
 * only a trigger — its shape for receipts is undocumented — so the receipt is re-read through the API and
 * mapped exactly like {@code fetch}. Documents that are not this store's receipts, and read failures, yield
 * {@link WebhookOutcome#empty()}: the consumer polls PENDING receipts anyway, and Fakturownia never retries.
 */
final class FakturowniaReceiptWebhookExecutor implements WebhookExecutor<Receipt> {

    static final String REJECTED = "REJECTED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock;
    private final Duration timeout;

    FakturowniaReceiptWebhookExecutor(Clock clock) {
        this(clock, FakturowniaReceiptsApi.DEFAULT_TIMEOUT);
    }

    FakturowniaReceiptWebhookExecutor(Clock clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
    }

    @Override
    public WebhookOutcome<Receipt> execute(String payload, WebhookContext context) {
        JsonNode body;
        try {
            body = payload == null ? null : JSON.readTree(payload);
        } catch (Exception e) {
            body = null;
        }
        if (body == null || !body.isObject()) {
            return rejected();
        }
        String expected = context.providerConfig() == null ? null : context.providerConfig().get(FakturowniaReceiptConfig.WEBHOOK_TOKEN);
        if (!tokenMatches(expected, FakturowniaReceiptMapper.text(body, "api_token"))) {
            return rejected();
        }

        JsonNode deal = body.path("deal");
        String id = FakturowniaReceiptMapper.text(body, "id");
        if (id == null) {
            id = FakturowniaReceiptMapper.text(deal.path("external_ids"), "fakturownia");
        }
        if (id == null) {
            return WebhookOutcome.empty();
        }
        String kind = FakturowniaReceiptMapper.text(deal, "kind");
        if (kind == null) {
            kind = FakturowniaReceiptMapper.text(body, "kind");
        }
        if (kind != null && !"receipt".equals(kind)) {
            return WebhookOutcome.empty();
        }

        try {
            FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(context.providerConfig());
            JsonNode document = new FakturowniaReceiptsApi(config.apiUrl(), config.apiKey(), timeout).getReceipt(id);
            if (!FakturowniaReceiptMapper.isReceipt(document)
                    || !config.departmentId().equals(FakturowniaReceiptMapper.text(document, "department_id"))
                    || !isReceiptKey(FakturowniaReceiptMapper.text(document, "oid"))) {
                return WebhookOutcome.empty();
            }
            return WebhookOutcome.of(FakturowniaReceiptMapper.toReceipt(document, clock), null);
        } catch (RuntimeException e) {
            return WebhookOutcome.empty();
        }
    }

    private static boolean tokenMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isReceiptKey(String oid) {
        try {
            ReceiptKeys.requireValid(oid);
            return true;
        } catch (ReceiptValidationException e) {
            return false;
        }
    }

    private static WebhookOutcome<Receipt> rejected() {
        return WebhookOutcome.of(null, new WebhookStatusResponse(REJECTED));
    }
}
