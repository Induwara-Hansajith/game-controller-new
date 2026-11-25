# Changelog

## 0.1.0-beta.2 - Full controller pre-release

### Android mobile app

- Added Gamepad and Racepad modes with two sticks, triggers, D-pad, sim controls, and a 16-button input packet.

### Windows PC companion

- Added selectable Auto, Xbox 360 XInput (ViGEmBus), and vJoy DirectInput output.
- Auto selects XInput for Gamepad mode and vJoy for Racepad mode when available, then falls back to the other available backend.
- Added sticks, triggers, clutch, handbrake, D-pad, and 16-button telemetry to the desktop and web cockpits.
- Expanded vJoy mapping to RX/RY, configured POV hats, and up to 16 buttons.
- Stale input and disconnect reset virtual controls; shutdown releases active devices.
- The standalone build includes the ViGEm client runtime but does not install ViGEmBus or vJoy.

### Known limits

- XInput exposes fewer independent button flags than the phone's 16-button protocol; extra paddle/gear controls are aliases. vJoy can expose 16 separate configured buttons.
- Phone-to-PC control still needs testing with a physical Android device on the target network.
- These are unsigned/debug-signed pre-release test builds.

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
