package com.satnova.alpha

/**
 * Phone-accessible preview of the same French launcher UI.
 * No HOME intent or HDMI switching on mobile; Zen 4 Wi-Fi commands are real.
 */
class MobilePreviewActivity : MinimalHomeActivity() {
    override val previewMode: Boolean = true
}
