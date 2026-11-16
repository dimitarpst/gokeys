@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys.ui

import kotlinx.cinterop.*
import platform.windows.*
import app.gokeys.Win
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An off-screen bitmap to paint a window in one go (no flicker). GDI's own RoundRect has jagged corners, so rounded
 * shapes are drawn here with anti-aliased edges; text still goes through GDI for ClearType.
 */
class Canvas(private val width: Int, private val height: Int) {
    val dc: HDC = CreateCompatibleDC(null)!!
    private val pixels: CPointer<UIntVar>
    private val bitmap: HBITMAP
    private val old: HGDIOBJ?

    init {
        memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().convert()
            bmi.bmiHeader.biWidth = width
            bmi.bmiHeader.biHeight = -height // top-down
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.convert()
            val bits = alloc<COpaquePointerVar>()
            bitmap = CreateDIBSection(null, bmi.ptr, DIB_RGB_COLORS.convert(), bits.ptr, null, 0u)!!
            pixels = bits.value!!.reinterpret()
        }
        old = SelectObject(dc, bitmap)
        SetBkMode(dc, TRANSPARENT)
    }

    fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in max(top, 0) until min(bottom, height)) for (x in max(left, 0) until min(right, width)) {
            pixels[y * width + x] = color.toUInt()
        }
    }

    /** A filled rounded rectangle with soft (anti-aliased) edges. */
    fun roundRect(left: Int, top: Int, right: Int, bottom: Int, radius: Int, color: Int) {
        val cx = (left + right) / 2.0
        val cy = (top + bottom) / 2.0
        val hx = (right - left) / 2.0 - radius
        val hy = (bottom - top) / 2.0 - radius
        for (y in max(top, 0) until min(bottom, height)) for (x in max(left, 0) until min(right, width)) {
            val qx = abs(x + 0.5 - cx) - hx
            val qy = abs(y + 0.5 - cy) - hy
            val distance = sqrt(max(qx, 0.0) * max(qx, 0.0) + max(qy, 0.0) * max(qy, 0.0)) + min(max(qx, qy), 0.0) - radius
            val coverage = (0.5 - distance).coerceIn(0.0, 1.0)
            if (coverage > 0) blend(y * width + x, color, coverage)
        }
    }

    fun text(text: String, font: HFONT?, color: Int, left: Int, top: Int, right: Int, bottom: Int, flags: Int) = memScoped {
        SelectObject(dc, font)
        SetTextColor(dc, Win.rgb(color))
        val r = alloc<RECT>().apply { this.left = left; this.top = top; this.right = right; this.bottom = bottom }
        DrawTextW(dc, text, -1, r.ptr, (flags or DT_SINGLELINE or DT_NOPREFIX).convert())
    }

    fun textWidth(text: String, font: HFONT?): Int = memScoped {
        SelectObject(dc, font)
        val size = alloc<SIZE>()
        GetTextExtentPoint32W(dc, text, text.length, size.ptr)
        size.cx
    }

    fun icon(icon: HICON?, left: Int, top: Int, size: Int) = DrawIconEx(dc, left, top, icon, size, size, 0u, null, DI_NORMAL.convert())

    fun copyTo(target: HDC?) = BitBlt(target, 0, 0, width, height, dc, 0, 0, SRCCOPY.convert())

    /** Writes the pixels as a .bmp (debug previews). */
    fun saveBmp(path: String) {
        val size = width * height * 4
        val bytes = ByteArray(54 + size)
        fun put(at: Int, value: Int, n: Int) = repeat(n) { bytes[at + it] = (value shr (8 * it)).toByte() }
        bytes[0] = 'B'.code.toByte(); bytes[1] = 'M'.code.toByte()
        put(2, bytes.size, 4); put(10, 54, 4); put(14, 40, 4); put(18, width, 4); put(22, -height, 4)
        put(26, 1, 2); put(28, 32, 2); put(34, size, 4)
        for (i in 0 until width * height) put(54 + i * 4, pixels[i].toInt() or (0xFF shl 24), 4)
        Win.writeBytes(path, bytes)
    }

    fun release() {
        SelectObject(dc, old)
        DeleteObject(bitmap)
        DeleteDC(dc)
    }

    private fun blend(i: Int, color: Int, alpha: Double) {
        val under = pixels[i].toInt()
        fun channel(shift: Int) = ((color shr shift and 0xFF) * alpha + (under shr shift and 0xFF) * (1 - alpha)).toInt() shl shift
        pixels[i] = (channel(16) or channel(8) or channel(0)).toUInt()
    }
}
