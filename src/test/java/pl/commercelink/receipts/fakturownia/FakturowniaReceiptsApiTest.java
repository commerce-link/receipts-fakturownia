package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Endpoint;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Fault;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaReceiptsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeFakturownia fake = new FakeFakturownia();
    private final FakturowniaReceiptsApi api = new FakturowniaReceiptsApi(fake.url() + "/", FakeFakturownia.API_KEY, Duration.ofMillis(300));

    @AfterEach
    void stop() {
        fake.close();
    }

    private static ObjectNode receipt(String oid) {
        return JSON.createObjectNode().put("kind", "receipt").put("oid", oid).put("oid_unique", "yes")
                .put("department_id", FakeFakturownia.DEPARTMENT_ID);
    }

    @Test
    void createReturnsTheStoredDocumentAndSendsBearerToken() {
        // when
        JsonNode created = api.createReceipt(receipt("order-1:R1"));

        // then
        assertEquals("order-1:R1", created.get("oid").asText());
        assertEquals(1, fake.createCalls());
        assertEquals("POST /invoices.json", fake.requestLog().getFirst());
    }

    @Test
    void wrongApiKeyIsAnHttp401() {
        // given
        FakturowniaReceiptsApi wrongKey = new FakturowniaReceiptsApi(fake.url(), "other", Duration.ofMillis(300));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> wrongKey.getReceipt("1"));

        // then
        assertEquals(FakturowniaApiException.Kind.HTTP, failure.kind());
        assertEquals(401, failure.status());
    }

    @Test
    void validationErrorExposesFieldMessagesAndCode() {
        // given
        api.createReceipt(receipt("order-1:R1"));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.createReceipt(receipt("order-1:R1")));

        // then
        assertEquals(FakturowniaApiException.Kind.HTTP, failure.kind());
        assertEquals(422, failure.status());
        assertEquals("error", failure.providerCode());
        assertEquals("oid: jest już zajęte", failure.providerMessage());
    }

    @Test
    void plainTextErrorMessageIsKept() {
        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.getReceipt("404404"));

        // then
        assertEquals(404, failure.status());
        assertEquals("Nie znaleziono", failure.providerMessage());
    }

    @Test
    void nonJsonErrorBodyFallsBackToStatusAndBody() {
        // given
        fake.failNext(Endpoint.GET, new Fault.Status(502, "<html>Bad gateway</html>"));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.getReceipt("1"));

        // then
        assertEquals("502", failure.providerCode());
        assertEquals("<html>Bad gateway</html>", failure.providerMessage());
    }

    @Test
    void timeoutAfterSendingIsSentNoAnswer() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.SlowAnswer(1500));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.createReceipt(receipt("order-1:R1")));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void droppedConnectionAfterSendingIsSentNoAnswer() {
        // given
        fake.failNext(Endpoint.CREATE, new Fault.DropAfterApplying());

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.createReceipt(receipt("order-1:R1")));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
        assertEquals(1, fake.createCalls());
    }

    @Test
    void refusedConnectionIsNotSent() throws IOException {
        // given
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        FakturowniaReceiptsApi unreachable = new FakturowniaReceiptsApi("http://127.0.0.1:" + closedPort, "k", Duration.ofMillis(300));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> unreachable.getReceipt("1"));

        // then
        assertEquals(FakturowniaApiException.Kind.NOT_SENT, failure.kind());
    }

    @Test
    void unreadableSuccessBodyIsSentNoAnswer() {
        // given
        fake.failNext(Endpoint.GET, new Fault.Status(200, "not json"));

        // when
        FakturowniaApiException failure = assertThrows(FakturowniaApiException.class, () -> api.getReceipt("1"));

        // then
        assertEquals(FakturowniaApiException.Kind.SENT_NO_ANSWER, failure.kind());
    }

    @Test
    void findSendsOidKindPeriodAndDepartment() {
        // given
        api.createReceipt(receipt("order-1:R1"));
        api.createReceipt(receipt("order-1:R11"));

        // when
        List<JsonNode> found = api.findReceiptsByOid("order-1:R1", FakeFakturownia.DEPARTMENT_ID);

        // then
        assertEquals(2, found.size(), "the fake matches substrings, like the real search may");
        assertEquals("GET /invoices.json?oid=order-1%3AR1&kind=receipt&period=all&department_id=7&per_page=100",
                fake.requestLog().getLast());
    }

    @Test
    void updateInternalNoteReplacesTheNote() {
        // given
        String id = api.createReceipt(receipt("order-1:R1")).get("id").asText();

        // when
        api.updateInternalNote(id, "commercelink:fiscal-print-ordered");

        // then
        assertEquals("commercelink:fiscal-print-ordered", fake.invoice(id).get("internal_note").asText());
        assertEquals("PUT /invoices/" + id + ".json", fake.requestLog().getLast());
    }

    @Test
    void fiscalPrintIgnoresNonJsonBodyAndPassesPrinter() {
        // given
        String id = api.createReceipt(receipt("order-1:R1")).get("id").asText();

        // when
        api.orderFiscalPrint(id, "12");

        // then
        assertEquals(1, fake.fiscalPrintCalls(id));
        assertEquals("GET /invoices/fiscal_print?id=" + id + "&mode=e-receipt&fiskator_name=12", fake.requestLog().getLast());
    }

    @Test
    void fiscalPrintWithoutPrinterUsesTheDefault() {
        // given
        String id = api.createReceipt(receipt("order-1:R1")).get("id").asText();

        // when
        api.orderFiscalPrint(id, "");

        // then
        assertTrue(fake.requestLog().getLast().endsWith("&mode=e-receipt"));
    }
}
