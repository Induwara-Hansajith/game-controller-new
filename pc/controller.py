"""Small ctypes adapter for an already-installed vJoy driver."""

from __future__ import annotations

import ctypes
import os
from pathlib import Path


DEVICE_ID = 1
HID_USAGE_X = 0x30
HID_USAGE_Y = 0x31
HID_USAGE_Z = 0x32
STATUS_NAMES = {
    0: "owned",
    1: "available",
    2: "busy (in use by another app)",
    3: "missing (unconfigured)",
    4: "unknown",
}


class VJoyUnavailable(RuntimeError):
    pass


class VJoyDevice:
    """Maps steering/throttle/brake and eight buttons to vJoy device 1.

    This only talks to a driver that is already installed. It never installs,
    configures, or changes the driver's device definition.
    """

    def __init__(self, device_id: int = DEVICE_ID) -> None:
        self.device_id = device_id
        self._dll: ctypes.CDLL | None = None
        self._acquired = False
        self._axis_ranges: dict[int, tuple[int, int]] = {}
        self._button_count = 0
        self._error: str | None = None
        self._load()

    def _load(self) -> None:
        if os.name != "nt":
            self._error = "The vJoy receiver runs on Windows."
            return

        configured = os.environ.get("VJOY_DLL_PATH")
        candidates = [Path(configured)] if configured else []
        candidates += [
            Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "vJoy" / "x64" / "vJoyInterface.dll",
            Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "vJoy" / "x86" / "vJoyInterface.dll",
        ]
        dll_path = next((p for p in candidates if p and p.is_file()), None)
        if dll_path is None:
            self._error = "vJoyInterface.dll was not found. Install vJoy to enable virtual joystick output."
            return

        try:
            dll = ctypes.CDLL(str(dll_path))
            uint = ctypes.c_uint
            long = ctypes.c_long
            bool32 = ctypes.c_int

            dll.vJoyEnabled.argtypes = []
            dll.vJoyEnabled.restype = bool32
            dll.GetVJDStatus.argtypes = [uint]
            dll.GetVJDStatus.restype = ctypes.c_int
            dll.AcquireVJD.argtypes = [uint]
            dll.AcquireVJD.restype = bool32
            dll.RelinquishVJD.argtypes = [uint]
            dll.RelinquishVJD.restype = None
            dll.ResetVJD.argtypes = [uint]
            dll.ResetVJD.restype = bool32
            dll.GetVJDAxisExist.argtypes = [uint, uint]
            dll.GetVJDAxisExist.restype = bool32
            dll.GetVJDAxisMin.argtypes = [uint, uint, ctypes.POINTER(long)]
            dll.GetVJDAxisMin.restype = bool32
            dll.GetVJDAxisMax.argtypes = [uint, uint, ctypes.POINTER(long)]
            dll.GetVJDAxisMax.restype = bool32
            dll.GetVJDButtonNumber.argtypes = [uint]
            dll.GetVJDButtonNumber.restype = ctypes.c_int
            dll.SetAxis.argtypes = [long, uint, uint]
            dll.SetAxis.restype = bool32
            dll.SetBtn.argtypes = [bool32, uint, ctypes.c_ubyte]
            dll.SetBtn.restype = bool32
        except (OSError, AttributeError) as exc:
            self._error = f"Could not load the vJoy API: {exc}"
            return

        self._dll = dll
        if not dll.vJoyEnabled():
            self._error = "The vJoy driver is installed but disabled in Windows."
            return

        for usage in (HID_USAGE_X, HID_USAGE_Y, HID_USAGE_Z):
            if not dll.GetVJDAxisExist(self.device_id, usage):
                continue
            low, high = ctypes.c_long(), ctypes.c_long()
            if dll.GetVJDAxisMin(self.device_id, usage, ctypes.byref(low)) and dll.GetVJDAxisMax(
                self.device_id, usage, ctypes.byref(high)
            ):
                self._axis_ranges[usage] = (low.value, high.value)

        self._button_count = max(0, int(dll.GetVJDButtonNumber(self.device_id)))
        self._error = None

    @property
    def is_available(self) -> bool:
        if self._dll is None:
            self._load()
        if self._dll is None:
            return False
        status = int(self._dll.GetVJDStatus(self.device_id))
        return status in (0, 1)

    @property
    def description(self) -> str:
        if self._dll is None:
            self._load()
        if self._dll is None:
            return f"vJoy unavailable ({self._error or 'driver not loaded'})"

        status = int(self._dll.GetVJDStatus(self.device_id))
        status_text = STATUS_NAMES.get(status, "unknown")
        axis_count = len(self._axis_ranges)
        return f"vJoy device {self.device_id}: {status_text} · {axis_count} axes · {self._button_count} buttons"

    def acquire(self) -> None:
        if self._acquired:
            self.neutral()
            return

        if self._dll is None:
            self._load()
        if self._dll is None:
            raise VJoyUnavailable(self._error or "vJoy driver is unavailable.")

        status = int(self._dll.GetVJDStatus(self.device_id))
        if status == 0:  # VJD_STAT_OWN (already owned by this process)
            self._acquired = True
            self.neutral()
            return
        if status != 1:  # VJD_STAT_FREE
            status_text = STATUS_NAMES.get(status, "not available")
            raise VJoyUnavailable(f"vJoy device {self.device_id} is {status_text}.")

        if not self._dll.AcquireVJD(self.device_id):
            raise VJoyUnavailable(f"Could not acquire vJoy device {self.device_id}.")

        self._acquired = True
        self.neutral()

    @staticmethod
    def _clamp(value: object, low: float, high: float) -> float:
        try:
            number = float(value)
        except (TypeError, ValueError):
            return low
        if number != number or number in (float("inf"), float("-inf")):
            return low
        return max(low, min(high, number))

    def _axis(self, usage: int, normalized: float) -> None:
        bounds = self._axis_ranges.get(usage)
        if not self._acquired or bounds is None or self._dll is None:
            return
        low, high = bounds
        value = round(low + ((self._clamp(normalized, -1.0, 1.0) + 1.0) * 0.5 * (high - low)))
        self._dll.SetAxis(value, self.device_id, usage)

    def apply(self, data: dict) -> None:
        if not self._acquired or self._dll is None:
            return
        self._axis(HID_USAGE_X, self._clamp(data.get("steer"), -1.0, 1.0))
        self._axis(HID_USAGE_Y, self._clamp(data.get("throttle"), 0.0, 1.0) * 2.0 - 1.0)
        self._axis(HID_USAGE_Z, self._clamp(data.get("brake"), 0.0, 1.0) * 2.0 - 1.0)
        buttons = data.get("buttons", [])
        if not isinstance(buttons, list):
            buttons = []
        for index in range(min(self._button_count, 8)):
            pressed = index < len(buttons) and bool(buttons[index])
            self._dll.SetBtn(int(pressed), self.device_id, index + 1)

    def neutral(self) -> None:
        if not self._acquired:
            return
        self.apply({"steer": 0.0, "throttle": 0.0, "brake": 0.0, "buttons": []})

    def close(self) -> None:
        if self._acquired and self._dll is not None:
            try:
                self._dll.ResetVJD(self.device_id)
                self.neutral()
                self._dll.RelinquishVJD(self.device_id)
            except Exception:
                pass
        self._acquired = False


