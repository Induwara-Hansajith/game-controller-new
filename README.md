# Pocket Controller

**Version:** 0.1.0-beta.4 (pre-release)

An original phone-to-PC game controller inspired by Gyropad's workflow. The Android app sends Gamepad or Racepad input to the Windows companion over a local network. The PC can expose a virtual Xbox 360 XInput controller or a vJoy DirectInput device.

## Apps

- **Android:** Gamepad mode (two sticks, triggers, D-pad and 16 controls), Racepad mode (steering, pedals, clutch, handbrake, gears and 16 controls), and local-network pairing.
- **Windows PC:** desktop and web cockpits, automatic discovery, live input display, and selectable Auto, Xbox 360 XInput, or vJoy Sim Wheel output.

## Build

- Android APK: open `android/` in Android Studio, or run `android/gradlew.bat -p android assembleDebug` from the repository root. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
- Windows companion: run `pc/build.bat` on Windows. It creates `pc/dist/PocketControllerPC.exe`.

## Connect

1. For Xbox-style game support, install ViGEmBus. For simulator output, install/configure vJoy device 1 with X/Y/Z, optional RX/RY and a POV hat, and up to 16 buttons. The app does not install drivers.
2. Close another program if it already owns vJoy device 1.
3. Run the PC app and note the LAN address and pairing PIN.
4. Open the Android app on the same trusted private Wi-Fi network. It can auto-detect the PC; you can also enter its address and PIN manually.
5. Tap **Center** with the phone held in its neutral position, then keep both apps open.

The receiver uses a local TCP connection and supports one phone at a time. It does not change Firewall settings. Auto selects XInput for Gamepad mode and vJoy for Racepad mode when available, with fallback to the other installed backend. See [CHANGELOG.md](CHANGELOG.md) for pre-release limits.
