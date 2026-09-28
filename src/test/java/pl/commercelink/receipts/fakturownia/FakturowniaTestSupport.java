package pl.commercelink.receipts.fakturownia;

import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.VatRate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared fixtures: configuration pointing at a {@link FakeFakturownia}, providers with a short timeout, requests. */
final class FakturowniaTestSupport {

    static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);
    static final long SLOWER_THAN_TIMEOUT_MILLIS = 1500;
    static final String WEBHOOK_TOKEN = "hook-secret";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T10:30:00Z"), ZoneOffset.UTC);

    private FakturowniaTestSupport() {
    }

    static Map<String, String> config(FakeFakturownia fake, String... extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                FakturowniaReceiptConfig.API_URL, fake.url(),
                FakturowniaReceiptConfig.API_KEY, FakeFakturownia.API_KEY,
                FakturowniaReceiptConfig.DEPARTMENT_ID, FakeFakturownia.DEPARTMENT_ID,
                FakturowniaReceiptConfig.WEBHOOK_TOKEN, WEBHOOK_TOKEN));
        for (int i = 0; i < extra.length; i += 2) {
            config.put(extra[i], extra[i + 1]);
        }
        return config;
    }

    /** A provider whose retry pauses return at once, so the tests never wait for the back-off. */
    static FakturowniaReceiptProvider provider(FakeFakturownia fake, String... extra) {
        return provider(fake, millis -> { }, extra);
    }

    static FakturowniaReceiptProvider provider(FakeFakturownia fake, FakturowniaReceiptProvider.Sleeper sleeper, String... extra) {
        FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(config(fake, extra));
        return new FakturowniaReceiptProvider(new FakturowniaReceiptsApi(config.apiUrl(), config.apiKey(), SHORT_TIMEOUT), config, CLOCK, sleeper);
    }

    static String uniqueKey() {
        return UUID.randomUUID() + ":R1";
    }

    static ReceiptRequest request(String key) {
        return ReceiptRequest.builder()
                .receiptKey(key)
                .orderId("order-" + key.replace(':', '-'))
                .saleDate(LocalDateTime.of(2026, 9, 22, 12, 0))
                .line(ReceiptLine.goods("Kabel HDMI 2m", new BigDecimal("2"), Money.ofGrosze(2499), VatRate.VAT_23).sku("HDMI-2"))
                .line(ReceiptLine.shipping("Dostawa kurier", Money.ofGrosze(1599), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(6597)).label("Przelewy24"))
                .buyer(ReceiptBuyer.builder().email("jan@example.com").build())
                .build();
    }
}
