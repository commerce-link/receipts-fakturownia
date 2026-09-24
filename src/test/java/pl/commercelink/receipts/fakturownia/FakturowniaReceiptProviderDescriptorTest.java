package pl.commercelink.receipts.fakturownia;

import org.junit.jupiter.api.Test;
import pl.commercelink.provider.api.EventBinding.WebhookBinding;
import pl.commercelink.provider.api.ProviderField;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaReceiptProviderDescriptorTest {

    private final FakturowniaReceiptProviderDescriptor descriptor = new FakturowniaReceiptProviderDescriptor();

    private static Map<String, String> config(String... overrides) {
        Map<String, String> config = new HashMap<>(Map.of(
                "apiUrl", "https://shop.fakturownia.pl/",
                "apiKey", "token",
                "departmentId", "7",
                "webhookToken", "hook"));
        for (int i = 0; i < overrides.length; i += 2) {
            config.put(overrides[i], overrides[i + 1]);
        }
        return config;
    }

    @Test
    void isRegisteredAsAServiceUnderItsStableName() {
        // when
        List<ReceiptProviderDescriptor> descriptors = ServiceLoader.load(ReceiptProviderDescriptor.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        // then
        assertEquals(1, descriptors.size());
        assertEquals("fakturownia", descriptors.getFirst().name());
        assertEquals("Paragony.pl (Fakturownia)", descriptors.getFirst().displayName());
    }

    @Test
    void declaresTheConfigurationFields() {
        // when
        List<ProviderField> fields = descriptor.configurationFields();

        // then
        assertEquals(List.of("apiUrl", "apiKey", "departmentId", "printerId", "webhookToken", "lineNameLength"),
                fields.stream().map(ProviderField::key).toList());
        assertEquals(ProviderField.FieldType.URL, fields.get(0).type());
        assertEquals(ProviderField.FieldType.PASSWORD, fields.get(1).type());
        assertEquals(ProviderField.FieldType.PASSWORD, fields.get(4).type());
        assertEquals(ProviderField.FieldType.NUMBER, fields.get(5).type());
        assertEquals(List.of(true, true, true, false, true, false), fields.stream().map(ProviderField::required).toList());
    }

    @Test
    void declaresOneWebhookBindingUnderTheProviderName() {
        // when
        WebhookBinding<?> binding = assertInstanceOf(WebhookBinding.class, descriptor.bindings().getFirst());

        // then
        assertEquals(1, descriptor.bindings().size());
        assertEquals("fakturownia", binding.path());
    }

    @Test
    void createsAProviderWithDefaults() {
        // when
        ReceiptProvider provider = descriptor.create(config());

        // then
        assertEquals(40, provider.maxLineNameLength());
        assertTrue(provider.requiresBuyerEmail());
    }

    @Test
    void refusesMissingRequiredFields() {
        for (String field : List.of("apiUrl", "apiKey", "departmentId", "webhookToken")) {
            // given
            Map<String, String> config = config();
            config.remove(field);

            // when
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> descriptor.create(config));

            // then
            assertTrue(failure.getMessage().contains(field), failure.getMessage());
        }
    }
}
