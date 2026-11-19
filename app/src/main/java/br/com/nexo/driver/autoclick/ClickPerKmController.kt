package br.com.nexo.driver.autoclick

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * User-enabled click for the "Solicitações > Escolha uma > Escolher" screen.
 * Operates exclusively on currently visible accessibility nodes, with no fixed coordinates.
 * Does not act if the R$/km metric is unavailable or ambiguous.
 */
class ClickPerKmController(private val service: AccessibilityService) {
    private var lastOfferKey: String? = null
    private var lastClickMs: Long = 0L
    private var lastReadMs: Long = 0L

    fun considerVisibleScreen() {
        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val minimumCents = prefs.getLong(KEY_MIN_CENTS, 200L)
        if (minimumCents <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadMs < 90L || now - lastClickMs < 1_200L) return
        lastReadMs = now

        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() == service.packageName) return
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val texts = mutableListOf<String>()
            gather(root, nodes, texts, 0)
            // Match the specific screen demonstrated, never "Mapa de Chamadas".
            val combined = texts.joinToString(" ")
            if (!combined.contains("Solicitações", ignoreCase = true) ||
                !combined.contains("Escolha uma", ignoreCase = true)) return
            val buttons = nodes.filter {
                it.isVisibleToUser &&
                    (it.text?.toString()?.trim()?.equals("Escolher", true) == true ||
                        it.contentDescription?.toString()?.trim()?.equals("Escolher", true) == true)
            }
            if (buttons.size != 1) return
            // Platform-displayed rate takes precedence; fallback calculates from total fare
            // and total distance only when both are unequivocally exposed on screen.
            val shownRates = RATE.findAll(combined).mapNotNull { parseCents(it.groupValues[1]) }.distinct().toList()
            if (shownRates.size != 1) return
            val rateCents = shownRates.single()
            if (rateCents < minimumCents) return
            val fare = MONEY.find(combined)?.groupValues?.get(1) ?: return
            val key = "${root.packageName}:$fare:$rateCents:${combined.take(240)}"
            if (lastOfferKey == key && now - lastClickMs < 30_000L) return
            var button: AccessibilityNodeInfo? = buttons.single()
            var depth = 0
            while (button != null && !button.isClickable && depth++ < 5) button = button.parent
            if (button?.isVisibleToUser == true &&
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastOfferKey = key
                lastClickMs = now
            }
        } finally {
            root.recycle()
        }
    }

    private fun gather(
        node: AccessibilityNodeInfo,
        nodes: MutableList<AccessibilityNodeInfo>,
        texts: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 14 || nodes.size >= 500) return
        nodes.add(node)
        if (node.isVisibleToUser) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            gather(child, nodes, texts, depth + 1)
        }
    }

    private fun parseCents(raw: String): Long? {
        val clean = raw.replace(".", "").replace(",", ".")
        val number = clean.toDoubleOrNull() ?: return null
        return if (number > 0.0 && number < 1000.0) (number * 100 + 0.5).toLong() else null
    }

    companion object {
        const val PREFS = "gigu_click_per_km"
        const val KEY_ENABLED = "enabled"
        const val KEY_MIN_CENTS = "minimum_rate_cents"
        private val RATE = Regex("""R\$\s*([\d.,]+)\s*/\s*km""", RegexOption.IGNORE_CASE)
        private val MONEY = Regex("""R\$\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
    }
}
