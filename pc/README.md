# Windows PC companion

## Run from source

Run `start.bat` to open the desktop companion. It starts the receiver and shows the current PC address, six-digit pairing PIN, connection state, vJoy state, and live input. Use **Open dashboard** for the browser view or **Restart receiver** to generate a new PIN.

You can also run `python desktop_app.py` or `python receiver.py`. The receiver listens on TCP port 8766 for the phone and serves the local dashboard at `http://127.0.0.1:8765`.

## Build the standalone app

Run `build.bat` on Windows. It creates `dist/PocketControllerPC.exe`. The build uses an isolated `.venv-build` environment and bundles the Python runtime and dashboard files.

The gaming PC still needs vJoy installed and configured: device 1 should have X, Y, and Z axes and up to eight buttons. The app never installs or reconfigures the driver. vJoy maps steering to X, throttle to Y, and brake to Z. Disconnecting a phone returns all controls to neutral and releases vJoy.

The receiver broadcasts its presence on the local network so Android can auto-detect the PC. The PC companion allows one connected phone at a time and does not currently create an Xbox/XInput device.
