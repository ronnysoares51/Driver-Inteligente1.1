package br.com.nexo.driver.autoclick

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import br.com.nexo.driver.evaluation.OfferEvaluator
import br.com.nexo.driver.offer.NormalizedOffer
import br.com.nexo.driver.offer.OfferSource
import android.content.Context

/** Opt-in click per kilometre. No click is attempted when an offer is incomplete. */
class ClickPerKmController(private val service: AccessibilityService) {
    private val evaluator = OfferEvaluator()
    private var lastOfferKey: String? = null
    private var lastClickMs = 0L

    fun consider(offer: NormalizedOffer) {
        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        if (offer.source != OfferSource.NINETY_NINE) return
        val minimumCents = prefs.getLong(KEY_MIN_CENTS, 200L)
        if (minimumCents <= 0L) return
        val derived = evaluator.derive(offer)
        val rate = derived.ratePerKm
        if (!rate.isUsable(0.85f) || (rate.value ?: 0L) < minimumCents) return
        // Never act on estimates with unknown pickup or trip distance.
        if (!offer.payout.isUsable(0.85f) ||
            !offer.pickup.distance.isUsable(0.85f) ||
            !offer.trip.distance.isUsable(0.85f)) return
        val key = "${offer.payout.value?.cents}:${offer.pickup.distance.value?.meters}:${offer.trip.distance.value?.meters}:${offer.passenger.rating.value}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastOfferKey && now - lastClickMs < 30_000L) return
        if (now - lastClickMs < 1_500L) return
        val root = service.rootInActiveWindow ?: return
        try {
            // Only interact with an exact, visible button belonging to the 99 Driver app.
            val owner = root.packageName?.toString().orEmpty()
            if (owner != "com.app99.driver") return
            val candidates = root.findAccessibilityNodeInfosByText("Escolher")
            try {
                val exact = candidates.filter {
                    it.isVisibleToUser &&
                        (it.text?.toString()?.trim()?.equals("Escolher", true) == true ||
                            it.contentDescription?.toString()?.trim()?.equals("Escolher", true) == true)
                }
                if (exact.size != 1) return
                var target: AccessibilityNodeInfo? = exact.single()
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    lastOfferKey = key
                    lastClickMs = now
                }
            } finally {
                candidates.forEach { runCatching { it.recycle() } }
            }
        } finally {
            root.recycle()
        }
    }

    companion object {
        const val PREFS = "gigu_click_per_km"
        const val KEY_ENABLED = "enabled"
        const val KEY_MIN_CENTS = "minimum_rate_cents"
    }
}
