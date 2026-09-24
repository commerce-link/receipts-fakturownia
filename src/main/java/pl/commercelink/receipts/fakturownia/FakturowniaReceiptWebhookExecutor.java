package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import pl.commercelink.provider.api.WebhookContext;
import pl.commercelink.provider.api.WebhookExecutor;
import pl.commercelink.provider.api.WebhookOutcome;
import pl.commercelink.provider.api.WebhookStatusResponse;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptKeys;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

import static java.lang.System.Logger.Level.WARNING;

/**
 * Handles Fakturownia's {@code invoice:update} webhook. Fakturownia signs nothing: the only proof of origin is
 * the {@code api_token} it echoes in the body, compared with the store's {@code webhookToken}. The body is
 * only a trigger — it carries no fiscal status — so the receipt is re-read through the API and mapped exactly
 * like {@code fetch}. Documents that are not this store's receipts, and read failures, yield
 * {@link WebhookOutcome#empty()}. Fakturownia retries a failed delivery up to 25 times and then deactivates the
 * webhook, so the consumer must answer 2xx to every authentic webhook and keeps polling PENDING receipts.
 */
final class FakturowniaReceiptWebhookExecutor implements WebhookExecutor<Receipt> {

    static final String REJECTED = "REJECTED";

    private static final Logger LOG = System.getLogger(FakturowniaReceiptWebhookExecutor.class.getName());

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
            body = payload == null ? null : FakturowniaJson.MAPPER.readTree(payload);
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
                    || !FakturowniaReceiptMapper.inDepartment(document, config.departmentId())
                    || !isReceiptKey(FakturowniaReceiptMapper.text(document, "oid"))) {
                return WebhookOutcome.empty();
            }
            return WebhookOutcome.of(FakturowniaReceiptMapper.toReceipt(document, clock), null);
        } catch (RuntimeException e) {
            // Also catches a broken store configuration (FakturowniaReceiptConfig.from): the consumer sees
            // an ordinary empty outcome and keeps polling, but the operator needs to see this in the logs.
            String departmentId = context.providerConfig() == null ? null : context.providerConfig().get(FakturowniaReceiptConfig.DEPARTMENT_ID);
            LOG.log(WARNING, "Fakturownia receipt webhook failed for store " + departmentId, e);
            return WebhookOutcome.empty();
        }
    }

    private static boolean tokenMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        // the configured value is stripped like every other setting: a pasted trailing newline must not reject all webhooks
        expected = expected.strip();
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
