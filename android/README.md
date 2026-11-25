# Android mobile app

The native Android controller runs in landscape. It connects to the PC app by LAN address and six-digit PIN, then sends tilt steering, held touch pedals, and A/B/X/Y button state over a local TCP connection.

## Build and install

Open this folder in Android Studio or run `gradlew.bat -p . assembleDebug` from the project root. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android version: Android 8.

## Use

1. Start the PC companion and open the Android app on the same trusted private Wi-Fi. It tries to discover the PC automatically; if needed, enter the displayed address and PIN in the top row.
2. Hold the phone in your preferred neutral position and tap **Center**.
3. Leave **Gyro steer** enabled for tilt steering. Turn it off to steer by dragging the wheel.
4. Touch and hold the gas or brake areas and the A/B/X/Y buttons to press them.

The app saves the most recently used PC address on the phone. Keep the screen open while playing; leaving the app disconnects and clears controls.
