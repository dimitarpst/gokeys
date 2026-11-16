@file:OptIn(ExperimentalForeignApi::class)

package app.gokeys

import kotlinx.cinterop.*
import platform.windows.*

/** The few Win32 basics the rest uses: environment, files (Unicode paths), the registry, colours, DPI. */
internal object Win {
    /** HKEY_CURRENT_USER is a macro cinterop can't see: ((HKEY)(ULONG_PTR)((LONG)0x80000001)). */
    val HKCU: HKEY = 0xFFFFFFFF80000001uL.toLong().toCPointer()!!

    fun env(name: String): String = memScoped {
        val buf = allocArray<WCHARVar>(1024)
        if (GetEnvironmentVariableW(name, buf, 1024u) == 0u) "" else buf.toKString()
    }

    fun exePath(): String = memScoped {
        val buf = allocArray<WCHARVar>(1024)
        GetModuleFileNameW(null, buf, 1024u)
        buf.toKString()
    }

    fun readText(path: String): String? {
        val h = CreateFileW(path, GENERIC_READ.convert(), FILE_SHARE_READ.convert(), null, OPEN_EXISTING.convert(), 0u, null)
        if (h == null || h.toLong() == -1L) return null
        try {
            val size = GetFileSize(h, null).toInt()
            val bytes = ByteArray(size)
            if (size > 0) memScoped {
                val read = alloc<UIntVar>()
                bytes.usePinned { ReadFile(h, it.addressOf(0), size.convert(), read.ptr, null) }
            }
            return bytes.decodeToString()
        } finally {
            CloseHandle(h)
        }
    }

    fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    fun writeBytes(path: String, bytes: ByteArray) {
        val h = CreateFileW(path, GENERIC_WRITE.convert(), 0u, null, CREATE_ALWAYS.convert(), FILE_ATTRIBUTE_NORMAL.convert(), null)
        if (h == null || h.toLong() == -1L) return
        memScoped {
            val written = alloc<UIntVar>()
            if (bytes.isNotEmpty()) bytes.usePinned { WriteFile(h, it.addressOf(0), bytes.size.convert(), written.ptr, null) }
        }
        CloseHandle(h)
    }

    fun mkdir(path: String) {
        CreateDirectoryW(path, null)
    }

    fun setRegistryString(key: String, name: String, value: String) {
        memScoped {
            RegSetKeyValueW(HKCU, key, name, REG_SZ.convert(), value.wcstr.ptr, ((value.length + 1) * 2).convert())
        }
    }

    fun deleteRegistryValue(key: String, name: String) {
        RegDeleteKeyValueW(HKCU, key, name)
    }

    fun hasRegistryValue(key: String, name: String): Boolean =
        RegGetValueW(HKCU, key, name, RRF_RT_ANY.convert(), null, null, null) == ERROR_SUCCESS

    /** COLORREF from 0xRRGGBB. */
    fun rgb(hex: Int): COLORREF = (((hex and 0xFF) shl 16) or (hex and 0xFF00) or ((hex shr 16) and 0xFF)).convert()

    /** Pixels for [dp] at the system DPI (the process is DPI aware, so nothing is blurry-scaled). */
    fun px(dp: Int): Int = dp * dpi / 96
    private val dpi by lazy {
        val dc = GetDC(null)
        GetDeviceCaps(dc, LOGPIXELSX).also { ReleaseDC(null, dc) }
    }

    /** Calls an optional system function by name (dwmapi, uxtheme): used only for the dark look, so failure is fine. */
    fun <T : Function<*>> optional(dll: String, name: String): CPointer<CFunction<T>>? =
        GetProcAddress(LoadLibraryW(dll), name)?.reinterpret()

    /** Same, for uxtheme's unnamed exports (cinterop's GetProcAddress only takes names, so call it by pointer). */
    private fun <T : Function<*>> optional(dll: String, ordinal: Int): CPointer<CFunction<T>>? =
        optional<(HMODULE?, CPointer<ByteVar>?) -> COpaquePointer?>("kernel32.dll", "GetProcAddress")
            ?.invoke(LoadLibraryW(dll), ordinal.toLong().toCPointer())?.reinterpret()

    /** Dark tray menus when Windows is in dark mode (uxtheme's SetPreferredAppMode(AllowDark), as Explorer does). */
    fun allowDarkMenus() {
        optional<(Int) -> Int>("uxtheme.dll", 135)?.invoke(1)
        optional<() -> Unit>("uxtheme.dll", 136)?.invoke() // FlushMenuThemes
    }

    /** A borderless popup with Windows 11's rounded corners and a [border] in our colour. */
    fun roundCorners(hwnd: HWND?, border: Int) = memScoped {
        val set = optional<(HWND?, UInt, COpaquePointer?, UInt) -> Int>("dwmapi.dll", "DwmSetWindowAttribute") ?: return@memScoped
        val value = alloc<UIntVar>()
        value.value = 2u // DWMWCP_ROUND
        set(hwnd, 33u, value.ptr, 4u) // DWMWA_WINDOW_CORNER_PREFERENCE
        value.value = rgb(border)
        set(hwnd, 34u, value.ptr, 4u) // DWMWA_BORDER_COLOR
    }

    /** Dark title bar in [caption] colour, so it runs straight into the window body (Windows 11; dark on 10). */
    fun darkTitleBar(hwnd: HWND?, caption: Int) = memScoped {
        val set = optional<(HWND?, UInt, COpaquePointer?, UInt) -> Int>("dwmapi.dll", "DwmSetWindowAttribute") ?: return@memScoped
        val value = alloc<UIntVar>()
        value.value = 1u
        set(hwnd, 20u, value.ptr, 4u) // DWMWA_USE_IMMERSIVE_DARK_MODE
        value.value = rgb(caption)
        set(hwnd, 35u, value.ptr, 4u) // DWMWA_CAPTION_COLOR
        set(hwnd, 34u, value.ptr, 4u) // DWMWA_BORDER_COLOR
    }
}
