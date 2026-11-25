# Changelog

## 0.1.0-beta.1 - First pre-release

This preview provides a two-part phone-to-PC game controller.

### Android mobile app

- Tilt steering with center calibration, or drag steering.
- Hold-to-use gas and brake pedals, plus A/B/X/Y buttons.
- Pair by local IPv4 address and six-digit PIN, or discover the PC automatically on the same network.

### Windows PC companion

- Desktop pairing screen and live input status.
- Steering, throttle, brake, and buttons map to a configured vJoy device.
- Input returns to neutral after a disconnect or stale phone data.
- PC presence is broadcast on the local network for automatic phone discovery.

### Known limits

- Use a trusted private Wi-Fi network. The session PIN and control stream are not encrypted on the LAN.
- Windows output requires vJoy to be installed and configured separately; only one program can own the selected device at a time.
- No Xbox/XInput output yet.
- Phone-to-PC control has not been live-tested with a connected Android device.
- This is a pre-release preview, not a production-ready release.

### Packaging

- The Android preview APK is debug-signed and intended for sideload testing, not Play Store distribution.
- The Windows executable is unsigned; Windows may show an unknown-publisher warning.