package pl.commercelink.receipts.fakturownia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful stand-in for the Fakturownia API on a real local HTTP server, so the adapter's transport,
 * status handling and timeouts are exercised end to end. Mirrors the documented behaviour: receipts under
 * {@code /invoices}, {@code oid_unique} refusing a second document with the same {@code oid} (422), an
 * {@code oid} list filter that matches substrings (so callers must filter exactly), and {@code fiscal_print}
 * answering with a non-JSON body.
 */
final class FakeFakturownia implements AutoCloseable {

    static final String API_KEY = "fake-api-key";
    static final String DEPARTMENT_ID = "7";

    enum Endpoint { CREATE, LIST, GET, UPDATE, FISCAL_PRINT }

    /** What the server does instead of answering normally. */
    sealed interface Fault {
        /** Answer with this status and body; the call has no effect. */
        record Status(int status, String body) implements Fault {}

        /** Apply the call, then answer only after the client has given up. */
        record SlowAnswer(long millis) implements Fault {}

        /** Apply the call, then close the connection without answering. */
        record DropAfterApplying() implements Fault {}

        /** Answer normally (lets a queue of faults skip a call). */
        record Pass() implements Fault {}
    }

    private static final Pattern DOCUMENT = Pattern.compile("^/invoices/([^/]+)\\.json$");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, ObjectNode> invoices = new LinkedHashMap<>();
    private final Map<Endpoint, Deque<Fault>> nextFaults = new EnumMap<>(Endpoint.class);
    private final Map<Endpoint, Fault> permanentFaults = new EnumMap<>(Endpoint.class);
    private final Map<String, Integer> fiscalPrintsById = new HashMap<>();
    private final List<String> requestLog = new ArrayList<>();
    private long nextId = 1000;
    private int createCalls;
    private int fiscalPrintCalls;
    private int requests;
    private boolean listOmitsInternalNote;
    private boolean ignoreInternalNoteUpdates;
    private boolean autoFiscalisation;
    private boolean queueOnMarker;
    private String statusBeforeFiscalPrint;
    private String linkBeforeNextFiscalPrint;
    private String lastFiscalPrintAccept;

    FakeFakturownia() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- test controls -------------------------------------------------------------------------------

    synchronized void failNext(Endpoint endpoint, Fault fault) {
        nextFaults.computeIfAbsent(endpoint, e -> new ArrayDeque<>()).add(fault);
    }

    synchronized void failAlways(Endpoint endpoint, Fault fault) {
        permanentFaults.put(endpoint, fault);
    }

    /** The list endpoint leaves {@code internal_note} out of its documents (its fields are undocumented). */
    synchronized void listOmitsInternalNote() {
        listOmitsInternalNote = true;
    }

    /** A PUT answers 200 but does not persist {@code internal_note} (whether receipts accept it is undocumented). */
    synchronized void ignoreInternalNoteUpdates() {
        ignoreInternalNoteUpdates = true;
    }

    /** The account option "Automatyczna fiskalizacja paragonów po utworzeniu przez API" is on: every new receipt is queued at once. */
    synchronized void autoFiscalisation() {
        autoFiscalisation = true;
    }

    /**
     * Someone queues the receipt while the adapter marks it: a PUT that stores {@code internal_note} also sets
     * {@code fiscal_status: "to_print"}, as if the operator clicked "fiscal print" between the adapter's reads.
     */
    synchronized void queueOnMarker() {
        queueOnMarker = true;
    }

    /**
     * The next {@code fiscal_print} request finds the receipt already at this fiscal status, as if it was queued
     * or settled after the adapter's last read. The status is set before any fault on that request answers.
     */
    synchronized void settleBeforeNextFiscalPrint(String status) {
        statusBeforeFiscalPrint = status;
    }

    /**
     * The next {@code fiscal_print} request finds the receipt already carrying an {@code e_receipt_view_url},
     * with no {@code fiscal_status}, as if the link was published without the field being set. Applied before
     * any fault on that request answers, like {@link #settleBeforeNextFiscalPrint(String)}.
     */
    synchronized void settleLinkBeforeNextFiscalPrint(String url) {
        linkBeforeNextFiscalPrint = url;
    }

    /** The {@code Accept} header of the last {@code fiscal_print} request. */
    synchronized String lastFiscalPrintAccept() {
        return lastFiscalPrintAccept;
    }

