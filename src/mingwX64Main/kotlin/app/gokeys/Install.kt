@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.gokeys

import app.gokeys.ui.Tray
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.experimental.ExperimentalNativeApi

/**
 * gokeys.exe runs from wherever it landed (Downloads): it copies itself to `%LOCALAPPDATA%\gokeys` and runs from
 * there. Starting it again just opens the running one's window; starting a different (newer) exe replaces the
 * installed one. Windows starts it with `--tray`, which skips the window.
 */
object Install {
    private const val RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val dir = Win.env("LOCALAPPDATA") + "\\gokeys"
    private val exe = "$dir\\gokeys.exe"
    private val runName = "gokeys" + Config.profile.let { if (it.isEmpty()) "" else "-$it" }

    val isInstalled get() = Win.exePath().equals(exe, ignoreCase = true)

    /** "Start with Windows": an HKCU Run entry for this exe. */
    var autostart: Boolean
        get() = Win.hasRegistryValue(RUN, runName)
        set(on) = if (on) Win.setRegistryString(RUN, runName, "\"${Win.exePath()}\" --tray") else Win.deleteRegistryValue(RUN, runName)

    /** True if this process should exit: another gokeys handles it (opens its window, or takes over as the update). */
    fun handOff(openWindow: Boolean): Boolean {
        val running = FindWindowW(Tray.CLASS, null)
        if (Platform.isDebugBinary || isInstalled) {
            if (running != null && openWindow) PostMessageW(running, Tray.WM_OPEN.convert(), 0u, 0)
            return running != null
        }
        if (!sameFile(Win.exePath(), exe)) {
            running?.let { PostMessageW(it, WM_CLOSE.convert(), 0u, 0) }
            Win.mkdir(dir)
            // The old copy needs a moment to exit and release its file.
            val copied = (1..30).any { CopyFileW(Win.exePath(), exe, FALSE) != 0 || run { Sleep(200u); false } }
            if (!copied) {
                MessageBoxW(null, "gokeys couldn't install itself to $dir.", "gokeys", (MB_OK or MB_ICONERROR).convert())
                return true
            }
        }
        launch(exe, if (openWindow) "" else "--tray")
        return true
    }

    /** Runs a downloaded update; it asks this copy to quit and takes its place (see [handOff]). */
    fun runUpdate(path: String, openWindow: Boolean) = launch(path, if (openWindow) "" else "--tray")

    /** Leftovers from the last update, once the installed copy is the one running. */
    fun cleanUp() {
        if (isInstalled) DeleteFileW("$dir\\update\\gokeys.exe")
    }

    private fun launch(exe: String, args: String) = memScoped {
        val startup = alloc<STARTUPINFOW>().apply { cb = sizeOf<STARTUPINFOW>().convert() }
        val process = alloc<PROCESS_INFORMATION>()
        val commandLine = "\"$exe\" $args".wcstr.ptr // CreateProcessW may write to it
        if (CreateProcessW(exe, commandLine, null, null, FALSE, 0u, null, null, startup.ptr, process.ptr) != 0) {
            CloseHandle(process.hProcess)
            CloseHandle(process.hThread)
        }
    }

    /** Same size and modification time: CopyFile keeps both, so this is "the installed copy of this exe". */
    private fun sameFile(a: String, b: String): Boolean = memScoped {
        val x = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
        val y = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
        if (GetFileAttributesExW(a, _GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, x.ptr) == 0 || GetFileAttributesExW(b, _GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, y.ptr) == 0) return false
        x.nFileSizeLow == y.nFileSizeLow && x.nFileSizeHigh == y.nFileSizeHigh &&
            x.ftLastWriteTime.dwLowDateTime == y.ftLastWriteTime.dwLowDateTime && x.ftLastWriteTime.dwHighDateTime == y.ftLastWriteTime.dwHighDateTime
    }
}
