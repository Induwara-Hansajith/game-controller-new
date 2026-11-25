# Pocket Controller

**Version:** 0.1.0-beta.1 (pre-release)

An original phone-to-PC game controller inspired by Gyropad's workflow. The Android app reads tilt and touch input; the Windows companion receives it over a trusted local network and sends it to a configured vJoy device.

## Apps

- **Android:** tilt or touch steering, center calibration, held pedals, A/B/X/Y buttons, and pairing by PC address plus a six-digit PIN.
- **Windows PC:** desktop pairing UI, receiver status, and live input display. Output maps steering to X, throttle to Y, brake to Z, and buttons to vJoy device 1.

## Build

- Android APK: open `android/` in Android Studio, or run `android/gradlew.bat -p android assembleDebug` from the repository root. The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
- Windows companion: run `pc/build.bat` on Windows. It creates `pc/dist/PocketControllerPC.exe`.

## Connect

1. Install/configure vJoy on Windows with X, Y, and Z axes and up to eight buttons.
2. Close Gyropad PC or another program if it is already using vJoy device 1.
3. Run the PC app and note the LAN address and pairing PIN.
4. Open the Android app on the same trusted private Wi-Fi network. It can auto-detect the PC; you can also enter its address and PIN manually.
5. Tap **Center** with the phone held in its neutral position, then keep both apps open.

The receiver uses a local TCP connection. It supports one phone at a time and does not install drivers or change Firewall settings. Xbox/XInput output is not included yet. See [CHANGELOG.md](CHANGELOG.md) for pre-release limits.