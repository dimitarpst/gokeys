@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import app.gokeys.Win
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.*

/** A clickable area from the last paint. */
class Hit(
    val id: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val onRight: (() -> Unit)?,
    val onClick: () -> Unit,
) {
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** How a keycap looks: grey while held or not usable, mint when ready, an outline for the key still to come. */
enum class Look { HELD, READY, EMPTY }

/** Big keycaps for the recorder, small ones in lists. Sizes in dp. */
enum class Cap(val face: Int, val edge: Int, val radius: Int, val inset: Int, val gap: Int) {
    BIG(56, 5, 14, 20, 10),
    MEDIUM(44, 4, 12, 14, 8),
    SMALL(28, 3, 8, 10, 6),
}

/**
 * Drawing in go* style onto a [Canvas], recording what's clickable. Coordinates are pixels; [px] converts dp.
 * One per paint.
 */
class Ui(val c: Canvas, val width: Int, private val hovered: String?, private val pressed: String?) {
    val hits = mutableListOf<Hit>()

    fun px(dp: Int) = Win.px(dp)

    fun isHovered(id: String) = hovered == id && (pressed == null || pressed == id)

    fun isPressed(id: String) = pressed == id && hovered == id

    fun hit(id: String, left: Int, top: Int, right: Int, bottom: Int, onRight: (() -> Unit)? = null, onClick: () -> Unit) {
        hits += Hit(id, left, top, right, bottom, onRight, onClick)
    }

    fun text(text: String, font: HFONT?, color: Int, left: Int, top: Int, right: Int, bottom: Int, flags: Int = DT_LEFT or DT_VCENTER) =
        c.text(text, font, color, left, top, right, bottom, flags or DT_END_ELLIPSIS)

    /** Mint (primary) or outlined pill button, right-aligned at [right]; returns its left edge. */
    fun pill(
        id: String, label: String, right: Int, top: Int, primary: Boolean, enabled: Boolean = true,
        height: Int = px(40), on: Int = Theme.SURFACE, round: Boolean = false, onClick: () -> Unit,
    ): Int {
        val w = if (round) height else maxOf(c.textWidth(label, Fonts.label) + px(if (height < px(40)) 32 else 48), px(if (height < px(40)) 64 else 96))
        val left = right - w
        val bottom = top + height
        val r = height / 2
        if (primary) {
            c.roundRect(left, top, right, bottom, r, when {
                !enabled -> Theme.DISABLED
                isPressed(id) -> Theme.ACCENT_PRESSED
                isHovered(id) -> Theme.ACCENT_HOVER
                else -> Theme.ACCENT
            })
        } else {
            c.roundRect(left, top, right, bottom, r, Theme.LINE)
            c.roundRect(left + px(1), top + px(1), right - px(1), bottom - px(1), r - px(1), when {
                isPressed(id) -> Theme.PRESSED
                isHovered(id) -> Theme.HOVER
                else -> on
            })
        }
        val color = when {
            !enabled -> Theme.DISABLED_TEXT
            primary -> Theme.ON_ACCENT
            else -> Theme.TEXT
        }
        c.text(label, Fonts.label, color, left, top, right, bottom, DT_CENTER or DT_VCENTER)
        if (enabled) hit(id, left, top, right, bottom, onClick = onClick)
        return left
    }

    /** Width of [names] as keycaps of size [cap]. */
    fun capsWidth(names: List<String>, cap: Cap): Int {
        val font = when (cap) { Cap.BIG -> Fonts.bigKey; Cap.MEDIUM -> Fonts.mediumKey; Cap.SMALL -> Fonts.smallKey }
        return names.sumOf { maxOf(c.textWidth(it, font) + 2 * px(cap.inset), px(cap.face)) } + px(cap.gap) * (names.size - 1)
    }

    /** Keycaps starting at [left]: a face with a darker edge under it, like a real key. "" draws an empty key. */
    fun caps(names: List<String>, left: Int, top: Int, cap: Cap, look: Look) {
        val font = when (cap) { Cap.BIG -> Fonts.bigKey; Cap.MEDIUM -> Fonts.mediumKey; Cap.SMALL -> Fonts.smallKey }
        val face = px(cap.face)
        val edge = px(cap.edge)
        val radius = px(cap.radius)
        var x = left
        for (name in names) {
            val right = x + maxOf(c.textWidth(name, font) + 2 * px(cap.inset), face)
            if (name.isEmpty() || look == Look.EMPTY) {
                c.roundRect(x, top, right, top + face, radius, Theme.LINE)
                c.roundRect(x + px(2), top + px(2), right - px(2), top + face - px(2), radius - px(2), Theme.CARD)
            } else {
                val ready = look == Look.READY
                c.roundRect(x, top + edge, right, top + face + edge, radius, if (ready) Theme.ACCENT_EDGE else Theme.KEY_EDGE)
                c.roundRect(x, top, right, top + face, radius, if (ready) Theme.ACCENT else Theme.KEY)
                c.text(name, font, if (ready) Theme.ON_ACCENT else Theme.TEXT, x, top, right, top + face, DT_CENTER or DT_VCENTER or DT_SINGLELINE)
            }
            x = right + px(cap.gap)
        }
    }

    /** A small rounded tag, right-aligned at [right], e.g. "Online". */
    fun tag(text: String, right: Int, top: Int, fill: Int, color: Int): Int {
        val w = c.textWidth(text, Fonts.tag) + px(20)
        c.roundRect(right - w, top, right, top + px(24), px(12), fill)
        c.text(text, Fonts.tag, color, right - w, top, right, top + px(24), DT_CENTER or DT_VCENTER)
        return right - w
    }
}
