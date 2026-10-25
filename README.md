# AppleTV Wake for Android TV

Make your Android / Google TV behave like a plain screen for your Apple TV.

- **Wake the TV with the Apple TV.** Wake the Apple TV with its remote and the TV turns on, on the right
  input, even on sets whose HDMI-CEC "power on" never works from standby (TCL, among others).
- **Switch to your second device with the Apple TV remote.** Double press mute and the TV toggles between
  the Apple TV and a second HDMI device (PS5, Xbox, Switch...). The sound ends up unchanged.
- **Never see the stock launcher again (optional).** Home always goes straight to the Apple TV.

Turning the Apple TV off still turns everything off, through regular HDMI-CEC.

It was built for a TCL Google TV whose remote broke: the Apple TV remote became the only remote in the
room. If your Apple TV turns your TV off but not on, this is for you.

## How it works

- **Wake.** The app keeps a connection open to the Apple TV over Apple's *Companion* protocol (the one
  the iPhone remote uses), using credentials from a one-time pairing. tvOS pushes a power-state event
  when the Apple TV wakes. On the tested TCL, the Wi-Fi chip wakes Android for that packet even in deep
  standby, and the app turns the screen on and switches to the Apple TV input. The protocol client is a small Java
  port of [pyatv](https://github.com/postlund/pyatv).
- **Double mute.** The Apple TV remote sends volume and mute to the TV (over HDMI-CEC or infrared), and
  nothing else.
  The app follows the system's mute broadcasts: two changes within 0.7 s switch the input.
- **Home.** An optional launcher that switches to the Apple TV input and finishes.

The app talks only to the Apple TV on your local network. No internet access, no analytics, no ads.
It runs as an accessibility service only because Android keeps those running and restarts them at boot;
it reads no screen content and filters no keys.

## Requirements

- An Android TV or Google TV on **Android 11 or later**.
- An **Apple TV** (tvOS 15 or later) on the same network.
- A computer with [adb](https://developer.android.com/tools/releases/platform-tools)
  (macOS: `brew install android-platform-tools`) and either [uv](https://docs.astral.sh/uv/) or
  Python 3.9 to 3.13, used once for the pairing.
- **USB debugging** enabled on the TV during setup: Settings > System > About > click *Build* 7 times,
  then Settings > System > Developer options > USB debugging. On many TVs (TCL included) this also
  opens network debugging on port 5555, which the setup script uses.
- Optional: HDMI-CEC enabled on both sides (TV: *HDMI control*; Apple TV: Settings > Remotes and Devices
  > Control TVs and Receivers) for the double mute and the CEC power off.

### Tested

| TV | Firmware | Apple TV | Second device |
|---|---|---|---|
| TCL "Smart TV Pro" (`G05_4K_GB`), Google TV 11 | `AR2101`, `V8-T615T03-LF1V503` | Apple TV 4K (2nd gen), tvOS 27 | PS5 |

Other brands should work as long as their Live TV app accepts the standard passthrough input intent.
Please open an issue or a PR with your model, working or not.

## Install

1. Get the code: `git clone https://github.com/Gauthier-Huguenin/appletv-wake-android-tv`.
2. Find the TV's IP address (Settings > Network) and run:

   ```sh
   scripts/setup.sh 192.168.1.50
   ```

   The script downloads the latest APK from the releases (or uses `dist/AppleTVWake.apk` if you built
   it), then:
   1. connects to the TV (accept the *Allow USB debugging* prompt on screen);
   2. lists the HDMI inputs with the names announced over HDMI-CEC, and asks which one is the Apple TV
      and which one is the second device;
   3. pairs with the Apple TV: a 4-digit code appears on the Apple TV screen. If the Apple TV refuses,
      set Settings > AirPlay and HomeKit > Allow Access to *Everyone on the Same Network* for the
      pairing; you can set it back afterwards, the pairing keeps working;
   4. lets the app run in the background (Doze exemption, accessibility service, and on TCL the
      auto-start permission);
   5. optionally replaces the stock launcher.
3. Test it: turn the Apple TV off with its remote, wait a few minutes, then wake it.
4. Turn USB debugging off once everything works. The app does not need it, and it closes port 5555.

To undo everything: `scripts/uninstall.sh <tv-ip>`.

## Troubleshooting

Read the app's log:

```sh
adb shell cat /sdcard/Android/data/io.github.gauthierhuguenin.atvwake/files/log.txt
```

- **"enabled but not running" at the end of the setup.** TCL's Safety Guard blocks apps at boot. Open
  Safety Guard > Permission Shield > Auto Launch Permission, allow *AppleTV Wake*, and run the setup again.
- **The TV does not wake.** Check that the log shows `connected to the Apple TV` and, at the wake-up,
  `Apple TV state 1 -> 3`. If the connection drops while the TV sleeps, Android is probably cutting the
  app's network: check `adb shell dumpsys deviceidle whitelist | grep atvwake`.
- **Double mute does nothing.** The Apple TV must control the TV volume over HDMI-CEC (Settings >
  Remotes and Devices > Volume Control). The log shows `double mute` when it is detected.
- **The double press goes to the wrong input.** The app remembers the last switch it made. If a device
  takes over on its own over CEC (a console powering on), the first double press may land on it again;
  press twice more.
- **The Apple TV was reset or replaced.** Run the setup again to pair it.

## Limitations

- The TV cannot be woken from the second device (a console controller, for instance): it does not tell
  anyone when it powers on. Start with the Apple TV.
- A tvOS update could change the Companion protocol. Siri / HomeKit ("turn on the TV"), if your TV
  supports it, is a good fallback to keep.
- The app is configured over adb only. There is no settings screen on the TV.

## Debloating (optional)

Removing the stock launcher and the apps you never use makes these TVs much snappier. See
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) for tested per-model lists and tools. With this
app, keep at least:

- the Live TV app and the TV input service (`com.tcl.tv` and `com.tcl.tvinput` on TCL): input switching
  goes through them;
- AirPlay / HomeKit (`com.tcl.airplay2`, `com.mediatek.AirplayAPK` on TCL) if you want Siri as a
  fallback to turn the TV on;
- `com.google.android.tv.remote.service` if you use a phone remote app.

On TCL sets, a system service re-enables a handful of TCL packages at every boot; that is harmless.

## Building from source

Needs JDK 17 and the Android SDK with `platforms;android-30` and build-tools. No Gradle.

```sh
ANDROID_HOME=~/Library/Android/sdk ./build.sh   # writes dist/AppleTVWake.apk, debug-signed
```

Release APKs are built and signed by GitHub Actions when a `v*` tag is pushed.

## Credits

- [pyatv](https://github.com/postlund/pyatv) by Pierre Ståhl: the Companion protocol client is a port of
  its code, and the setup uses it for pairing.
- [cleanyourtv](https://github.com/Gathin23/cleanyourtv): TCL firmware findings that made the setup
  reliable.
- [Bouncy Castle](https://www.bouncycastle.org/) for X25519, Ed25519 and ChaCha20-Poly1305.

See [NOTICE.md](NOTICE.md). Licensed under the [MIT License](LICENSE).

Not affiliated with Apple, TCL, Google or Sony. Apple TV is a trademark of Apple Inc.
