package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.commercelink.receipts.api.FiscalData;
import pl.commercelink.receipts.api.LineKind;
import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptFailure;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptMedium;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptValidationException;
import pl.commercelink.receipts.api.VatRate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Translates between the receipts contract and Fakturownia documents: a {@link ReceiptRequest} into the
 * {@code invoice} object of {@code POST /invoices.json}, and a receipt document into a {@link Receipt}.
 * Every refusal happens here, before any remote call.
 */
final class FakturowniaReceiptMapper {

    /** Written to {@code internal_note} right before fiscalisation is ordered; its presence means "never order again". */
    static final String FISCAL_PRINT_MARKER = "commercelink:fiscal-print-ordered";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String departmentId;
    private final int lineNameLength;

    FakturowniaReceiptMapper(String departmentId, int lineNameLength) {
        this.departmentId = departmentId;
        this.lineNameLength = lineNameLength;
    }

    // ---- request -------------------------------------------------------------------------------------

    /** Builds the {@code invoice} object; throws {@link ReceiptValidationException} for anything Fakturownia cannot take. */
    ObjectNode toInvoice(ReceiptRequest request) {
        if (request.medium() != ReceiptMedium.ELECTRONIC) {
            throw new ReceiptValidationException("Fakturownia adapter issues only ELECTRONIC receipts, got " + request.medium());
        }
        String email = request.buyer().email();
        if (email == null || email.isBlank()) {
            throw new ReceiptValidationException("Fakturownia requires the buyer's e-mail for an e-receipt");
        }
        String date = request.saleDate().toLocalDate().toString();
        Money paid = Money.ZERO;
        for (ReceiptPayment payment : request.payments()) {
            paid = paid.plus(payment.amount());
        }

        ObjectNode invoice = JSON.createObjectNode();
        invoice.put("kind", "receipt");
        invoice.putNull("number");
        invoice.put("oid", request.receiptKey());
        invoice.put("oid_unique", "yes");
        invoice.put("department_id", departmentId);
        invoice.put("sell_date", date);
        invoice.put("issue_date", date);
        invoice.put("currency", "PLN");
        invoice.put("status", "paid");
        invoice.put("paid", amount(paid));
        invoice.put("paid_date", date);
        invoice.put("payment_type", paymentType(request));
        invoice.put("buyer_email", email.strip());
        String taxId = request.buyer().taxId();
        if (taxId != null && !taxId.isBlank()) {
            invoice.put("buyer_tax_no", taxId.strip());
        }
        ObjectNode strategy = invoice.putObject("calculating_strategy");
        strategy.put("position", "keep_gross");
        strategy.put("sum", "sum");
        strategy.put("invoice_form_price_kind", "gross");
        ArrayNode positions = invoice.putArray("positions");
        for (int i = 0; i < request.lines().size(); i++) {
            positions.add(position(i, request.lines().get(i)));
        }
        return invoice;
    }

    private ObjectNode position(int index, ReceiptLine line) {
        requireSupportedKind(index, line.kind());
        String name = lineName(line.name(), lineNameLength);
        if (name.isEmpty()) {
            throw new ReceiptValidationException("line " + index + ": name is empty after removing characters the fiscal module refuses");
        }
        ObjectNode position = JSON.createObjectNode();
        position.put("name", name);
        if (line.sku() != null && !line.sku().isBlank()) {
            position.put("code", line.sku());
        }
        position.put("quantity", line.quantity().stripTrailingZeros().toPlainString());
        position.put("price_gross", amount(line.unitGross()));
        position.put("total_price_gross", amount(line.totalGross()));
        position.put("tax", tax(index, line.vatRate()));
        return position;
    }

    private static void requireSupportedKind(int index, LineKind kind) {
        switch (kind) {
            case GOODS, SHIPPING, SERVICE -> {
            }
            default -> throw new ReceiptValidationException("line " + index + ": unsupported line kind " + kind);
        }
    }

    /** Fakturownia {@code tax}: numeric rate or {@code "zw"}; {@code "np"} is not a fiscal rate and is never sent. */
    static String tax(int index, VatRate rate) {
        return switch (rate) {
            case VAT_23 -> "23";
            case VAT_8 -> "8";
            case VAT_5 -> "5";
            case VAT_0 -> "0";
            case EXEMPT -> "zw";
            default -> throw new ReceiptValidationException("line " + index + ": unsupported VAT rate " + rate);
        };
    }