    /** The printer fiscalised the sale and the hub issued the e-receipt. */
    synchronized void settleFiscalised(String id) {
        ObjectNode invoice = require(id);
        invoice.put("fiscal_status", "er_printed");
        invoice.put("e_receipt_view_url", "https://test.paragony.pl/eR" + id);
        invoice.put("updated_at", "2026-09-22T12:05:01.000+02:00");
    }

    /** The printer reached this fiscal status (e.g. {@code er_fatal}: fiscalised on paper, no e-receipt link). */
    synchronized void settleFiscalStatus(String id, String status) {
        ObjectNode invoice = require(id);
        invoice.put("fiscal_status", status);
        invoice.put("updated_at", "2026-09-22T12:05:01.000+02:00");
    }

    /** The printer refused the sale; Fakturownia keeps its message in {@code fiscal_print_error}. */
    synchronized void settleFiscalError(String id, String message) {
        ObjectNode invoice = require(id);
        invoice.put("fiscal_status", "error");
        invoice.put("fiscal_print_error", message);
        invoice.put("updated_at", "2026-09-22T12:05:01.000+02:00");
    }

    /** The operator marked the receipt as rejected in Fakturownia. */
    synchronized void settleCancelled(String id) {
        require(id).put("status", "rejected");
    }

    /** Stores a document as-is (used to prepare webhook scenarios). */
    synchronized void putInvoice(ObjectNode invoice) {
        invoices.put(invoice.get("id").asText(), invoice);
    }

    synchronized ObjectNode invoice(String id) {
        return invoices.get(id);
    }

    synchronized int invoiceCount() {
        return invoices.size();
    }

    synchronized int createCalls() {
        return createCalls;
    }

    synchronized int fiscalPrintCalls() {
        return fiscalPrintCalls;
    }

    synchronized int fiscalPrintCalls(String id) {
        return fiscalPrintsById.getOrDefault(id, 0);
    }

    synchronized int requests() {
        return requests;
    }

    /** "METHOD /path?query" of every request, in order. */
    synchronized List<String> requestLog() {
        return List.copyOf(requestLog);
    }

