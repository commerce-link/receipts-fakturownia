package pl.commercelink.receipts.fakturownia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakturowniaApiExceptionTest {

    @Test
    void mentionsOidMatchesAnObjectKeyThatIsOidWithASuffix() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"oid_unique\":[\"jest już zajęte\"]}}");

        // when / then
        assertTrue(exception.mentionsOid());
    }

    @Test
    void mentionsOidMatchesOidWithASuffixInPlainText() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":\"Oid_unique has already been taken\"}");

        // when / then
        assertTrue(exception.mentionsOid());
    }

    @Test
    void mentionsOidDoesNotMatchAnObjectKeyThatOnlySharesAPrefix() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"void_reason\":[\"jest nieprawidłowe\"]}}");

        // when / then
        assertFalse(exception.mentionsOid());
    }

    @Test
    void mentionsOidDoesNotMatchTextThatOnlySharesLetters() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":\"avoid oidx\"}");

        // when / then
        assertFalse(exception.mentionsOid());
    }

    @Test
    void mentionsOidDoesNotMatchAnUnrelatedObjectKey() {
        // given
        FakturowniaApiException exception = FakturowniaApiException.http(422,
                "{\"code\":\"error\",\"message\":{\"buyer_email\":[\"jest nieprawidłowy\"]}}");

        // when / then
        assertFalse(exception.mentionsOid());
    }
}
