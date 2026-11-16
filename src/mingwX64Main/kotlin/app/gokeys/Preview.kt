package app.gokeys

import app.gokeys.input.Key
import app.gokeys.input.Shortcut
import app.gokeys.net.KeyInfo
import app.gokeys.net.PcInfo
import app.gokeys.net.Updates
import app.gokeys.ui.Flyout
import app.gokeys.ui.KeyWindow
import app.gokeys.ui.MainWindow
import kotlinx.coroutines.runBlocking

/**
 * Debug builds only: GOKEYS_PREVIEW=<folder> paints the windows in a few states to .bmp files.
 * No window opens, no key hook, no network.
 */
internal object Preview {
    fun run(dir: String) {
        // GOKEYS_UPDATES=<test server>: check, download and verify an update, and say what happened.
        if (Updates.enabled) return runBlocking {
            val result = runCatching { Updates.latest()?.let { "found ${it.version} at " + Updates.download(it) } ?: "no update" }
            Win.writeText("$dir/update.txt", result.getOrElse { "failed: ${it.message}" })
        }
        val ctrl = Key(0x1D, 0xA2, false)
        val shift = Key(0x2A, 0xA0, false)
        val f9 = Key(0x43, 0x78, false)
        val f10 = Key(0x44, 0x79, false)
        val quote = Key(0x28, 0xDE, false)
        val down = OwnKey("a", "Next song", Shortcut(listOf(ctrl, shift, f10)))
        val office = PcInfo("h", "OFFICE-PC", online = true, keys = listOf(
            KeyInfo("k1", "Volume down", "Umschalt+Ä"),
            KeyInfo("k2", "Volume up", "Umschalt+Ö"),
            KeyInfo("k3", "Mute", ""),
        ))
        App.config = Config(name = "DESK-PC", keys = listOf(down), hotkeys = listOf(Hotkey("h", "k1", Shortcut(listOf(ctrl, quote)))))

        App.others = emptyList()
        MainWindow.snapshot("$dir/1-main-alone.bmp")
        MainWindow.previewPairing("K7Q-4MX")
        MainWindow.snapshot("$dir/2-main-code.bmp")
        MainWindow.previewPairing(null)
        App.others = listOf(office)
        App.update = app.gokeys.net.Release("1.1.0", "", 0, "")
        MainWindow.snapshot("$dir/3-main-paired.bmp")
        val many = office.copy(keys = (1..7).map { KeyInfo("m$it", listOf("Volume down", "Volume up", "Mute", "Next song", "Previous", "Play / pause", "Mic mute")[it - 1], if (it % 3 == 0) "" else "Strg+Umschalt+F$it") })
        App.others = listOf(many)
        MainWindow.snapshot("$dir/8-main-660.bmp", widthDp = 660)
        MainWindow.snapshot("$dir/9-main-1000.bmp", widthDp = 1000)
        App.others = listOf(office)
        Flyout.snapshot("$dir/7-flyout.bmp")
        App.update = null

        KeyWindow.preview(KeyWindow.Target.Own(null), held = emptyList(), captured = Shortcut(listOf(ctrl, shift, f9)))
        KeyWindow.snapshot("$dir/4-key-new.bmp")
        KeyWindow.preview(KeyWindow.Target.Binding(office, office.keys[1]), held = listOf(ctrl), captured = null)
        KeyWindow.snapshot("$dir/5-hotkey-holding.bmp")
        KeyWindow.preview(KeyWindow.Target.Binding(office, office.keys[1]), held = emptyList(), captured = Shortcut(listOf(ctrl, quote)))
        KeyWindow.snapshot("$dir/6-hotkey-clash.bmp")
    }
}
