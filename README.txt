Build the APK (Windows):
1. Install Android Studio (free): https://developer.android.com/studio
2. Unzip this folder, then in Android Studio: File > Open > pick the ImmortalPathAndroid folder. Let it sync (it downloads what it needs the first time).
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" when it finishes.
   The file is app/build/outputs/apk/debug/app-debug.apk. Copy it to your phone and open it
   (allow "install unknown apps" if asked).

To use a different game file, replace app/src/main/assets/game.html and rebuild.
Saves live inside the app, so they are separate from the browser version. Use the game's own export/import to move a save.
