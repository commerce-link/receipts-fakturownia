package pl.commercelink.receipts.fakturownia;

import org.junit.jupiter.api.AfterEach;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.testing.ReceiptProviderContractTest;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Endpoint;
import pl.commercelink.receipts.fakturownia.FakeFakturownia.Fault;

import java.util.Optional;
import java.util.OptionalInt;

/** The contract kit from receipts-api, run against {@link FakeFakturownia} (one fresh backend per test). */
class FakturowniaReceiptProviderContractTest extends ReceiptProviderContractTest {

    private final FakeFakturownia fake = new FakeFakturownia();

    @AfterEach
    void stop() {
        fake.close();
    }

    @Override
    protected ReceiptProvider provider() {
        return FakturowniaTestSupport.provider(fake);
    }

    @Override
    protected String uniqueReceiptKey() {
        return FakturowniaTestSupport.uniqueKey();
    }

    @Override
    protected void settle(Receipt pending, ReceiptState target) {
        switch (target) {
            case FISCALISED -> fake.settleFiscalised(pending.providerReceiptId());
            case FAILED -> fake.settleFiscalError(pending.providerReceiptId(), "Niepoprawna wartość brutto na dokumencie");
            default -> throw new IllegalArgumentException("Cannot settle to " + target);
        }
    }

    @Override
    protected Optional<ReceiptProvider> providerWithFailingTransport() {
        fake.failNext(Endpoint.CREATE, new Fault.SlowAnswer(FakturowniaTestSupport.SLOWER_THAN_TIMEOUT_MILLIS));
        return Optional.of(provider());
    }

    @Override
    protected Optional<ReceiptProvider> providerWithRejectingBackend() {
        fake.failAlways(Endpoint.CREATE, new Fault.Status(422,
                "{\"code\":\"error\",\"message\":{\"positions\":[\"nieprawidłowa stawka\"]}}"));
        return Optional.of(provider());
    }

    @Override
    protected Optional<ReceiptProvider> providerLosingNextResponse() {
        fake.failNext(Endpoint.CREATE, new Fault.DropAfterApplying());
        return Optional.of(provider());
    }

    @Override
    protected OptionalInt createCalls() {
        return OptionalInt.of(fake.createCalls());
    }

    @Override
    protected OptionalInt remoteCalls() {
        return OptionalInt.of(fake.requests());
    }
}
