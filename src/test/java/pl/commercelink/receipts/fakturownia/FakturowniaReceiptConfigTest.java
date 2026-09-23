package pl.commercelink.receipts.fakturownia;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaReceiptConfigTest {

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
    void readsAllFields() {
        // when
        FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(config("printerId", " 12 ", "lineNameLength", " 38 "));

        // then
        assertEquals("https://shop.fakturownia.pl", config.apiUrl());
        assertEquals("token", config.apiKey());
        assertEquals("7", config.departmentId());
        assertEquals("12", config.printerId());
        assertEquals("hook", config.webhookToken());
        assertEquals(38, config.lineNameLength());
    }

    @Test
    void appliesDefaultsForOptionalFields() {
        // when
        FakturowniaReceiptConfig config = FakturowniaReceiptConfig.from(config("printerId", "  "));

        // then
        assertNull(config.printerId());
        assertEquals(40, config.lineNameLength());
    }

    @Test
    void keepsSecretsOutOfToString() {
        // when
        String text = FakturowniaReceiptConfig.from(config()).toString();

        // then
        assertFalse(text.contains("token"), text);
        assertFalse(text.contains("hook"), text);
    }

    @Test
    void refusesMissingOrBlankRequiredFields() {
        for (String field : List.of("apiUrl", "apiKey", "departmentId", "webhookToken")) {
            // given
            Map<String, String> missing = config();
            missing.remove(field);

            // when
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(missing));
            IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config(field, " ")));

            // then
            assertTrue(failure.getMessage().contains(field), failure.getMessage());
            assertTrue(blank.getMessage().contains(field), blank.getMessage());
        }
    }

    @Test
    void refusesAnApiUrlThatIsNotHttp() {
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("apiUrl", "shop.fakturownia.pl")));
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("apiUrl", "ftp://shop.fakturownia.pl")));
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("apiUrl", "https://shop fakturownia")));
    }

    @Test
    void refusesALineNameLengthOutsideOneToForty() {
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("lineNameLength", "0")));
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("lineNameLength", "41")));
        assertThrows(IllegalArgumentException.class, () -> FakturowniaReceiptConfig.from(config("lineNameLength", "forty")));
        assertEquals(1, FakturowniaReceiptConfig.from(config("lineNameLength", "1")).lineNameLength());
    }
}
