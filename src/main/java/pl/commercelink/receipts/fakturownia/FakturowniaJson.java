package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The one {@link ObjectMapper} the module needs; stateless and thread-safe, so every class shares it. */
final class FakturowniaJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private FakturowniaJson() {
    }
}
