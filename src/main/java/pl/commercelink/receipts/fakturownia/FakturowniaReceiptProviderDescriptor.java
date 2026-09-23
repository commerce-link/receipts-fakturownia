package pl.commercelink.receipts.fakturownia;

import pl.commercelink.provider.api.EventBinding;
import pl.commercelink.provider.api.EventBinding.WebhookBinding;
import pl.commercelink.provider.api.ProviderField;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Paragony.pl e-receipts issued through the Fakturownia API. */
public final class FakturowniaReceiptProviderDescriptor implements ReceiptProviderDescriptor {

    static final String NAME = "fakturownia";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "Paragony.pl (Fakturownia)";
    }

    @Override
    public List<ProviderField> configurationFields() {
        return List.of(
                new ProviderField(FakturowniaReceiptConfig.API_URL, "API URL", ProviderField.FieldType.URL, true, "https://example.fakturownia.pl"),
                new ProviderField(FakturowniaReceiptConfig.API_KEY, "API token", ProviderField.FieldType.PASSWORD, true, ""),
                new ProviderField(FakturowniaReceiptConfig.DEPARTMENT_ID, "Department ID", ProviderField.FieldType.TEXT, true, ""),
                new ProviderField(FakturowniaReceiptConfig.PRINTER_ID, "Fiscal printer ID (empty = default printer)", ProviderField.FieldType.TEXT, false, ""),
                new ProviderField(FakturowniaReceiptConfig.WEBHOOK_TOKEN, "Webhook API token", ProviderField.FieldType.PASSWORD, true, ""),
                new ProviderField(FakturowniaReceiptConfig.LINE_NAME_LENGTH, "Max line name length (1-40)", ProviderField.FieldType.NUMBER, false, "40")
        );
    }

    @Override
    public ReceiptProvider create(Map<String, String> configuration) {
        FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(configuration);
        return new FakturowniaReceiptProvider(
                new FakturowniaReceiptsApi(config.apiUrl(), config.apiKey(), FakturowniaReceiptsApi.DEFAULT_TIMEOUT),
                config, Clock.systemUTC());
    }

    @Override
    public List<EventBinding<?>> bindings() {
        return List.of(new WebhookBinding<>(NAME, new FakturowniaReceiptWebhookExecutor(Clock.systemUTC())));
    }
}
