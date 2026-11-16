package app.gokeys.ui

/**
 * gokeys' colours (0xRRGGBB). Mint is its own accent in the govrse family (gocast blue, gowake violet, goscrape amber,
 * gotrippin coral); the neutrals are dark greys with a hint of it. Matches branding/make-logo.mjs.
 */
object Theme {
    const val SURFACE = 0x161A19 // the whole window, title bar included
    const val CARD = 0x1F2422 // tiles, and the stage the keys sit on
    const val CARD_HOVER = 0x262C2A
    const val FIELD = 0x1F2422
    const val LINE = 0x343C39 // outlines: secondary button, empty key
    const val TEXT = 0xECF1EE
    const val MUTED = 0x929D98
    const val ERROR = 0xFF99A4

    const val ACCENT = 0x5ED6A0
    const val ACCENT_HOVER = 0x76DEAF
    const val ACCENT_PRESSED = 0x4FC08D
    const val ACCENT_EDGE = 0x3A9A6E // the side of a mint key
    const val ACCENT_TINT = 0x223A30 // step tag
    const val ON_ACCENT = 0x0C1A13

    const val KEY = 0x39423F // a key that's held or not confirmed yet
    const val KEY_EDGE = 0x2A312E
    const val HOVER = 0x252B29
    const val PRESSED = 0x1E2321
    const val DISABLED = 0x242A28
    const val DISABLED_TEXT = 0x5E6864
}
