package com.satnova.alpha

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvContract
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

    private val secretoOverlay by lazy { SecretoZenPanel(this) }
    private val labelCache = mutableMapOf<String, Boolean>()
    private val zenObserver = object : Zen4Client.Listener {
        override fun state(connected: Boolean, message: String) {
            secretoOverlay.setConnected(connected)
        }
        override fun catalogue(channels: List<ZenChannel>) {
            secretoOverlay.setChannels(channels)
        }
        override fun playing(channelId: String) { }
        override fun tuneConfirmed(channel: ZenChannel) { }
    }

    /** Detect the actual Secreto HOME package, not arbitrary app content. */
    private fun isSecretoHome(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName) return false
        val configured = prefs.getString("secreto_home_package", "").orEmpty()
        if (configured.isNotBlank()) return pkg == configured
        return labelCache.getOrPut(pkg) {
            if (pkg.contains("secreto", true)) true else try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info)
                    .toString().contains("Secreto TV", ignoreCase = true)
            } catch (_: Exception) { false }
        }
    }

    /** SatNova's connected-HDMI selection, with the same original remote-arming gates. */
    private fun openHdmiFromSecreto(): Boolean {
        return try {
            val tv = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return false
            val inputs = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }
            val connected = inputs.filter { input ->
                try { tv.getInputState(input.id) == TvInputManager.INPUT_STATE_CONNECTED }
                catch (_: Exception) { false }
            }
            val hdmi1 = connected.firstOrNull { input ->
                val label = input.loadLabel(this).toString().replace(Regex("\\s+"), "")
                label.equals("HDMI1", ignoreCase = true) ||
                    input.id.contains("HDMI1", ignoreCase = true) ||
                    input.id.contains("HDMI_1", ignoreCase = true)
            }
            val target = if (connected.size == 1) connected.first()
                else hdmi1 ?: return false
            val intent = Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(target.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val viewer = intent.resolveActivity(packageManager) ?: return false
            startActivity(intent)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("satnova_selected_hdmi_input_id", target.id)
                .putString("satnova_expected_hdmi_viewer", viewer.packageName)
                .putLong("satnova_hdmi_requested_at", now)
                .putBoolean("armed", true)
                .putBoolean("hdmi_seen", false)
                .putLong("armed_since", now)
                .putString("last_forward_result", "Attente affichage HDMI")
                .apply()
            true
        } catch (error: Exception) {
            android.util.Log.w("AltaPlayZen", "HDMI request unavailable", error)
            false
        }
    }

    private var lastHomeOverrideMs = 0L
    private var homeDownConsumed = false
    private val homeProbeHandler = Handler(Looper.getMainLooper())
    private var homeProbeGeneration = 0
    private var bootProbeGeneration = 0

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

    /**
     * Boot-only fallback for OEMs that reopen the previous HDMI input.
     * The marker is set by BOOT_COMPLETED only after user opt-in. It is consumed
     * before launching to prevent repeated HDMI hijacking. Switching to HDMI
     * manually later never triggers this code.
     */
    private fun maybeRestoreBootHome(pkg: String) {
        if (packageName == "com.altaplay.launcher") return // Secreto owns HOME

        val deadline = prefs.getLong("boot_home_pending_until_elapsed", 0L)
        if (deadline <= 0L) return
        if (!prefs.getBoolean("boot_home_enabled", false) ||
            SystemClock.elapsedRealtime() !in 0L..deadline) {
            prefs.edit().remove("boot_home_pending_until_elapsed").apply()
            return
        }
        if (pkg == packageName) {
            prefs.edit().remove("boot_home_pending_until_elapsed")
                .putString("last_boot_home_result", "SatNova affichée après reboot").apply()
            return
        }
        // Do not react to arbitrary apps or temporary system dialogs.
        if (pkg !in stockHomePackages && !approvedViewer(pkg)) return
        prefs.edit().remove("boot_home_pending_until_elapsed").apply()
        val launched = openSatNovaFromSystem("startup HDMI/home: " + pkg)
        prefs.edit().putString("last_boot_home_result",
            if (launched) "Relance SatNova demandée depuis : " + pkg
            else "Relance refusée depuis : " + pkg).apply()
        android.util.Log.i("SatNovaBoot",
            "One-shot boot fallback package=" + pkg + " requested=" + launched)
    }

    private fun scheduleBootHomeProbes() {
        if (!prefs.getBoolean("boot_home_enabled", false)) return
        val generation = ++bootProbeGeneration
        listOf(1_000L, 2_800L, 5_500L, 9_000L, 14_000L).forEach { delay ->
            homeProbeHandler.postDelayed({
                if (generation != bootProbeGeneration) return@postDelayed
                val pkg = try {
                    rootInActiveWindow?.packageName?.toString().orEmpty()
                } catch (_: Exception) { "" }
                if (pkg.isNotBlank()) maybeRestoreBootHome(pkg)
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
        if (packageName == "com.altaplay.launcher") return // Never hijack Secreto

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

    /**
     * The TV does not expose video pixels/active HDMI port to an ordinary app.
     * Require three separate signals: SatNova explicitly selected an input,
     * the platform reports that very input as physically connected, and the
     * approved viewer is now foreground. Unknown means NO key forwarding.
     */
    private fun selectedHdmiStillConnected(): Boolean {
        val inputId = prefs.getString("satnova_selected_hdmi_input_id", "").orEmpty()
        if (inputId.isBlank()) return false
        return try {
            val manager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return false
            val info = manager.tvInputList.firstOrNull { it.id == inputId }
                ?: return false
            info.type == TvInputInfo.TYPE_HDMI &&
                manager.getInputState(inputId) == TvInputManager.INPUT_STATE_CONNECTED
        } catch (_: Exception) { false }
    }

    private fun authorizedHdmiForeground(pkg: String): Boolean {
        if (!selectedHdmiStillConnected()) return false
        val expected = prefs.getString("satnova_expected_hdmi_viewer", "").orEmpty()
        if (pkg == expected && expected.isNotBlank()) return true
        return approvedViewer(pkg)
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
        if (!authorizedHdmiForeground(viewerPackage) ||
            !prefs.getBoolean("auto_arm", false)) return
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
        zen = Zen4SessionHub.attach(this, this, zenObserver)
        prefs.edit().putBoolean("service_active", true).apply()
        android.util.Log.i("SatNovaHomeOverride", "Accessibility service connected, home opt-in=" +
            prefs.getBoolean("home_override_enabled", false))
        scheduleHomeRestoreProbe()
        scheduleBootHomeProbes()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        // This preference is independent from the time-limited HDMI key bridge.
        // The original HOME/POWER/VOLUME keys remain owned by Android TV.
        maybeOverrideStockHome(pkg, event.eventType)
        maybeRestoreBootHome(pkg)
        // Track every window transition, including time spent in HOME/Shahid.
        // A vetted HDMI viewer can resume a still-valid, opted-in session.
        currentPackage = pkg
        // Secreto stays HOME. Only the supplemental channel strip is ours.
        val focused = try { rootInActiveWindow?.packageName?.toString().orEmpty() }
            catch (_: Exception) { "" }
        secretoOverlay.setSecretoForeground(isSecretoHome(focused.ifBlank { pkg }))
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
            if (authorizedHdmiForeground(pkg)) {
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
            packageName != "com.altaplay.launcher" &&
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
        // Only intercept GUIDE/selection while Secreto itself is visible.
        // The known-working HDMI key translation below is unchanged.
        if (secretoOverlay.isSecretoForeground() &&
            secretoOverlay.handleKey(event, zen, ::openHdmiFromSecreto)) return true
        if (!armValid()) return false
        // BACK must remain the TV's own navigation key. EXIT for Zen 4 is
        // exclusively mapped to the yellow remote button on approved HDMI.
        // SOURCE, volume and power also always belong to the television.
        if (event.keyCode in listOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_TV_INPUT,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_APP_SWITCH
            )) return false

        if (event.keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            if (!authorizedHdmiForeground(currentPackage)) return false
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
        val permitted = authorizedHdmiForeground(viewer)
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
        ++bootProbeGeneration
        homeProbeHandler.removeCallbacksAndMessages(null)
        secretoOverlay.close()
        Zen4SessionHub.detach(this)
        zen = null
        swallowed.clear()
        prefs.edit().putBoolean("service_active", false).putBoolean("armed", false).apply()
        super.onDestroy()
    }
}