    /** One payment type per document: all payments must map to the same value. */
    private static String paymentType(ReceiptRequest request) {
        Set<String> types = new LinkedHashSet<>();
        for (ReceiptPayment payment : request.payments()) {
            types.add(paymentType(payment));
        }
        if (types.size() > 1) {
            throw new ReceiptValidationException("Fakturownia records one payment type per receipt, got " + types);
        }
        return types.iterator().next();
    }

    static String paymentType(ReceiptPayment payment) {
        PaymentForm form = payment.form();
        return switch (form) {
            case CASH -> "cash";
            case CARD -> "card";
            case TRANSFER -> "transfer";
            case MOBILE -> labelOr(payment, "Płatność mobilna");
            case VOUCHER -> labelOr(payment, "Bon");
            case CREDIT -> labelOr(payment, "Kredyt");
            case OTHER -> labelOr(payment, "Inna");
            default -> throw new ReceiptValidationException("Unsupported payment form " + form);
        };
    }

    private static String labelOr(ReceiptPayment payment, String fallback) {
        return payment.label() == null || payment.label().isBlank() ? fallback : payment.label().strip();
    }

    /**
     * Applies the Paragony.pl module's rules: {@code &} becomes "i", {@code ^ % $ # @ *} are removed (printer
     * error [16]), whitespace collapses, and the name is cut to {@code maxLength} without splitting a character.
     */
    static String lineName(String name, int maxLength) {
        String cleaned = name.replace("&", " i ")
                .replaceAll("[\\^%$#@*]", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.codePointCount(0, cleaned.length()) <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, maxLength)).strip();
    }

    private static String amount(Money money) {
        return money.toBigDecimal().toPlainString();
    }

    // ---- response ------------------------------------------------------------------------------------

    /**
     * Maps a receipt document to the contract: a non-empty {@code e_receipt_view_url} means FISCALISED; no link
     * and {@code status == "rejected"} or {@code cancelled == true} means FAILED; anything else is PENDING.
     * Fakturownia exposes neither the receipt number nor the cash register's unique number, so both stay null.
     */
    static Receipt toReceipt(JsonNode document, Clock clock) {
        String id = text(document, "id");
        if (id == null) {
            throw new IllegalArgumentException("Fakturownia document without id: " + document);
        }
        String key = text(document, "oid");
        String url = text(document, "e_receipt_view_url");
        if (url != null) {
            return Receipt.fiscalised(key, id, new FiscalData(null, null, fiscalisedAt(document, clock)), url);
        }
        if ("rejected".equals(text(document, "status")) || document.path("cancelled").asBoolean(false)) {
            return Receipt.failed(key, id, new ReceiptFailure("cancelled", "Receipt cancelled in Fakturownia"));
        }
        return Receipt.pending(key, id);
    }

    static boolean hasFiscalPrintMarker(JsonNode document) {
        String note = text(document, "internal_note");
        return note != null && note.contains(FISCAL_PRINT_MARKER);
    }

    /**
     * The one department rule shared by {@code find} and the webhook: a document stating another department is
     * foreign; a document without {@code department_id} is accepted (the list call already filters by
     * department, and a read by id must not turn every webhook into a no-op should Fakturownia omit the field).
     */
    static boolean inDepartment(JsonNode document, String departmentId) {
        String department = text(document, "department_id");
        return department == null || department.equals(departmentId);
    }

    static boolean isReceipt(JsonNode document) {
        return "receipt".equals(text(document, "kind"));
    }

    /** {@code print_time}, else {@code updated_at}, else now — the moment is approximate, the state is not. */
    private static Instant fiscalisedAt(JsonNode document, Clock clock) {
        for (String field : new String[]{"print_time", "updated_at"}) {
            String value = text(document, field);
            if (value != null) {
                try {
                    return OffsetDateTime.parse(value).toInstant();
                } catch (DateTimeParseException ignored) {
                    // try the next field
                }
            }
        }
        return clock.instant();
    }

    static String text(JsonNode document, String field) {
        JsonNode value = document.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
