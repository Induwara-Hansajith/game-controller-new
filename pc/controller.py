"""Selectable Xbox XInput and vJoy DirectInput output backends."""

from __future__ import annotations

import ctypes
import os
import threading
from pathlib import Path


DEVICE_ID = 1
HID_USAGE_X = 0x30
HID_USAGE_Y = 0x31
HID_USAGE_Z = 0x32
HID_USAGE_RX = 0x33
HID_USAGE_RY = 0x34
HID_USAGE_RZ = 0x35
HID_USAGE_SL0 = 0x36
HID_USAGE_SL1 = 0x37
MAX_BUTTONS = 16
STATUS_NAMES = {
    0: "owned",
    1: "available",
    2: "busy (in use by another app)",
    3: "missing (unconfigured)",
    4: "unknown",
}


class VJoyUnavailable(RuntimeError):
    pass


class ControllerUnavailable(RuntimeError):
    pass


class VJoyDevice:
    """Maps race controls, a POV hat, and up to 16 buttons to vJoy device 1.

    This only talks to a driver that is already installed. It never installs,
    configures, or changes the driver's device definition.
    """

    def __init__(self, device_id: int = DEVICE_ID) -> None:
        self.device_id = device_id
        self._dll: ctypes.CDLL | None = None
        self._acquired = False
        self._axis_ranges: dict[int, tuple[int, int]] = {}
        self._button_count = 0
        self._continuous_pov_count = 0
        self._discrete_pov_count = 0
        self._set_cont_pov = None
        self._set_disc_pov = None
        self._error: str | None = None
        self._last_axes: dict[int, int] = {}
        self._last_buttons: dict[int, int] = {}
        self._last_pov: int | None = None
        self._load()

    def _reset_cache(self) -> None:
        self._last_axes.clear()
        self._last_buttons.clear()
        self._last_pov = None

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
            try:
                dll.GetVJDContPovNumber.argtypes = [uint]
                dll.GetVJDContPovNumber.restype = ctypes.c_int
                dll.GetVJDDiscPovNumber.argtypes = [uint]
                dll.GetVJDDiscPovNumber.restype = ctypes.c_int
                dll.SetContPov.argtypes = [ctypes.c_ulong, uint, ctypes.c_ubyte]
                dll.SetContPov.restype = bool32
                dll.SetDiscPov.argtypes = [ctypes.c_long, uint, ctypes.c_ubyte]
                dll.SetDiscPov.restype = bool32
                self._set_cont_pov = dll.SetContPov
                self._set_disc_pov = dll.SetDiscPov
            except AttributeError:
                # Older vJoy interface builds can still provide axes/buttons.
                pass
        except (OSError, AttributeError) as exc:
            self._error = f"Could not load the vJoy API: {exc}"
            return

        self._dll = dll
        if not dll.vJoyEnabled():
            self._error = "The vJoy driver is installed but disabled in Windows."
            return

        for usage in (HID_USAGE_X, HID_USAGE_Y, HID_USAGE_Z, HID_USAGE_RX, HID_USAGE_RY,
                      HID_USAGE_RZ, HID_USAGE_SL0, HID_USAGE_SL1):
            if not dll.GetVJDAxisExist(self.device_id, usage):
                continue
            low, high = ctypes.c_long(), ctypes.c_long()
            if dll.GetVJDAxisMin(self.device_id, usage, ctypes.byref(low)) and dll.GetVJDAxisMax(
                self.device_id, usage, ctypes.byref(high)
            ):
                self._axis_ranges[usage] = (low.value, high.value)

        self._button_count = max(0, int(dll.GetVJDButtonNumber(self.device_id)))
        if self._set_cont_pov is not None:
            self._continuous_pov_count = max(0, int(dll.GetVJDContPovNumber(self.device_id)))
        if self._set_disc_pov is not None:
            self._discrete_pov_count = max(0, int(dll.GetVJDDiscPovNumber(self.device_id)))
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
        pov_count = self._continuous_pov_count + self._discrete_pov_count
        return f"vJoy device {self.device_id}: {status_text} | {axis_count} axes | {self._button_count} buttons | {pov_count} POV"

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
        if self._last_axes.get(usage) == value:
            return
        self._dll.SetAxis(value, self.device_id, usage)
        self._last_axes[usage] = value

    def apply(self, data: dict) -> None:
        if not self._acquired or self._dll is None:
            return
        if str(data.get("mode", "racepad")).lower() == "gamepad":
            axis_values = {
                HID_USAGE_X: self._clamp(data.get("lx", 0.0), -1.0, 1.0),
                HID_USAGE_Y: self._clamp(data.get("ly", 0.0), -1.0, 1.0),
                HID_USAGE_Z: self._clamp(data.get("rx", 0.0), -1.0, 1.0),
                HID_USAGE_RX: self._clamp(data.get("ry", 0.0), -1.0, 1.0),
                HID_USAGE_RY: self._clamp(data.get("lt", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_RZ: self._clamp(data.get("rt", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_SL0: -1.0,
                HID_USAGE_SL1: -1.0,
            }
        else:
            axis_values = {
                HID_USAGE_X: self._clamp(data.get("steer", data.get("lx", 0.0)), -1.0, 1.0),
                HID_USAGE_Y: self._clamp(data.get("throttle", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_Z: self._clamp(data.get("brake", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_RX: self._clamp(data.get("clutch", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_RY: self._clamp(data.get("handbrake", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_RZ: self._clamp(data.get("rx", 0.0), -1.0, 1.0),
                HID_USAGE_SL0: self._clamp(data.get("lt", 0.0), 0.0, 1.0) * 2.0 - 1.0,
                HID_USAGE_SL1: self._clamp(data.get("rt", 0.0), 0.0, 1.0) * 2.0 - 1.0,
            }
        for usage, value in axis_values.items():
            self._axis(usage, value)
        buttons = data.get("buttons", [])
        if not isinstance(buttons, list):
            buttons = []
        for index in range(min(self._button_count, MAX_BUTTONS)):
            pressed = int(index < len(buttons) and bool(buttons[index]))
            if self._last_buttons.get(index) != pressed:
                self._dll.SetBtn(pressed, self.device_id, index + 1)
                self._last_buttons[index] = pressed
        self._set_pov(data.get("dpad", {}))

    @staticmethod
    def _dpad_direction(dpad: object) -> tuple[int, int] | None:
        if isinstance(dpad, str):
            dpad = {name: name in dpad.lower() for name in ("up", "down", "left", "right")}
        if not isinstance(dpad, dict):
            return None
        vertical = int(bool(dpad.get("up"))) - int(bool(dpad.get("down")))
        horizontal = int(bool(dpad.get("right"))) - int(bool(dpad.get("left")))
        return (vertical, horizontal) if vertical or horizontal else None

    def _set_pov(self, dpad: object) -> None:
        if not self._acquired:
            return
        direction = self._dpad_direction(dpad)
        angle_by_direction = {
            (1, 0): 0, (1, 1): 4500, (0, 1): 9000, (-1, 1): 13500,
            (-1, 0): 18000, (-1, -1): 22500, (0, -1): 27000, (1, -1): 31500,
        }
        angle = angle_by_direction.get(direction) if direction else None
        if self._continuous_pov_count and self._set_cont_pov is not None:
            pov_val = 0xFFFFFFFF if angle is None else angle
            if self._last_pov != pov_val:
                self._set_cont_pov(pov_val, self.device_id, 1)
                self._last_pov = pov_val
        elif self._discrete_pov_count and self._set_disc_pov is not None:
            discrete = {0: 0, 4500: 1, 9000: 2, 13500: 3, 18000: 4,
                        22500: 5, 27000: 6, 31500: 7}
            pov_val = -1 if angle is None else discrete[angle]
            if self._last_pov != pov_val:
                self._set_disc_pov(pov_val, self.device_id, 1)
                self._last_pov = pov_val

    def neutral(self) -> None:
        if not self._acquired:
            return
        self._reset_cache()
        self.apply({"steer": 0.0, "throttle": 0.0, "brake": 0.0, "clutch": 0.0,
                    "handbrake": 0.0, "lx": 0.0, "ly": 0.0, "rx": 0.0, "ry": 0.0,
                    "lt": 0.0, "rt": 0.0, "buttons": [], "dpad": {}})

    def close(self) -> None:
        if self._acquired and self._dll is not None:
            try:
                self.neutral()
                self._dll.ResetVJD(self.device_id)
                self._dll.RelinquishVJD(self.device_id)
            except Exception:
                pass
        self._acquired = False
        self._reset_cache()


class Xbox360Device:
    """Emulates one Xbox 360 XInput pad through an already-installed ViGEmBus."""

    def __init__(self) -> None:
        self._pad = None
        self._vg = None
        self._acquired = False
        self._error: str | None = None
        self._last_report_key: tuple | None = None

    def _load(self) -> bool:
        if self._vg is not None:
            return True
        if os.name != "nt":
            self._error = "Xbox 360 emulation requires Windows and ViGEmBus."
            return False
        try:
            import vgamepad as vg

            # Import connects to the ViGEm bus; the actual virtual controller is
            # created only when acquire() is called.
            self._vg = vg
            self._error = None
            return True
        except Exception as exc:
            self._error = str(exc)
            return False

    @property
    def is_available(self) -> bool:
        return self._acquired or self._load()

    @property
    def description(self) -> str:
        if self._acquired and self._pad is not None:
            return "Xbox 360 XInput: active via ViGEmBus"
        if self.is_available:
            return "Xbox 360 XInput: ready via ViGEmBus"
        return f"Xbox 360 XInput unavailable ({self._error or 'ViGEmBus not installed'})"

    def acquire(self) -> None:
        if self._acquired and self._pad is not None:
            self.neutral()
            return
        try:
            if not self._load() or self._vg is None:
                raise ControllerUnavailable(self._error or "ViGEmBus is unavailable.")
            self._pad = self._vg.VX360Gamepad()
            self._acquired = True
            self._last_report_key = None
            self.neutral()
        except Exception as exc:
            self._error = str(exc)
            raise ControllerUnavailable(f"Could not initialize Xbox 360 XInput output: {exc}") from exc

    @staticmethod
    def _dpad_buttons(dpad: object) -> set[str]:
        if isinstance(dpad, str):
            return {name for name in ("up", "down", "left", "right") if name in dpad.lower()}
        if isinstance(dpad, dict):
            return {name for name in ("up", "down", "left", "right") if bool(dpad.get(name))}
        return set()

    def apply(self, data: dict) -> None:
        if not self._acquired or self._pad is None:
            return
        pad = self._pad
        vg = self._vg
        if vg is None:
            return
        clamp = VJoyDevice._clamp
        mode = str(data.get("mode", "racepad")).lower()
        if mode == "gamepad":
            lx = clamp(data.get("lx"), -1.0, 1.0)
            ly = clamp(data.get("ly", 0.0), -1.0, 1.0)
            rx = clamp(data.get("rx", 0.0), -1.0, 1.0)
            ry = clamp(data.get("ry", 0.0), -1.0, 1.0)
            lt = clamp(data.get("lt", 0.0), 0.0, 1.0)
            rt = clamp(data.get("rt", 0.0), 0.0, 1.0)
        else:
            lx = clamp(data.get("steer", data.get("lx", 0.0)), -1.0, 1.0)
            ly = clamp(data.get("ly", 0.0), -1.0, 1.0)
            # Racepad clutch and handbrake use the right stick, leaving both
            # analog triggers available for accelerator and brake.
            rx = clamp(data.get("handbrake", 0.0), 0.0, 1.0)
            ry = -clamp(data.get("clutch", 0.0), 0.0, 1.0)
            lt = clamp(data.get("brake", 0.0), 0.0, 1.0)
            rt = clamp(data.get("throttle", 0.0), 0.0, 1.0)

        buttons = data.get("buttons", [])
        if not isinstance(buttons, list):
            buttons = []
        # Keep XInput button flags 1:1 without overlapping D-Pad or triggering Guide in Racepad mode.
        # vJoy exposes all 16 independent buttons separately.
        btn_map = [
            vg.XUSB_BUTTON.XUSB_GAMEPAD_A,              # 0: A
            vg.XUSB_BUTTON.XUSB_GAMEPAD_B,              # 1: B
            vg.XUSB_BUTTON.XUSB_GAMEPAD_X,              # 2: X
            vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,              # 3: Y
            vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,  # 4: LB / GEAR DOWN
            vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER, # 5: RB / GEAR UP
            vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,           # 6: VIEW / CAM
            vg.XUSB_BUTTON.XUSB_GAMEPAD_START,          # 7: MENU / MAP
            vg.XUSB_BUTTON.XUSB_GAMEPAD_GUIDE if mode == "gamepad" else None,  # 8: GUIDE (Gamepad only)
            vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,     # 9: L3 / HORN
            vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,    # 10: R3 / LIGHTS
        ]
        pressed = {flag for i, flag in enumerate(btn_map) if flag is not None and i < len(buttons) and bool(buttons[i])}
        all_flags = {flag for flag in btn_map if flag is not None}
        dpad = self._dpad_buttons(data.get("dpad", {}))
        dpad_map = {
            "up": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
            "down": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
            "left": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
            "right": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        }
        pressed.update(dpad_map[name] for name in dpad)

        w_buttons = 0
        for flag in pressed:
            w_buttons |= int(flag)
        s_lx = round(lx * 32767)
        s_ly = round(ly * 32767)
        s_rx = round(rx * 32767)
        s_ry = round(ry * 32767)
        b_lt = round(lt * 255)
        b_rt = round(rt * 255)
        report_key = (s_lx, s_ly, s_rx, s_ry, b_lt, b_rt, w_buttons)
        if report_key == self._last_report_key:
            return

        report = pad.report
        report.sThumbLX = s_lx
        report.sThumbLY = s_ly
        report.sThumbRX = s_rx
        report.sThumbRY = s_ry
        report.bLeftTrigger = b_lt
        report.bRightTrigger = b_rt
        report.wButtons = w_buttons
        pad.update()
        self._last_report_key = report_key

    def neutral(self) -> None:
        if not self._acquired or self._pad is None:
            return
        self._last_report_key = None
        pad = self._pad
        pad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
        pad.right_joystick_float(x_value_float=0.0, y_value_float=0.0)
        pad.right_trigger_float(value_float=0.0)
        pad.left_trigger_float(value_float=0.0)
        pad.reset()
        pad.update()

    def close(self) -> None:
        self._last_report_key = None
        if self._pad is not None:
            try:
                self.neutral()
            except Exception:
                pass
            pad, self._pad = self._pad, None
            try:
                del pad  # VX360Gamepad.__del__ removes the virtual target.
            except Exception:
                pass
        self._acquired = False


class UnifiedDevice:
    """Routes one input stream to Auto, Xbox 360 XInput, or vJoy DirectInput."""

    def __init__(self, vjoy_id: int = DEVICE_ID) -> None:
        self.xbox = Xbox360Device()
        self.vjoy = VJoyDevice(vjoy_id)
        self._lock = threading.RLock()
        self.selected_driver = "auto"
        self._active_driver: str | None = None

    @property
    def active_driver(self) -> str | None:
        return self._active_driver

    @property
    def drivers(self) -> dict:
        return {
            "xinput": {"available": self.xbox.is_available, "description": self.xbox.description},
            "vjoy": {"available": self.vjoy.is_available, "description": self.vjoy.description},
        }

    @property
    def is_available(self) -> bool:
        return self.xbox.is_available or self.vjoy.is_available

    @property
    def description(self) -> str:
        if self._active_driver == "xinput":
            return self.xbox.description
        if self._active_driver == "vjoy":
            return self.vjoy.description
        statuses = self.drivers
        available = [
            status["description"] for status in statuses.values() if status["available"]
        ]
        return "Selected: " + self.selected_driver + (" · Ready: " + " / ".join(available) if available else " · No virtual controller driver available")

    def set_driver(self, driver: str) -> None:
        driver = str(driver).strip().lower()
        if driver not in ("auto", "xinput", "vjoy"):
            raise ValueError("Output driver must be auto, xinput, or vjoy.")
        with self._lock:
            if driver == self.selected_driver:
                return
            self.neutral()
            self.xbox.close()
            self.vjoy.close()
            self._active_driver = None
            self.selected_driver = driver

    def _candidate_backends(self, mode: str):
        if self.selected_driver == "xinput":
            return [("xinput", self.xbox)]
        if self.selected_driver == "vjoy":
            return [("vjoy", self.vjoy)]

        preferred = "xinput" if mode == "gamepad" else "vjoy"
        fallback = "vjoy" if preferred == "xinput" else "xinput"
        return [
            ("xinput", self.xbox) if name == "xinput" else ("vjoy", self.vjoy)
            for name in (preferred, fallback)
        ]

    def _activate(self, mode: str) -> None:
        candidates = self._candidate_backends(mode)
        if self._active_driver == candidates[0][0] and (
            (self._active_driver == "xinput" and self.xbox._acquired)
            or (self._active_driver == "vjoy" and self.vjoy._acquired)
        ):
            return
        self.xbox.close()
        self.vjoy.close()
        errors = []
        for name, backend in candidates:
            if not backend.is_available:
                errors.append(backend.description)
                continue
            try:
                backend.acquire()
                self._active_driver = name
                return
            except (ControllerUnavailable, VJoyUnavailable, RuntimeError) as exc:
                errors.append(str(exc))
                backend.close()
                if self.selected_driver != "auto":
                    raise
        self._active_driver = None
        raise ControllerUnavailable("No output driver could be activated: " + " | ".join(errors))

    def acquire(self, mode: str = "racepad") -> None:
        with self._lock:
            self._activate(mode)

    def apply(self, data: dict) -> None:
        with self._lock:
            mode = str(data.get("mode", "racepad")).lower()
            self._activate(mode)
            backend = self.xbox if self._active_driver == "xinput" else self.vjoy
            backend.apply(data)

    def neutral(self) -> None:
        with self._lock:
            for backend in (self.xbox, self.vjoy):
                try:
                    backend.neutral()
                except Exception:
                    pass

    def close(self) -> None:
        with self._lock:
            self.neutral()
            self.xbox.close()
            self.vjoy.close()
            self._active_driver = None


# Default export alias
ControllerDevice = UnifiedDevice