class Xbox360Device:
    """Emulates a genuine Microsoft Xbox 360 Controller (XInput) via ViGEmBus.

    Provides universal out-of-the-box plug & play compatibility with all Steam games,
    Xbox PC / Game Pass titles, Forza Horizon, Assetto Corsa, F1, Euro Truck Simulator,
    and flight/racing simulators.
    """

    def __init__(self) -> None:
        self._pad = None
        self._acquired = False
        self._error: str | None = None

    @property
    def is_available(self) -> bool:
        if self._acquired:
            return True
        try:
            import vgamepad as vg

            test_pad = vg.VX360Gamepad()
            del test_pad
            return True
        except Exception as exc:
            self._error = str(exc)
            return False

    @property
    def description(self) -> str:
        if self._acquired and self._pad is not None:
            return "Xbox 360 Controller (ViGEmBus): Active · Native XInput"
        if self.is_available:
            return "Xbox 360 Controller (ViGEmBus): Available"
        return f"Xbox 360 Controller unavailable ({self._error or 'ViGEmBus not loaded'})"

    def acquire(self) -> None:
        if self._acquired and self._pad is not None:
            self.neutral()
            return
        try:
            import vgamepad as vg

            self._pad = vg.VX360Gamepad()
            self._acquired = True
            self.neutral()
        except Exception as exc:
            self._error = str(exc)
            raise RuntimeError(f"Could not initialize Xbox 360 controller: {exc}") from exc

    def apply(self, data: dict) -> None:
        if not self._acquired or self._pad is None:
            return
        import vgamepad as vg

        pad = self._pad
        steer = max(-1.0, min(1.0, float(data.get("steer", 0.0) or 0.0)))
        throttle = max(0.0, min(1.0, float(data.get("throttle", 0.0) or 0.0)))
        brake = max(0.0, min(1.0, float(data.get("brake", 0.0) or 0.0)))
        buttons = data.get("buttons", [])

        # Left Thumbstick X-axis for analog steering (-1.0 to 1.0)
        pad.left_joystick_float(x_value_float=steer, y_value_float=0.0)
        # Right Trigger for progressive Gas (0.0 to 1.0)
        pad.right_trigger_float(value_float=throttle)
        # Left Trigger for progressive Brake (0.0 to 1.0)
        pad.left_trigger_float(value_float=brake)

        # Standard Xbox 360 button matrix
        btn_map = [
            vg.XUSB_BUTTON.XUSB_GAMEPAD_A,              # 0: A
            vg.XUSB_BUTTON.XUSB_GAMEPAD_B,              # 1: B
            vg.XUSB_BUTTON.XUSB_GAMEPAD_X,              # 2: X
            vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,              # 3: Y
            vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,   # 4: LB (Gear Down / Clutch)
            vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,  # 5: RB (Gear Up)
            vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,            # 6: Back (Handbrake)
            vg.XUSB_BUTTON.XUSB_GAMEPAD_START,           # 7: Start (Nitro / Boost)
        ]

        for i, xbtn in enumerate(btn_map):
            if i < len(buttons) and buttons[i]:
                pad.press_button(button=xbtn)
            else:
                pad.release_button(button=xbtn)

        pad.update()

    def neutral(self) -> None:
        if not self._acquired or self._pad is None:
            return
        pad = self._pad
        pad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
        pad.right_trigger_float(value_float=0.0)
        pad.left_trigger_float(value_float=0.0)
        pad.reset()
        pad.update()

    def close(self) -> None:
        if self._pad is not None:
            try:
                self.neutral()
            except Exception:
                pass
            self._pad = None
        self._acquired = False


