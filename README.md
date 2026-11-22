# Shutter Bridge – watch shutter for the STOCK phone camera

Replaces the broken Xiaomi "camera remote" link. Two small apps that talk over Google's
Wearable Data Layer (the same link your Wear OS watch already uses for notifications):

- **wear/**  – watch app: one big button. Tap = photo now, hold = 3 s timer.
- **phone/** – companion: receives the message, opens the *stock Xiaomi camera*, and taps its
  shutter button through an Accessibility Service. Photos are taken by the real camera app,
  so you keep full Xiaomi image processing.

> Status: written but NOT compiled or tested on a real device (no Android SDK where this was
> written). Expect to tune the shutter-finding step on your 14T Pro. The phone app has a
> built-in diagnose screen for exactly that.

## 1. Build (Android Studio, JDK 17)
1. Install Android Studio (current stable). Open this folder (`ShutterBridge`), let Gradle sync.
2. Build > Build Bundle(s)/APK(s) > Build APK(s)  (or run `./gradlew assembleDebug`).
3. APKs appear at:
   - `phone/build/outputs/apk/debug/phone-debug.apk`
   - `wear/build/outputs/apk/debug/wear-debug.apk`

Both modules use the same applicationId (`com.shutterbridge.app`) and are signed with the same
debug key from your machine. Do not change either, or messages will not be delivered.

## 2. Install
**Phone:** enable USB debugging, plug in, then `adb install -r phone/build/outputs/apk/debug/phone-debug.apk`
(or just copy the APK to the phone and open it).

**Watch (Wi-Fi debugging):**
1. Watch: Settings > System > About > Versions, tap *Build number* 7 times.
2. Settings > Developer options: turn on *ADB debugging* and *Debug over Wi-Fi*.
3. Tap *Pair new device* (note the pairing code, IP and port), then on your computer:
   `adb pair <ip>:<pairing-port>` and enter the code.
4. `adb connect <ip>:<port-shown-on-watch>`
5. `adb -s <ip>:<port> install -r wear/build/outputs/apk/debug/wear-debug.apk`

## 3. Phone setup (open "Shutter Bridge" on the phone)
1. **Enable the accessibility service.** If it is greyed out ("Restricted setting"): step 2 first –
   App info > ⋮ (top right) > *Allow restricted settings*, then come back to step 1.
2. **App info:** also turn on *Autostart* and *Display pop-ups while running in the background*
   (HyperOS blocks background camera launches without it).
3. **Battery:** set Shutter Bridge to *No restrictions* so HyperOS doesn't kill it.
4. The status box at the top should read `Accessibility service: RUNNING ✓` and
   `Watch link: <your watch> ✓`.
5. Press **Test: take a photo in 5 s**. The camera should open and take a picture.

## 4. Use
Open "Shutter" on the watch and tap. The watch shows `Photo taken ✓`, or what went wrong.
Tip: set the phone camera to Photo mode (video mode would start recording instead).
Phone screen must be able to turn on; with a lock screen the camera opens in its secure
(lock-screen) mode, which may or may not work on HyperOS.

## Troubleshooting
| Watch says | Meaning / fix |
|---|---|
| Phone not connected | Watch isn't linked through the Google "Wear OS" app on the phone. Re-check pairing in that app. |
| Send failed / Link error | Google Play services / Wear OS app issue; reopen the Wear OS app. |
| Phone: enable accessibility service | Step 3.1 not done, or HyperOS killed it – toggle it off/on. |
| Phone: camera did not open | Allow pop-ups in background (3.2), unlock the phone, or open the camera manually first. |
| Phone: shutter button not found / tap failed | Run **Diagnose camera screen**, tap **Copy log**, send me the log. |
| Photo taken ✓ but no photo | The tap landed in the wrong place: adjust the X/Y fallback % in the app, or send me the log. |

## Privacy
The accessibility service receives no screen events (its event handler is empty). It reads the
screen only while handling a watch request, and only looks for the camera's shutter button.
Nothing is stored or sent anywhere except the "ok / error" reply to your own watch.
