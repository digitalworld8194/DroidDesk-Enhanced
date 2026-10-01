package com.orailnoor.droiddesk.runtime.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The Termux bridge is a loopback socket: it must not add exported Android components. */
class ManifestExportTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun components(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        return listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }

    private fun Element.name() = getAttributeNS(androidNs, "name")

    private fun Element.isExported(): Boolean {
        val explicit = getAttributeNS(androidNs, "exported")
        if (explicit.isNotEmpty()) return explicit == "true"
        // Before API 31 a component with an intent filter is exported by default.
        return getElementsByTagName("intent-filter").length > 0
    }

    @Test fun onlyTheLauncherActivityIsExported() {
        val exported = components().filter { it.isExported() }.map { it.name() }
        assertEquals(listOf(".MainActivity"), exported)
    }

    @Test fun servicesStayPrivate() {
        components().filter { it.tagName == "service" }.forEach { service ->
            assertEquals(service.name(), "false", service.getAttributeNS(androidNs, "exported"))
        }
    }

    @Test fun noReceiversOrProvidersWereAdded() {
        assertTrue(components().none { it.tagName == "receiver" || it.tagName == "provider" })
    }

    @Test fun mainActivityKeepsLauncherAndHomeFilters() {
        val main = components().first { it.name() == ".MainActivity" }
        val categories = main.getElementsByTagName("category")
        val names = (0 until categories.length).map { (categories.item(it) as Element).getAttributeNS(androidNs, "name") }
        assertTrue(names.contains("android.intent.category.LAUNCHER"))
        assertTrue(names.contains("android.intent.category.HOME"))
        assertTrue(names.contains("android.intent.category.DEFAULT"))
    }
}
