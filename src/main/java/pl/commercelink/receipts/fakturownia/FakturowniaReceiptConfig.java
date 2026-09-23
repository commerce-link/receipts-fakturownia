package pl.commercelink.receipts.fakturownia;

import java.net.URI;
import java.util.Map;

/**
 * One store's adapter configuration, validated when the provider is created.
 *
 * @param apiUrl         account URL, e.g. {@code https://shop.fakturownia.pl} (trailing slash removed)
 * @param apiKey         API token of the account
 * @param departmentId   department (company) the receipts are issued for
 * @param printerId      printer id from {@code /printers.json} passed as {@code fiskator_name}; null = default printer
 * @param webhookToken   token entered in the Fakturownia {@code invoice:update} webhook
 * @param lineNameLength longest line name sent to the printer (1–40)
 */
record FakturowniaReceiptConfig(String apiUrl, String apiKey, String departmentId, String printerId,
                                String webhookToken, int lineNameLength) {

    static final String API_URL = "apiUrl";
    static final String API_KEY = "apiKey";
    static final String DEPARTMENT_ID = "departmentId";
    static final String PRINTER_ID = "printerId";
    static final String WEBHOOK_TOKEN = "webhookToken";
    static final String LINE_NAME_LENGTH = "lineNameLength";

    static final int MAX_LINE_NAME_LENGTH = 40;

    /** Throws {@link IllegalArgumentException} naming the first missing or invalid field. */
    static FakturowniaReceiptConfig from(Map<String, String> configuration) {
        String apiUrl = required(configuration, API_URL);
        URI uri;
        try {
            uri = URI.create(apiUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(API_URL + " is not a valid URL: " + apiUrl, e);
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException(API_URL + " must be an http(s) URL: " + apiUrl);
        }
        String normalisedUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String printerId = configuration.get(PRINTER_ID);
        return new FakturowniaReceiptConfig(
                normalisedUrl,
                required(configuration, API_KEY),
                required(configuration, DEPARTMENT_ID),
                printerId == null || printerId.isBlank() ? null : printerId.strip(),
                required(configuration, WEBHOOK_TOKEN),
                lineNameLength(configuration.get(LINE_NAME_LENGTH)));
    }

    private static String required(Map<String, String> configuration, String key) {
        String value = configuration.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.strip();
    }

    private static int lineNameLength(String value) {
        if (value == null || value.isBlank()) {
            return MAX_LINE_NAME_LENGTH;
        }
        int length;
        try {
            length = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(LINE_NAME_LENGTH + " must be a number: " + value, e);
        }
        if (length < 1 || length > MAX_LINE_NAME_LENGTH) {
            throw new IllegalArgumentException(LINE_NAME_LENGTH + " must be between 1 and " + MAX_LINE_NAME_LENGTH + ": " + length);
        }
        return length;
    }

    @Override
    public String toString() {
        return "FakturowniaReceiptConfig[apiUrl=" + apiUrl + ", departmentId=" + departmentId + ", printerId=" + printerId
                + ", lineNameLength=" + lineNameLength + "]";
    }
}
