# Changelog

## 0.1.0-beta.1 — First pre-release

This preview provides a two-part phone-to-PC game controller.

### Android mobile app

- Tilt steering with center calibration, or drag steering.
- Hold-to-use gas and brake pedals, plus A/B/X/Y buttons.
- Pair by local IPv4 address and six-digit PIN, or discover the PC automatically over the same network.

### Windows PC companion

- Desktop pairing screen and live input status.
- Sends steering, throttle, brake, and buttons to a configured vJoy device.
- Releases input to neutral after disconnect or stale phone input.

### Known limits

- Requires a trusted private Wi-Fi network and one connected phone.
- Windows output requires vJoy to be installed and configured separately; only one program can own the selected device at a time.
- No Xbox/XInput output yet.
- Phone-to-PC control has not been live-tested with a connected Android device.
- This is a pre-release preview, not a production-ready release.
## Packaging

- The Android preview APK is debug-signed and intended for sideload testing, not Play Store distribution.
- The Windows executable is unsigned; Windows may show an unknown-publisher warning.
