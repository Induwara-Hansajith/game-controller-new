# Changelog

## 0.1.0-beta.5 - Stable auto-reconnect, smoother UDP streaming & customizable button layout

### Android mobile app

- **Dropped-connection resilience:** added low-latency `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`), TCP `SO_KEEPALIVE`, active socket reader & ping/pong heartbeat, silent exponential-backoff auto-reconnect (`RECONNECTING…` HUD state), and moved socket disconnect from `onPause()` to `onStop()` so system overlays/notifications do not break active sessions.
- **Smoother low-latency response:** added authenticated UDP fast-path input streaming (`125Hz` active / `25Hz` idle, `4ms` immediate wake) with automatic TCP fallback, zero-allocation JSON serialization, tuned One-Euro steering filter (`9Hz` base cutoff, `0.0015` step resolution), smooth touch-to-gyro wheel blend synced to the PC, circular analog thumbstick clamping, and dedicated `L3`/`R3` buttons (eliminating accidental stick clicks on touch).
- **Customizable buttons (rearrange & resize):** added full **Edit Layout Mode** (`✎ EDIT` in the top bar or via Setup) for both Racepad and Gamepad modes — drag any button, pedal, stick, D-pad, or steering wheel to rearrange it, resize with the corner handle, 2-finger pinch, or `−`/`+` bar (`55%–200%`), toggle snap-to-grid with center guides, reset single items or full layouts, and persist layouts automatically.

### Windows PC companion

- Added authenticated UDP fast-path receiver (`port 8766`) alongside TCP pairing/keepalive (`SO_KEEPALIVE`, `SIO_KEEPALIVE_VALS`), sequence ordering to drop stale out-of-order datagrams, ping/pong dead-peer cleanup, explicit `bye` teardown, and a 3.5s grace window on transient drops so virtual controllers do not flicker off during auto-reconnect.
- Cached redundant `vJoy` and `Xbox 360 XInput` report writes to reduce driver overhead at 125Hz, and bumped desktop & web cockpit HUD polling to 60Hz (`16ms` when connected).

## 0.1.0-beta.4 - Smoother response & non-overlapping key bindings

### Android mobile app

- Fixed overlapping Racepad button slots: every control (`GEAR DOWN`, `GEAR UP`, `CAM`, `MAP`, `LIGHTS`, `HORN`, `WIPERS`, `IGN`, `HANDBRAKE`, `NITRO`, `CRUISE`, `PIT`, `A/B/X/Y`) now uses its own unique button slot (`0..15`) with zero shared triggers.
- Separated overlapping touch zones: moved `CAM`/`MAP` off the `GEAR UP` paddle in Racepad mode, spaced Left Stick and D-Pad cleanly in Gamepad mode, moved `VIEW`/`GUIDE`/`MENU` above Right Stick, separated Right Stick from `ABXY`, and locked active stick fingers by pointer ID.
- Enabled `TCP_NODELAY`, bumped input streaming to 100Hz with immediate wake on touch/steering changes, lowered pedal minimum floor to 4%, switched sensor filtering to velocity-adaptive smoothing, and removed double-filtering delay on the virtual wheel.

### Windows PC companion

- Removed overlapping XInput aliases (`HANDBRAKE`/`PIT` no longer trigger Xbox `GUIDE`, `NITRO`/`CRUISE` no longer overlap `D-Pad`, and `PADDLE` no longer duplicates `LB`/`RB`), while keeping all 16 buttons separate in vJoy.
- Enabled `TCP_NODELAY` on phone sockets and upgraded desktop/web cockpit polling from 6.6Hz (`150ms`) to 30Hz (`33ms`) over HTTP/1.1 keep-alive for smooth telemetry.

## 0.1.0-beta.3 - Realistic steering-wheel rotation

### Android mobile app

- Removed forward/back tilt steering (`orientation[2]`) and replaced it with true left/right phone steering-wheel rotation around the screen axis using `TYPE_GAME_ROTATION_VECTOR` quaternion swing-twist math.
- Added selectable wheel lock ranges (`60°`, `90°` default, `135°`, `180°` each side), responsive smoothing, and a `1.5°` center deadzone.
- Upgraded the virtual steering wheel with a 3-spoke GT wheel, thumb grips, 12 o'clock marker, synchronized arc gauge, circular finger-rotation touch drag, haptic center/lock cues, and smooth caster self-centering on release.

### Windows PC companion

- Synced desktop and web cockpit steering wheel displays with full wheel rotation angles (`steer_deg`, defaulting to `±90°`).

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
