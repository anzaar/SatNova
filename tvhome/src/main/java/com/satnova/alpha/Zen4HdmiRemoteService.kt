package com.satnova.alpha

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional user-enabled HDMI remote bridge.
 *
 * In Android TV the regular HOME app does not receive buttons when the HDMI
 * viewer is foreground. With user consent, an AccessibilityService can request
 * hardware key filtering, if supported by the TV firmware.
 *
 * This bridge only forwards a documented subset of G-MScreen 1040 commands.
 * HOME is passed to TV unless the user explicitly enables optional HOME override.
 * SOURCE / volume / power are always left to the television.
 * Explicit "armed" session, 2-hour maximum and HDMI-app whitelist prevent
 * remapping remote buttons across ordinary Android apps.
 */
class Zen4HdmiRemoteService : AccessibilityService() {
    private val prefs by lazy { getSharedPreferences("zen4_hdmi_bridge", Context.MODE_PRIVATE) }
    private var zen: Zen4Client? = null
    private var currentPackage = ""
    private val swallowed = mutableSetOf<Int>()
    private var lastHomeOverrideMs = 0L
    private var homeDownConsumed = false
    private val homeProbeHandler = Handler(Looper.getMainLooper())
    private var homeProbeGeneration = 0

    private val stockHomePackages = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.android.tv.launcher"
    )

    /**
     * After a reboot the OEM HOME screen can already be on top before
     * Accessibility starts listening. The first HOME press then produces no
     * window-transition event. For an explicitly opted-in user, inspect only
     * the active window's package name at service connection and for a few
     * seconds afterwards. Never read node text or store window content.
     *
     * If Android/OEM denies window retrieval, retain the event-based fallback.
     * These finite, debounced probes do not run when HOME override is off.
     */
    private fun scheduleHomeRestoreProbe() {
        if (!prefs.getBoolean("home_override_enabled", false)) return
        val generation = ++homeProbeGeneration
        listOf(600L, 1_600L, 3_500L, 7_000L).forEach { delay ->
            homeProbeHandler.postDelayed({
                if (generation != homeProbeGeneration ||
                    !prefs.getBoolean("home_override_enabled", false)) return@postDelayed
                val foregroundPackage = try {
                    rootInActiveWindow?.packageName?.toString().orEmpty()
                } catch (error: Exception) {
                    android.util.Log.w("SatNovaHomeOverride", "Active HOME probe blocked", error)
                    ""
                }
                android.util.Log.i("SatNovaHomeOverride",
                    "service-start HOME probe delay=" + delay +
                    " activePackage=" + foregroundPackage)
                if (foregroundPackage in stockHomePackages) {
                    maybeOverrideStockHome(
                        foregroundPackage, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                }
            }, delay)
        }
    }

    private fun openSatNovaFromSystem(trigger: String): Boolean {
        return try {
            startActivity(Intent(this, MinimalHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            prefs.edit().putString("last_home_override_result", "SatNova demandée: " + trigger)
                .putLong("last_home_override_at", System.currentTimeMillis()).apply()
            android.util.Log.i("SatNovaHomeOverride", "Requested SatNova from " + trigger)
            true
        } catch (error: Exception) {
            prefs.edit().putString("last_home_override_result",
                "Refus Android: " + error.javaClass.simpleName).apply()
            android.util.Log.w("SatNovaHomeOverride", "Android rejected HOME from " + trigger, error)
            false
        }
    }

    /**
     * Opt-in fallback for firmware where Google TV's privileged HOME handler
     * outranks third-party launchers even when ROLE_HOME is granted.
     * Never captures HOME key codes, disables stock packages, or intercepts
     * arbitrary apps: it reacts only when a known stock HOME UI becomes visible.
     */
    private fun maybeOverrideStockHome(packageShown: String, eventType: Int) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (!prefs.getBoolean("home_override_enabled", false)) return
        if (packageShown !in stockHomePackages) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeOverrideMs < 3_000L) return
        lastHomeOverrideMs = now
        android.util.Log.i("SatNovaHomeOverride",
            "Detected opt-in stock launcher: " + packageShown +
            " eventType=" + eventType)
        openSatNovaFromSystem("stock launcher: " + packageShown)
    }

    private fun approvedViewer(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName) return false
        val known = listOf(
            "com.android.tv",
            "com.google.android.tv",
            "com.droidlogic.tv",
            "com.mediatek.wwtv.tvcenter",
            "com.mediatek.tv",
            "com.tcl.tv"
        )
        if (pkg in known) return true
        return pkg == prefs.getString("confirmed_hdmi_package", "")
    }

    private fun sessionWithinLimit(): Boolean {
        val since = prefs.getLong("armed_since", 0L)
        return since > 0L && System.currentTimeMillis() - since in 0L..7_200_000L
    }

    private fun armValid(): Boolean {
        if (!prefs.getBoolean("armed", false)) return false
        if (!sessionWithinLimit()) {
            prefs.edit().putBoolean("armed", false).putBoolean("auto_arm", false)
                .putBoolean("hdmi_seen", false).apply()
            swallowed.clear()
            return false
        }
        return true
    }

    /**
     * User-opted-in HDMI auto-arm may resume an existing session after HOME,
     * Shahid or SOURCE. Only a known/confirmed HDMI viewer may trigger it;
     * it must NEVER reset the user's two-hour consent expiry.
     * Non-HDMI apps never receive redirected keys.
     */
    private fun maybeRearmOnHdmiReturn(viewerPackage: String) {
        if (!approvedViewer(viewerPackage) || !prefs.getBoolean("auto_arm", false)) return
        if (!sessionWithinLimit()) {
            prefs.edit().putBoolean("armed", false).putBoolean("auto_arm", false)
                .putBoolean("hdmi_seen", false).apply()
            android.util.Log.i("SatNovaHdmiBridge", "Auto-arm expired; user must re-enable")
            return
        }
        if (!prefs.getBoolean("armed", false)) {
            swallowed.clear()
            prefs.edit().putBoolean("armed", true)
                .putBoolean("hdmi_seen", true)
                .putString("last_forward_result", "Contrôle HDMI réactivé automatiquement")
                .apply()
            android.util.Log.i("SatNovaHdmiBridge",
                "Re-armed after HDMI viewer returned: " + viewerPackage)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        zen = Zen4SessionHub.attach(this, this)
        prefs.edit().putBoolean("service_active", true).apply()
        android.util.Log.i("SatNovaHomeOverride", "Accessibility service connected, home opt-in=" +
            prefs.getBoolean("home_override_enabled", false))
        scheduleHomeRestoreProbe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        // This preference is independent from the time-limited HDMI key bridge.
        // The original HOME/POWER/VOLUME keys remain owned by Android TV.
        maybeOverrideStockHome(pkg, event.eventType)
        // Track every window transition, including time spent in HOME/Shahid.
        // A vetted HDMI viewer can resume a still-valid, opted-in session.
        currentPackage = pkg
        maybeRearmOnHdmiReturn(pkg)
        if (!armValid()) return
        if (pkg == packageName) {
            // Do not disarm the initial button press in the launcher. Disarm
            // only after an HDMI viewer was already observed and HOME returned.
            if (prefs.getBoolean("hdmi_seen", false)) {
                prefs.edit().putBoolean("armed", false)
                    .putBoolean("hdmi_seen", false).apply()
                swallowed.clear()
            }
        } else if (pkg !in listOf("com.android.systemui", "com.android.settings",
                "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")) {
            if (approvedViewer(pkg)) {
                prefs.edit().putBoolean("hdmi_seen", true).apply()
            } else if (prefs.getBoolean("hdmi_seen", false)) {
                // After verified HDMI, never keep remapping keys in unrelated apps.
                prefs.edit().putBoolean("armed", false)
                    .putBoolean("hdmi_seen", false).apply()
                swallowed.clear()
            }
            // Record foreground package only during an explicitly armed test.
            prefs.edit().putString("observed_viewer_package", pkg).apply()
        }
    }

    /** Launch the installed Android Shahid app, not a Zen 4 receiver command. */
    private fun launchShahid(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("shahid://"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun receiverKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_PROG_RED -> 6
        // Vestel/Lifemaxx yellow TV key: Zen 4 receiver BACK/EXIT (1040 KeyValue 7).
        // Do not redirect in Android apps; approved HDMI viewer and arm gate still apply.
        KeyEvent.KEYCODE_PROG_YELLOW -> 7
        KeyEvent.KEYCODE_PROG_BLUE -> 54
        // TV PAGE +/- keys control receiver volume; physical TV VOL keys stay native.
        KeyEvent.KEYCODE_PAGE_UP -> 35
        KeyEvent.KEYCODE_PAGE_DOWN -> 36

        KeyEvent.KEYCODE_DPAD_UP -> 1
        KeyEvent.KEYCODE_DPAD_DOWN -> 2
        KeyEvent.KEYCODE_DPAD_LEFT -> 3
        KeyEvent.KEYCODE_DPAD_RIGHT -> 4
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 5
        KeyEvent.KEYCODE_MENU -> 6
        KeyEvent.KEYCODE_BACK -> 7
        KeyEvent.KEYCODE_CHANNEL_UP -> 69
        KeyEvent.KEYCODE_CHANNEL_DOWN -> 70
        KeyEvent.KEYCODE_GUIDE -> 32
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_1 + 13
        KeyEvent.KEYCODE_0 -> 12
        in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 ->
            code - KeyEvent.KEYCODE_NUMPAD_1 + 13
        KeyEvent.KEYCODE_NUMPAD_0 -> 12
        else -> null
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        // Optional HOME key fallback if the OEM routes HOME to Accessibility.
        // If Android does not deliver this key, window-change fallback handles it.
        // When disabled or blocked, HOME passes through untouched to the TV.
        if (event.keyCode == KeyEvent.KEYCODE_HOME &&
            prefs.getBoolean("home_override_enabled", false)) {
            if (event.action == KeyEvent.ACTION_UP) {
                val consumed = homeDownConsumed
                homeDownConsumed = false
                return consumed
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                homeDownConsumed = openSatNovaFromSystem("HOME key")
                return homeDownConsumed
            }
            return homeDownConsumed
        }
        // Some Vestel/MediaTek builds do not emit a fresh window event on
        // return to HDMI via SOURCE. Confirm the active window before restoring
        // key forwarding; never auto-arm from a stale cached package.
        val activePackage = try {
            rootInActiveWindow?.packageName?.toString().orEmpty()
        } catch (_: Exception) { "" }
        if (activePackage.isNotBlank()) {
            currentPackage = activePackage
            maybeRearmOnHdmiReturn(activePackage)
        }
        if (!armValid()) return false
        // SOURCE, volume and power always belong to the television.
        if (event.keyCode in listOf(
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_TV_INPUT,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_APP_SWITCH
            )) return false

        if (event.keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            if (!approvedViewer(currentPackage)) return false
            if (event.action == KeyEvent.ACTION_UP) return swallowed.remove(event.keyCode)
            if (event.action != KeyEvent.ACTION_DOWN) return false
            if (event.repeatCount > 0) return swallowed.contains(event.keyCode)
            val launched = launchShahid()
            prefs.edit().putString("last_forward_result",
                if (launched) "Shahid ouvert" else "Shahid indisponible").apply()
            if (launched) swallowed.add(event.keyCode)
            return launched
        }
        val value = receiverKey(event.keyCode) ?: return false
        val viewer = currentPackage
        val permitted = approvedViewer(viewer)
        if (event.action == KeyEvent.ACTION_UP) {
            return swallowed.remove(event.keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        prefs.edit()
            .putString("last_viewer", viewer)
            .putInt("last_tv_key", event.keyCode)
            .putInt("last_zen_key", value)
            .putBoolean("last_key_approved", permitted)
            .apply()
        if (!permitted) return false
        // Repeats on long presses are capped, to prevent unbounded remote queues.
        if (event.repeatCount > 0 && event.repeatCount % 3 != 0) {
            return swallowed.contains(event.keyCode)
        }
        val forwarded = zen?.sendRemoteKey(value) == true
        prefs.edit().putString("last_forward_result",
            if (forwarded) "Envoyée" else "Zen 4 non connecté").apply()
        if (forwarded) swallowed.add(event.keyCode)
        return forwarded
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        ++homeProbeGeneration
        homeProbeHandler.removeCallbacksAndMessages(null)
        Zen4SessionHub.detach(this)
        zen = null
        swallowed.clear()
        prefs.edit().putBoolean("service_active", false).putBoolean("armed", false).apply()
        super.onDestroy()
    }
}
