package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A failed call to the Fakturownia API, classified by how far it got. The provider turns the kind into the
 * contract's exceptions: nothing sent, sent without a usable answer, or an HTTP error status.
 */
final class FakturowniaApiException extends RuntimeException {

    enum Kind {
        /** The request never left this JVM (connection refused, connect timeout). */
        NOT_SENT,
        /** The request may have reached Fakturownia but no usable answer came back (timeout, dropped connection, unreadable 2xx body). */
        SENT_NO_ANSWER,
        /** Fakturownia answered with a non-2xx status. */
        HTTP
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Kind kind;
    private final int status;
    private final String body;

    private FakturowniaApiException(Kind kind, int status, String body, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.body = body;
    }

    static FakturowniaApiException notSent(Throwable cause) {
        return new FakturowniaApiException(Kind.NOT_SENT, 0, null, "Request not sent: " + cause, cause);
    }

    static FakturowniaApiException sentNoAnswer(String message, Throwable cause) {
        return new FakturowniaApiException(Kind.SENT_NO_ANSWER, 0, null, message, cause);
    }

    static FakturowniaApiException http(int status, String body) {
        return new FakturowniaApiException(Kind.HTTP, status, body, "HTTP " + status + ": " + body, null);
    }

    Kind kind() {
        return kind;
    }

    /** HTTP status for {@link Kind#HTTP}, otherwise 0. */
    int status() {
        return status;
    }

    /** Fakturownia's {@code code} from the error body, or the HTTP status when the body has none. */
    String providerCode() {
        JsonNode code = parsedBody().path("code");
        return code.isTextual() && !code.asText().isBlank() ? code.asText() : String.valueOf(status);
    }

    /**
     * Fakturownia's {@code message}, which is either plain text or an object of field errors
     * ({@code {"oid":["jest już zajęte"]}} becomes {@code "oid: jest już zajęte"}). Falls back to the raw body.
     */
    String providerMessage() {
        JsonNode message = parsedBody().path("message");
        if (message.isTextual() && !message.asText().isBlank()) {
            return message.asText();
        }
        if (message.isObject() && !message.isEmpty()) {
            List<String> parts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = message.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<String> texts = new ArrayList<>();
                if (field.getValue().isArray()) {
                    field.getValue().forEach(item -> texts.add(item.asText()));
                } else {
                    texts.add(field.getValue().asText());
                }
                parts.add(field.getKey() + ": " + String.join(", ", texts));
            }
            return String.join("; ", parts);
        }
        return body == null || body.isBlank() ? "HTTP " + status : body;
    }

    /**
     * Whether the error body names this field: a key of the object-shaped {@code message}
     * ({@code {"message":{"oid":[…]}}}), or the field as a whole word in a plain-text {@code message} or body.
     */
    boolean mentionsField(String field) {
        JsonNode message = parsedBody().path("message");
        if (message.isObject()) {
            return message.has(field);
        }
        String text = message.isTextual() ? message.asText() : body;
        return text != null && Pattern.compile("\\b" + Pattern.quote(field) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private JsonNode parsedBody() {
        if (body == null || body.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }
}
