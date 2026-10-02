<p align="center">
  <img src="icon.png" width="120" height="120" style="border-radius: 20px;" alt="App Icon" />
</p>

# Higurashi Ch.1: Onikakushi (Android Launcher)

A clean, one-click standalone Android launcher for *Higurashi When They Cry - Ch.1 Onikakushi*.

Built on top of **Bannerlator** (Winlator Star), stripped of all unnecessary desktop menus and container lists so it behaves like a native handheld visual novel port.

---

### Key Tweaks

* **1-Click Boot:** Skips the container dashboard and desktop view completely. Opening the app initializes the environment and launches `HigurashiEp01.exe` automatically.
* **No Accidental Drawer:** Neutralized the swipe-from-left sidebar during gameplay, so tapping or swiping to read text never brings up emulator menus.
* **Clean Loading Screen:** Swapped out the diagnostic status bars and sparkles for a minimal splash image with simple loading dots.
* **Tuned for Mali/ARM:** Pre-configured with DXVK 1.7.2-async, 720p windowed mode, mailbox presentation, and aggressive startup.
* **Auto-Exit:** Quitting the visual novel cleanly closes the entire app instead of leaving you on a blank X11 desktop.

---

### How to Use

1. Install the built APK.
2. Place your game folder containing `HigurashiEp01.exe` on your device's internal storage or SD card.
3. Open the app. The first launch runs the background setup automatically and boots directly into the game.

---

### Credits

* **Game:** 07th Expansion / Ryukishi07 & MangaGamer.
* **Base Runtime:** Bannerlator & Winlator contributors.
