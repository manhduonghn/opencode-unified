package ai.opencode.server.model

import ai.opencode.core.ui.appFormattingLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale

class RequestReviewTests {
    @Test fun typedNumbersReadMidEntryTextAndLocaleSeparators() {
        val us = Locale.forLanguageTag("en-US")
        val de = Locale.forLanguageTag("de-DE")
        val fr = Locale.forLanguageTag("fr-FR")
        assertEquals(OpenCodeFormValue.Number(1.0), OpenCodeFormValue.typedNumber("1.", us))
        assertEquals(OpenCodeFormValue.Number(1.5), OpenCodeFormValue.typedNumber("1.5", us))
        assertEquals(OpenCodeFormValue.Number(1234.5), OpenCodeFormValue.typedNumber("1,234.5", us))
        assertEquals(OpenCodeFormValue.Number(1.0), OpenCodeFormValue.typedNumber("1,", de))
        assertEquals(OpenCodeFormValue.Number(1234.5), OpenCodeFormValue.typedNumber("1.234,5", de))
        assertEquals(OpenCodeFormValue.Number(1234.5), OpenCodeFormValue.typedNumber(NumberFormat.getInstance(fr).format(1234.5), fr))
        assertEquals(OpenCodeFormValue.Number(1234.5), OpenCodeFormValue.typedNumber(NumberFormat.getInstance(appFormattingLocale()).format(1234.5)))
        assertNull(OpenCodeFormValue.typedNumber("", us))
        assertNull(OpenCodeFormValue.typedNumber("abc", us))
    }
}
