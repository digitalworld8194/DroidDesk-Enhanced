package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniJsonTest {
    @Test fun roundTripsNestedValues() {
        val value = linkedMapOf(
            "s" to "ñandú \u0001 \"x\" \\ /",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "z" to null,
            "a" to listOf(1L, "two", listOf<Any?>(), mapOf<String, Any?>()),
        )
        assertEquals(value, MiniJson.parse(MiniJson.stringify(value)))
    }

    @Test fun parsesUnicodeEscapes() {
        assertEquals("é€", MiniJson.parse("\"\\u00e9\\u20ac\""))
    }

    @Test(expected = MiniJson.ParseException::class) fun rejectsTrailingData() {
        MiniJson.parse("{} {}")
    }

    @Test(expected = MiniJson.ParseException::class) fun rejectsDeepNesting() {
        MiniJson.parse("[".repeat(100) + "]".repeat(100))
    }

    @Test(expected = MiniJson.ParseException::class) fun rejectsRawControlCharacters() {
        MiniJson.parse("\"a\nb\"")
    }
}
