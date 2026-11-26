# Pocket Controller for iOS

Native SwiftUI-free UIKit iPhone/iPad controller matching the Windows PC companion (`0.1.0-beta.8`):
- **Steering wheel rotation (`CoreMotion`)** with `F1 Crisp`, `Balanced`, and `Smooth` response profiles plus `45°–180°` wheel lock ranges
- **250Hz `PCU1` binary UDP** streaming with `0ms` button/paddle edge wakeup, TCP fallback, and silent auto-reconnect
- **Gyro Racepad layout** (hidden virtual wheel, wide left Brake, center Clutch, wide right Gas), **Touch Wheel Racepad**, and **Xbox Gamepad**
- **Customizable buttons** (`✎ EDIT` to drag and resize any control, saved separately per mode)

## Build `.ipa`

### Automated via GitHub Actions (No Mac required)
Push changes to `main` (or run the **Build iOS IPA** workflow from the GitHub **Actions** tab) to compile an unsigned `PocketController-iOS.ipa` on macOS.
Apple users can install the `.ipa` using **AltStore**, **SideStore**, or **Sideloadly** (free Apple ID), or resign for **TestFlight / App Store** in Xcode on a Mac.

### Local Xcode (macOS)
```bash
cd ios
brew install xcodegen
xcodegen generate
open PocketController.xcodeproj
```