    // ---- HTTP ----------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String body = read(exchange.getRequestBody());
            Endpoint endpoint = endpointOf(method, path);
            Fault fault;
            synchronized (this) {
                requests++;
                requestLog.add(method + " " + path + (rawQuery == null ? "" : "?" + rawQuery));
                fault = endpoint == null ? null : takeFault(endpoint);
                if (endpoint == Endpoint.FISCAL_PRINT) {
                    lastFiscalPrintAccept = exchange.getRequestHeaders().getFirst("Accept");
                    String id = query(rawQuery).get("id");
                    if (statusBeforeFiscalPrint != null && id != null && invoices.containsKey(id)) {
                        invoices.get(id).put("fiscal_status", statusBeforeFiscalPrint);
                        statusBeforeFiscalPrint = null;
                    }
                    if (linkBeforeNextFiscalPrint != null && id != null && invoices.containsKey(id)) {
                        invoices.get(id).put("e_receipt_view_url", linkBeforeNextFiscalPrint);
                        linkBeforeNextFiscalPrint = null;
                    }
                }
            }
            if (!("Bearer " + API_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{\"code\":\"error\",\"message\":\"Unauthorized\"}");
                return;
            }
            if (endpoint == null) {
                respond(exchange, 404, "{\"code\":\"error\",\"message\":\"Not found\"}");
                return;
            }
            if (fault instanceof Fault.Status status) {
                respond(exchange, status.status(), status.body());
                return;
            }
            Answer answer;
            synchronized (this) {
                answer = apply(endpoint, path, query(rawQuery), body);
            }
            if (fault instanceof Fault.DropAfterApplying) {
                return; // closing the exchange without a response drops the connection
            }
            if (fault instanceof Fault.SlowAnswer slow) {
                sleep(slow.millis());
            }
            respond(exchange, answer.status(), answer.body());
        }
    }

    private record Answer(int status, String body) {}

    private Answer apply(Endpoint endpoint, String path, Map<String, String> query, String body) throws IOException {
        return switch (endpoint) {
            case CREATE -> create((ObjectNode) json.readTree(body).get("invoice"));
            case LIST -> list(query);
            case GET -> get(documentId(path));
            case UPDATE -> update(documentId(path), (ObjectNode) json.readTree(body).get("invoice"));
            case FISCAL_PRINT -> fiscalPrint(query.get("id"));
        };
    }

    private Answer create(ObjectNode input) {
        String oid = input.path("oid").asText(null);
        if ("yes".equals(input.path("oid_unique").asText()) && oid != null
                && invoices.values().stream().anyMatch(existing -> oid.equals(existing.path("oid").asText(null)))) {
            return new Answer(422, "{\"code\":\"error\",\"message\":{\"oid\":[\"jest już zajęte\"]}}");
        }
        long id = nextId++;
        ObjectNode invoice = input.deepCopy();
        invoice.put("id", id);
        invoice.put("number", "PAR " + id + "/2026");
        invoice.put("token", "tok" + id);
        invoice.put("view_url", "https://fake.fakturownia.pl/invoice/tok" + id);
        invoice.put("created_at", "2026-09-22T12:00:00.000+02:00");
        invoice.put("updated_at", "2026-09-22T12:00:00.000+02:00");
        invoice.putNull("e_receipt_view_url");
        invoice.putNull("print_time");
        if (autoFiscalisation && "receipt".equals(invoice.path("kind").asText())) {
            invoice.put("fiscal_status", "to_print");
        } else {
            invoice.putNull("fiscal_status");
        }
        invoice.put("cancelled", false);
        invoice.remove("oid_unique");
        invoices.put(String.valueOf(id), invoice);
        createCalls++;
        return new Answer(201, invoice.toString());
    }

    private Answer list(Map<String, String> query) {
        ArrayNode result = json.createArrayNode();
        for (ObjectNode invoice : invoices.values()) {
            if (query.containsKey("oid") && !invoice.path("oid").asText("").contains(query.get("oid"))) {
                continue;
            }
            if (query.containsKey("kind") && !query.get("kind").equals(invoice.path("kind").asText())) {
                continue;
            }
            if (query.containsKey("department_id") && !query.get("department_id").equals(invoice.path("department_id").asText())) {
                continue;
            }
            ObjectNode copy = invoice.deepCopy();
            if (listOmitsInternalNote) {
                copy.remove("internal_note");
            }
            result.add(copy);
        }
        return new Answer(200, result.toString());
    }

    private Answer get(String id) {
        ObjectNode invoice = invoices.get(id);
        return invoice == null ? notFound() : new Answer(200, invoice.toString());
    }

    private Answer update(String id, ObjectNode changes) {
        ObjectNode invoice = invoices.get(id);
        if (invoice == null) {
            return notFound();
        }
        ObjectNode applied = changes.deepCopy();
        if (ignoreInternalNoteUpdates) {
            applied.remove("internal_note");
        }
        invoice.setAll(applied);
        if (queueOnMarker && applied.hasNonNull("internal_note") && !applied.get("internal_note").asText().isEmpty()) {
            invoice.put("fiscal_status", "to_print");
        }
        invoice.put("updated_at", "2026-09-22T12:00:30.000+02:00");
        return new Answer(200, invoice.toString());
    }

    private Answer fiscalPrint(String id) {
        if (id == null || !invoices.containsKey(id)) {
            return notFound();
        }
        fiscalPrintCalls++;
        fiscalPrintsById.merge(id, 1, Integer::sum);
        invoices.get(id).put("fiscal_status", "to_print");
        return new Answer(200, "OK");
    }

    private static Answer notFound() {
        return new Answer(404, "{\"code\":\"error\",\"message\":\"Nie znaleziono\"}");
    }

    private Fault takeFault(Endpoint endpoint) {
        Deque<Fault> queue = nextFaults.get(endpoint);
        if (queue != null && !queue.isEmpty()) {
            return queue.poll();
        }
        return permanentFaults.get(endpoint);
    }

    private ObjectNode require(String id) {
        ObjectNode invoice = invoices.get(id);
        if (invoice == null) {
            throw new IllegalArgumentException("No invoice " + id);
        }
        return invoice;
    }

    private static Endpoint endpointOf(String method, String path) {
        if (path.equals("/invoices.json")) {
            return switch (method) {
                case "POST" -> Endpoint.CREATE;
                case "GET" -> Endpoint.LIST;
                default -> null;
            };
        }
        if (path.equals("/invoices/fiscal_print")) {
            return "GET".equals(method) ? Endpoint.FISCAL_PRINT : null;
        }
        if (DOCUMENT.matcher(path).matches()) {
            return switch (method) {
                case "GET" -> Endpoint.GET;
                case "PUT" -> Endpoint.UPDATE;
                default -> null;
            };
        }
        return null;
    }

    private static String documentId(String path) {
        Matcher matcher = DOCUMENT.matcher(path);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(path);
        }
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
