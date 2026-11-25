# Windows PC companion

## Run from source

Run `start.bat` to open the desktop companion. It starts the receiver and shows the current PC address, six-digit pairing PIN, connection state, active output driver, and live controls. Choose Auto, Xbox 360 (XInput), or vJoy (Sim Wheel). Use **Open dashboard** for the browser view or **Restart receiver** to generate a new PIN.

You can also run `python desktop_app.py` or `python receiver.py`. The receiver listens on TCP port 8766 for the phone and serves the local dashboard at `http://127.0.0.1:8765`.

## Build the standalone app

Run `build.bat` on Windows. It creates `dist/PocketControllerPC.exe`. The build uses an isolated `.venv-build` environment and bundles the Python runtime and dashboard files.

The PC needs ViGEmBus installed for Xbox 360 XInput emulation, or vJoy installed for DirectInput output. PocketController does not install or configure either driver. vJoy maps steering to X, throttle to Y, brake to Z, clutch to RX, handbrake to RY, the D-pad to a configured POV hat, and up to 16 buttons. Missing optional vJoy axes or POV controls are skipped. Disconnect, stale input, and shutdown reset controls; shutdown also releases the active virtual device.

The receiver broadcasts its presence on the local network so Android can auto-detect the PC. It accepts one connected phone at a time. Auto uses XInput for Gamepad input and vJoy for Racepad input, falling back to the other available output. XInput has fewer independent button flags than the phone's 16-button array, so extra paddle/gear controls are aliases; vJoy can expose 16 separate configured buttons.
