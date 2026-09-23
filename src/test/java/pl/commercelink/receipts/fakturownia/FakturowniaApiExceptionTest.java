package pl.commercelink.receipts.fakturownia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaApiExceptionTest {

    @Test
    void mentionsFieldMatchesAnObjectKeyThatIsTheFieldWithASuffix() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"oid_unique\":[\"jest już zajęte\"]}}");

        // when / then
        assertTrue(exception.mentionsField("oid"));
    }

    @Test
    void mentionsFieldMatchesTheFieldWithASuffixInPlainText() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":\"Oid_unique has already been taken\"}");

        // when / then
        assertTrue(exception.mentionsField("oid"));
    }

    @Test
    void mentionsFieldDoesNotMatchAnObjectKeyThatOnlySharesAPrefix() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"void_reason\":[\"jest nieprawidłowe\"]}}");

        // when / then
        assertFalse(exception.mentionsField("oid"));
    }

    @Test
    void mentionsFieldDoesNotMatchTextThatOnlySharesLetters() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":\"avoid oidx\"}");

        // when / then
        assertFalse(exception.mentionsField("oid"));
    }

    @Test
    void mentionsFieldDoesNotMatchAnUnrelatedObjectKey() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"buyer_email\":[\"jest nieprawidłowy\"]}}");

        // when / then
        assertFalse(exception.mentionsField("oid"));
    }
}
