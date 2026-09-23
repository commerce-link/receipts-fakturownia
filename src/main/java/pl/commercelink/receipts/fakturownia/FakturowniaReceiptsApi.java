package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin transport over the Fakturownia REST API (https://github.com/fakturownia/API,
 * https://github.com/e-paragony/api). Never retries: every failure is thrown as a
 * {@link FakturowniaApiException} whose kind tells whether the request could have had an effect. Reads and
 * the POST/PUT writes go through the shared JDK client (it never resends POST or PUT); the side-effecting
 * {@code fiscal_print} GET goes through {@link OneShotHttpGet}.
 */
class FakturowniaReceiptsApi {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    FakturowniaReceiptsApi(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    /** {@code POST /invoices.json}; returns the created document. */
    JsonNode createReceipt(ObjectNode invoice) {
        ObjectNode body = json.createObjectNode();
        body.set("invoice", invoice);
        return readJson(send(request("/invoices.json").POST(HttpRequest.BodyPublishers.ofString(write(body)))
                .header("Content-Type", "application/json")));
    }

    /** {@code GET /invoices/{id}.json}. */
    JsonNode getReceipt(String id) {
        return readJson(send(request("/invoices/" + encode(id) + ".json").GET()));
    }

    /**
     * {@code GET /invoices.json?oid=…&kind=receipt&period=all&department_id=…&per_page=100}. The server-side
     * {@code oid} filter may match more than the exact value; callers filter the result.
     */
    List<JsonNode> findReceiptsByOid(String oid, String departmentId) {
        String query = "?oid=" + encode(oid) + "&kind=receipt&period=all&department_id=" + encode(departmentId)
                + "&per_page=100";
        JsonNode list = readJson(send(request("/invoices.json" + query).GET()));
        if (!list.isArray()) {
            throw FakturowniaApiException.sentNoAnswer("Expected a JSON array from /invoices.json, got: " + list, null);
        }
        List<JsonNode> result = new ArrayList<>();
        list.forEach(result::add);
        return result;
    }

    /** {@code PUT /invoices/{id}.json} replacing the private note. */
    void updateInternalNote(String id, String note) {
        ObjectNode invoice = json.createObjectNode().put("internal_note", note);
        ObjectNode body = json.createObjectNode();
        body.set("invoice", invoice);
        send(request("/invoices/" + encode(id) + ".json").PUT(HttpRequest.BodyPublishers.ofString(write(body)))
                .header("Content-Type", "application/json"));
    }

    /**
     * {@code GET /invoices/fiscal_print?id=…&mode=e-receipt[&fiskator_name=…]}: orders fiscalisation on the
     * printer. The response body is undocumented and ignored; a 2xx only means the job was queued.
     *
     * <p>Sent through {@link OneShotHttpGet}, never through {@link HttpClient}: the JDK client silently resends
     * a GET when the server closes the connection before answering, and Fakturownia documents this
     * side-effecting call as a GET — a resend would fiscalise the sale twice.
     */
    void orderFiscalPrint(String id, String printerId) {
        String query = "?id=" + encode(id) + "&mode=e-receipt";
        if (printerId != null && !printerId.isBlank()) {
            query += "&fiskator_name=" + encode(printerId);
        }
        OneShotHttpGet.Response response = new OneShotHttpGet((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(), CONNECT_TIMEOUT, timeout)
                .get(URI.create(baseUrl + "/invoices/fiscal_print" + query),
                        Map.of("Accept", "application/json", "Authorization", "Bearer " + apiKey));
        if (response.status() / 100 != 2) {
            throw FakturowniaApiException.http(response.status(), response.body());
        }
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    private String send(HttpRequest.Builder builder) {
        HttpResponse<String> response;
        try {
            response = SHARED_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw FakturowniaApiException.notSent(e);
        } catch (IOException e) {
            throw FakturowniaApiException.sentNoAnswer("No answer from Fakturownia: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FakturowniaApiException.sentNoAnswer("Interrupted while waiting for Fakturownia", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw FakturowniaApiException.http(response.statusCode(), response.body());
        }
        return response.body();
    }

    private JsonNode readJson(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw FakturowniaApiException.sentNoAnswer("Empty response body from Fakturownia", null);
            }
            return node;
        } catch (JsonProcessingException e) {
            throw FakturowniaApiException.sentNoAnswer("Unreadable response body from Fakturownia", e);
        }
    }

    private String write(ObjectNode body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise request body", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