class UnifiedDevice:
    """Unified Game Controller supporting both native Xbox 360 (XInput via ViGEmBus)
    and DirectInput (vJoy) simultaneously for universal compatibility with all Steam,
    Xbox Game Pass, and simulator games."""

    def __init__(self, vjoy_id: int = DEVICE_ID) -> None:
        self.xbox = Xbox360Device()
        self.vjoy = VJoyDevice(vjoy_id)
        self._acquired = False

    @property
    def is_available(self) -> bool:
        return self.xbox.is_available or self.vjoy.is_available

    @property
    def description(self) -> str:
        active = []
        if self.xbox._acquired:
            active.append("Xbox 360: Active (XInput)")
        elif self.xbox.is_available:
            active.append("Xbox 360: Ready")
        if self.vjoy._acquired:
            active.append(f"vJoy {self.vjoy.device_id}: Owned")
        elif self.vjoy.is_available:
            active.append(f"vJoy {self.vjoy.device_id}: Ready")
        return " · ".join(active) if active else "No virtual gamepad driver ready"

    def acquire(self) -> None:
        errors = []
        acquired_any = False
        try:
            self.xbox.acquire()
            acquired_any = True
        except Exception as exc:
            errors.append(f"Xbox 360: {exc}")

        try:
            self.vjoy.acquire()
            acquired_any = True
        except Exception as exc:
            errors.append(f"vJoy: {exc}")

        if not acquired_any:
            raise VJoyUnavailable("; ".join(errors))
        self._acquired = True

    def apply(self, data: dict) -> None:
        if self.xbox._acquired:
            try:
                self.xbox.apply(data)
            except Exception:
                pass
        if self.vjoy._acquired:
            try:
                self.vjoy.apply(data)
            except Exception:
                pass

    def neutral(self) -> None:
        if self.xbox._acquired:
            try:
                self.xbox.neutral()
            except Exception:
                pass
        if self.vjoy._acquired:
            try:
                self.vjoy.neutral()
            except Exception:
                pass

    def close(self) -> None:
        self.xbox.close()
        self.vjoy.close()
        self._acquired = False


# Default export alias
ControllerDevice = UnifiedDevice
