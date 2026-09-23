package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.ReceiptValidationException;
import pl.commercelink.receipts.api.VatRate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaReceiptMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T10:30:00Z"), ZoneOffset.UTC);

    private final FakturowniaReceiptMapper mapper = new FakturowniaReceiptMapper("7", 40);

    private static ReceiptRequest.Builder base() {
        return ReceiptRequest.builder()
                .receiptKey("order-1:R1")
                .orderId("order-1")
                .saleDate(LocalDateTime.of(2026, 9, 22, 23, 30))
                .buyer(ReceiptBuyer.builder().email(" jan@example.com ").build());
    }

    private static ReceiptRequest single(VatRate rate, PaymentForm form) {
        return base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), rate))
                .payment(ReceiptPayment.of(form, Money.ofGrosze(9999)))
                .build();
    }

    // ---- document ------------------------------------------------------------------------------------

    @Test
    void mapsTheDocumentHeader() {
        // when
        ObjectNode invoice = mapper.toInvoice(single(VatRate.VAT_23, PaymentForm.TRANSFER));

        // then
        assertEquals("receipt", invoice.get("kind").asText());
        assertTrue(invoice.get("number").isNull());
        assertEquals("order-1:R1", invoice.get("oid").asText());
        assertEquals("yes", invoice.get("oid_unique").asText());
        assertEquals("7", invoice.get("department_id").asText());
        assertEquals("2026-09-22", invoice.get("sell_date").asText());
        assertEquals("2026-09-22", invoice.get("issue_date").asText());
        assertEquals("PLN", invoice.get("currency").asText());
        assertEquals("paid", invoice.get("status").asText());
        assertEquals("99.99", invoice.get("paid").asText());
        assertEquals("2026-09-22", invoice.get("paid_date").asText());
        assertEquals("jan@example.com", invoice.get("buyer_email").asText());
        assertFalse(invoice.has("buyer_tax_no"));
        assertFalse(invoice.has("buyer_name"));
        assertEquals("keep_gross", invoice.get("calculating_strategy").get("position").asText());
        assertEquals("sum", invoice.get("calculating_strategy").get("sum").asText());
        assertEquals("gross", invoice.get("calculating_strategy").get("invoice_form_price_kind").asText());
    }

    @Test
    void mapsPositionsWithAmountsAsTwoDecimalStrings() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Kawa ziarnista", new BigDecimal("0.250"), Money.ofGrosze(8000), VatRate.VAT_5).sku("KAWA-1"))
                .line(ReceiptLine.shipping("Dostawa", Money.ofGrosze(1500), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(3500)))
                .build();

        // when
        JsonNode positions = mapper.toInvoice(request).get("positions");

        // then
        assertEquals(2, positions.size());
        JsonNode coffee = positions.get(0);
        assertEquals("Kawa ziarnista", coffee.get("name").asText());
        assertEquals("KAWA-1", coffee.get("code").asText());
        assertEquals("0.25", coffee.get("quantity").asText());
        assertEquals("80.00", coffee.get("price_gross").asText());
        assertEquals("20.00", coffee.get("total_price_gross").asText());
        assertEquals("5", coffee.get("tax").asText());
        JsonNode shipping = positions.get(1);
        assertFalse(shipping.has("code"));
        assertEquals("1", shipping.get("quantity").asText());
        assertEquals("15.00", shipping.get("total_price_gross").asText());
    }

    @Test
    void wholeQuantityIsSentWithoutExponent() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Kabel", new BigDecimal("10"), Money.ofGrosze(100), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CASH, Money.ofGrosze(1000)))
                .build();

        // when
        JsonNode position = mapper.toInvoice(request).get("positions").get(0);

        // then
        assertEquals("10", position.get("quantity").asText());
    }

    @Test
    void sendsTaxIdOnlyWhenGiven() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(9999)))
                .buyer(ReceiptBuyer.builder().email("firma@example.com").taxId(" 5261040828 ").build())
                .build();

        // when
        ObjectNode invoice = mapper.toInvoice(request);

        // then
        assertEquals("5261040828", invoice.get("buyer_tax_no").asText());
    }

    // ---- VAT and payments ------------------------------------------------------------------------------

    @Test
    void mapsEveryVatRate() {
        assertEquals("23", tax(VatRate.VAT_23));
        assertEquals("8", tax(VatRate.VAT_8));
        assertEquals("5", tax(VatRate.VAT_5));
        assertEquals("0", tax(VatRate.VAT_0));
        assertEquals("zw", tax(VatRate.EXEMPT));
    }

    private String tax(VatRate rate) {
        return mapper.toInvoice(single(rate, PaymentForm.CASH)).get("positions").get(0).get("tax").asText();
    }

    @Test
    void mapsPaymentFormsWithFixedValues() {
        assertEquals("cash", paymentType(PaymentForm.CASH, null));
        assertEquals("card", paymentType(PaymentForm.CARD, null));
        assertEquals("transfer", paymentType(PaymentForm.TRANSFER, "Przelewy24"));
    }

    @Test
    void mapsOtherPaymentFormsToLabelOrPolishName() {
        assertEquals("BLIK", paymentType(PaymentForm.MOBILE, "BLIK"));
        assertEquals("Płatność mobilna", paymentType(PaymentForm.MOBILE, null));
        assertEquals("Bon", paymentType(PaymentForm.VOUCHER, " "));
        assertEquals("Kredyt", paymentType(PaymentForm.CREDIT, null));
        assertEquals("Inna", paymentType(PaymentForm.OTHER, null));
    }

    private String paymentType(PaymentForm form, String label) {
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(form, Money.ofGrosze(9999)).label(label))
                .build();
        return mapper.toInvoice(request).get("payment_type").asText();
    }

    @Test
    void splitPaymentOfTheSameFormIsOnePaymentType() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(5000)))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(4999)))
                .build();

        // when
        ObjectNode invoice = mapper.toInvoice(request);

        // then
        assertEquals("transfer", invoice.get("payment_type").asText());
        assertEquals("99.99", invoice.get("paid").asText());
    }

    @Test
    void mixedPaymentFormsAreRefused() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.VOUCHER, Money.ofGrosze(2000)))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(7999)))
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));
    }

    @Test
    void otherFormsWithDifferentLabelsAreRefused() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.OTHER, Money.ofGrosze(2000)).label("Karta podarunkowa"))
                .payment(ReceiptPayment.of(PaymentForm.OTHER, Money.ofGrosze(7999)).label("Raty"))
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));
    }

    // ---- buyer and names -------------------------------------------------------------------------------

    @Test
    void missingBuyerEmailIsRefused() {
        // given
        ReceiptRequest request = base()
                .buyer(ReceiptBuyer.NONE)
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(9999)))
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));
    }

    @Test
    void blankBuyerEmailIsRefused() {
        // given
        ReceiptRequest request = base()
                .buyer(ReceiptBuyer.builder().email("  ").build())
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(9999)))
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));
    }

    @Test
    void lineNameDropsCharactersTheFiscalModuleRefuses() {
        assertEquals("Black i Decker 50 rabat", FakturowniaReceiptMapper.lineName("Black & Decker  50% #rabat*", 40));
        assertEquals("Kabel USB-C (2m) łącze", FakturowniaReceiptMapper.lineName("Kabel USB-C (2m)\t@łącze", 40));
    }

    @Test
    void lineNameIsCutToTheConfiguredLengthWithoutSplittingACharacter() {
        // given
        String name = "Zestaw 😀 słuchawek bezprzewodowych z etui";

        // when
        String cut = FakturowniaReceiptMapper.lineName(name, 8);

        // then
        assertEquals("Zestaw 😀", cut);
        assertEquals(8, cut.codePointCount(0, cut.length()));
    }

    @Test
    void lineNameLengthComesFromConfiguration() {
        // given
        FakturowniaReceiptMapper shortNames = new FakturowniaReceiptMapper("7", 5);

        // when
        String name = shortNames.toInvoice(single(VatRate.VAT_23, PaymentForm.CASH)).get("positions").get(0).get("name").asText();

        // then
        assertEquals("Mysz", name);
        assertEquals("Monit", new FakturowniaReceiptMapper("7", 5).toInvoice(base()
                .line(ReceiptLine.goods("Monitor 27", BigDecimal.ONE, Money.ofGrosze(100), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CASH, Money.ofGrosze(100)))
                .build()).get("positions").get(0).get("name").asText());
    }

    @Test
    void nameMadeOnlyOfRefusedCharactersIsRefused() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("#*%", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(9999)))
                .build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));
    }

    @Test
    void zeroValueLineIsRefused() {
        // given
        ReceiptRequest request = base()
                .line(ReceiptLine.goods("Mysz", BigDecimal.ONE, Money.ofGrosze(9999), VatRate.VAT_23))
                .line(ReceiptLine.shipping("Dostawa gratis", Money.ZERO, VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(9999)))
                .build();

        // when
        ReceiptValidationException refused = assertThrows(ReceiptValidationException.class, () -> mapper.toInvoice(request));

        // then
        assertTrue(refused.getMessage().contains("line 1"), refused.getMessage());
    }

    // ---- document -> Receipt ---------------------------------------------------------------------------

    private static ObjectNode document() {
        return JSON.createObjectNode().put("id", 1001).put("kind", "receipt").put("oid", "order-1:R1").put("status", "paid")
                .put("updated_at", "2026-09-22T12:00:30.000+02:00");
    }

    @Test
    void documentWithoutEReceiptLinkIsPending() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().putNull("e_receipt_view_url"), CLOCK);

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
        assertEquals("1001", receipt.providerReceiptId());
        assertEquals("order-1:R1", receipt.receiptKey());
    }

    @Test
    void documentWithEReceiptLinkIsFiscalisedAtUpdatedAt() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document()
                .put("e_receipt_view_url", "https://shop.paragony.pl/eRabc")
                .put("fiscal_status", "er_printed")
                .put("print_time", "2026-09-01T08:00:00.000+02:00"), CLOCK);

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
        assertEquals("https://shop.paragony.pl/eRabc", receipt.documentUrl());
        assertNull(receipt.fiscal().receiptNumber());
        assertNull(receipt.fiscal().cashRegisterUniqueNumber());
        assertEquals(Instant.parse("2026-09-22T10:00:30Z"), receipt.fiscal().fiscalisedAt(), "print_time is not the fiscalisation time");
    }

    @Test
    void fiscalisationTimeFallsBackToNowWithoutUpdatedAt() {
        // given
        ObjectNode withoutTimes = document().put("fiscal_status", "printed");
        withoutTimes.remove("updated_at");

        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(withoutTimes, CLOCK);

        // then
        assertEquals(CLOCK.instant(), receipt.fiscal().fiscalisedAt());
    }

    @Test
    void everyFiscalisedStatusIsFiscalisedEvenWithoutLink() {
        for (String status : new String[]{"printed", "er_printed", "er_fail", "er_fatal"}) {
            // when
            Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("fiscal_status", status), CLOCK);

            // then
            assertEquals(ReceiptState.FISCALISED, receipt.state(), status);
            assertNull(receipt.documentUrl(), status);
        }
    }

    @Test
    void queuedOrPrintingOrUnknownStatusIsPending() {
        for (String status : new String[]{"to_print", "to_print_f", "to_print_q", "printing", "something_new"}) {
            // when
            Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("fiscal_status", status), CLOCK);

            // then
            assertEquals(ReceiptState.PENDING, receipt.state(), status);
        }
    }

    @Test
    void fiscalErrorIsFailedWithThePrinterMessage() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("fiscal_status", "error")
                .put("fiscal_print_error", "\nNiepoprawna wartość brutto na dokumencie"), CLOCK);

        // then
        assertEquals(ReceiptState.FAILED, receipt.state());
        assertEquals("fiscal_error", receipt.failure().code());
        assertEquals("Niepoprawna wartość brutto na dokumencie", receipt.failure().message());
    }

    @Test
    void fiscalErrorWithoutMessageStillCarriesOne() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("fiscal_status", "error"), CLOCK);

        // then
        assertEquals(ReceiptState.FAILED, receipt.state());
        assertEquals("fiscal_error", receipt.failure().code());
        assertFalse(receipt.failure().message().isBlank());
    }

    @Test
    void rejectedOrCancelledDocumentWithoutFiscalStatusIsFailed() {
        // when
        Receipt rejected = FakturowniaReceiptMapper.toReceipt(document().put("status", "rejected"), CLOCK);
        Receipt cancelled = FakturowniaReceiptMapper.toReceipt(document().put("cancelled", true), CLOCK);

        // then
        assertEquals(ReceiptState.FAILED, rejected.state());
        assertEquals("cancelled", rejected.failure().code());
        assertEquals(ReceiptState.FAILED, cancelled.state());
    }

    @Test
    void rejectedDocumentWithFiscalisedStatusIsNeverFailed() {
        for (String status : new String[]{"printed", "er_printed", "er_fail", "er_fatal"}) {
            // when
            Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("status", "rejected").put("cancelled", true)
                    .put("fiscal_status", status), CLOCK);

            // then
            assertEquals(ReceiptState.FISCALISED, receipt.state(), "a sale registered on paper must never be re-issued: " + status);
        }
    }

    @Test
    void rejectedDocumentStillQueuedIsPendingNotFailed() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("status", "rejected").put("fiscal_status", "to_print"), CLOCK);

        // then
        assertEquals(ReceiptState.PENDING, receipt.state(), "the printer may still register a queued sale");
    }

    @Test
    void fiscalStatusIsDetected() {
        assertTrue(FakturowniaReceiptMapper.hasFiscalStatus(document().put("fiscal_status", "to_print")));
        assertFalse(FakturowniaReceiptMapper.hasFiscalStatus(document().putNull("fiscal_status")));
        assertFalse(FakturowniaReceiptMapper.hasFiscalStatus(document().put("fiscal_status", "")));
        assertFalse(FakturowniaReceiptMapper.hasFiscalStatus(document()));
    }

    @Test
    void eReceiptLinkWinsOverCancellation() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("status", "rejected")
                .put("e_receipt_view_url", "https://shop.paragony.pl/eRabc"), CLOCK);

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state(), "a fiscalised sale cannot be undone");
    }

    @Test
    void documentWithoutOidHasNullKey() {
        // when
        Receipt receipt = FakturowniaReceiptMapper.toReceipt(document().put("oid", ""), CLOCK);

        // then
        assertNull(receipt.receiptKey());
    }

    @Test
    void documentWithoutIdIsRefused() {
        // when / then
        // given
        ObjectNode withoutId = document();
        withoutId.remove("id");

        // when / then
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptMapper.toReceipt(withoutId, CLOCK));
    }

    @Test
    void markerIsDetectedInsideTheNote() {
        assertTrue(FakturowniaReceiptMapper.hasFiscalPrintMarker(document().put("internal_note", "operator: x\ncommercelink:fiscal-print-ordered")));
        assertFalse(FakturowniaReceiptMapper.hasFiscalPrintMarker(document().put("internal_note", "")));
        assertFalse(FakturowniaReceiptMapper.hasFiscalPrintMarker(document()));
    }
}
