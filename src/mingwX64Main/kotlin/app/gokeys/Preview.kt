package app.gokeys

import app.gokeys.input.Key
import app.gokeys.input.Shortcut
import app.gokeys.net.KeyInfo
import app.gokeys.net.PcInfo
import app.gokeys.ui.Flyout
import app.gokeys.ui.KeyWindow
import app.gokeys.ui.MainWindow

/**
 * Debug builds only: GOKEYS_PREVIEW=<folder> paints the windows in a few states to .bmp files.
 * No window opens, no key hook, no network.
 */
internal object Preview {
    fun run(dir: String) {
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
        MainWindow.snapshot("$dir/3-main-paired.bmp")
        Flyout.snapshot("$dir/7-flyout.bmp")

        KeyWindow.preview(KeyWindow.Target.Own(null), held = emptyList(), captured = Shortcut(listOf(ctrl, shift, f9)))
        KeyWindow.snapshot("$dir/4-key-new.bmp")
        KeyWindow.preview(KeyWindow.Target.Binding(office, office.keys[1]), held = listOf(ctrl), captured = null)
        KeyWindow.snapshot("$dir/5-hotkey-holding.bmp")
        KeyWindow.preview(KeyWindow.Target.Binding(office, office.keys[1]), held = emptyList(), captured = Shortcut(listOf(ctrl, quote)))
        KeyWindow.snapshot("$dir/6-hotkey-clash.bmp")
    }
}
