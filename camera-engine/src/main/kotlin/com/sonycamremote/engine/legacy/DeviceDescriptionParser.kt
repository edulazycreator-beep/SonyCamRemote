package com.sonycamremote.engine.legacy

import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class DeviceDescription(
    val friendlyName: String?,
    val modelName: String?,
    val manufacturer: String?,
    /** serviceType ("camera", "avContent", "system", "guide") → full JSON-RPC endpoint URL. */
    val serviceUrls: Map<String, String>,
)

/**
 * Parses the UPnP device description (dd.xml) served by legacy Sony cameras. The Sony-specific
 * <av:X_ScalarWebAPI_DeviceInfo> block lists each service's ActionList_URL; the JSON-RPC endpoint is
 * ActionList_URL + "/" + ServiceType.
 */
object DeviceDescriptionParser {

    fun parse(xml: String): DeviceDescription {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Harden against XXE: device descriptions never need DTDs or external entities.
            // Android's parser rejects unknown features, so this is best-effort.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        fun firstText(localName: String): String? {
            val nodes = doc.getElementsByTagNameNS("*", localName)
            return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
        }

        val services = linkedMapOf<String, String>()
        val serviceNodes = doc.getElementsByTagNameNS("*", "X_ScalarWebAPI_Service")
        for (i in 0 until serviceNodes.length) {
            val el = serviceNodes.item(i) as? Element ?: continue
            val type = el.getElementsByTagNameNS("*", "X_ScalarWebAPI_ServiceType").item(0)?.textContent?.trim()
            val url = el.getElementsByTagNameNS("*", "X_ScalarWebAPI_ActionList_URL").item(0)?.textContent?.trim()
            if (!type.isNullOrEmpty() && !url.isNullOrEmpty()) {
                services[type] = url.trimEnd('/') + "/" + type
            }
        }
        return DeviceDescription(
            friendlyName = firstText("friendlyName"),
            modelName = firstText("modelName"),
            manufacturer = firstText("manufacturer"),
            serviceUrls = services,
        )
    }

    /** Fallback used for manual-IP connections when dd.xml is unreachable (default camera AP address). */
    fun defaultServiceUrls(host: String, port: Int = 8080): Map<String, String> =
        listOf("camera", "avContent", "system", "guide").associateWith { "http://$host:$port/sony/$it" }
}
