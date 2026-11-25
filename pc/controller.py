"""Small ctypes adapter for an already-installed vJoy driver."""

from __future__ import annotations

import ctypes
import os
from pathlib import Path


DEVICE_ID = 1
HID_USAGE_X = 0x30
HID_USAGE_Y = 0x31
HID_USAGE_Z = 0x32
STATUS_NAMES = {0: "owned", 1: "available", 2: "busy", 3: "missing", 4: "unknown"}


class VJoyUnavailable(RuntimeError):
    pass


class VJoyDevice:
    """Maps steering/throttle/brake and eight buttons to vJoy device 1.

    This only talks to a driver that is already installed. It never installs,
    configures, or changes the driver's device definition.
    """

    def __init__(self, device_id: int = DEVICE_ID) -> None:
        self.device_id = device_id
        self._dll = None
        self._acquired = False
        self._axis_ranges: dict[int, tuple[int, int]] = {}
        self._button_count = 0
        self._load()

    def _load(self) -> None:
        if os.name != "nt":
            raise VJoyUnavailable("The vJoy receiver runs on Windows.")

        configured = os.environ.get("VJOY_DLL_PATH")
        candidates = [Path(configured)] if configured else []
        candidates += [
            Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "vJoy" / "x64" / "vJoyInterface.dll",
            Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "vJoy" / "x86" / "vJoyInterface.dll",
        ]
        dll_path = next((p for p in candidates if p and p.is_file()), None)
        if dll_path is None:
            raise VJoyUnavailable("vJoyInterface.dll was not found. Install/configure vJoy, then restart the receiver.")

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
            raise VJoyUnavailable(f"Could not load the vJoy API: {exc}") from exc

        self._dll = dll
        if not dll.vJoyEnabled():
            raise VJoyUnavailable("The vJoy driver is installed but not enabled.")

        status = int(dll.GetVJDStatus(self.device_id))
        if status in (3, 4):
            raise VJoyUnavailable(f"vJoy device {self.device_id} is {STATUS_NAMES.get(status, 'unavailable')}.")

        for usage in (HID_USAGE_X, HID_USAGE_Y, HID_USAGE_Z):
            if not dll.GetVJDAxisExist(self.device_id, usage):
                continue
            low, high = ctypes.c_long(), ctypes.c_long()
            if dll.GetVJDAxisMin(self.device_id, usage, ctypes.byref(low)) and dll.GetVJDAxisMax(self.device_id, usage, ctypes.byref(high)):
                self._axis_ranges[usage] = (low.value, high.value)
        self._button_count = max(0, int(dll.GetVJDButtonNumber(self.device_id)))

    @property
    def description(self) -> str:
        if self._dll is None:
            return "vJoy unavailable"
        status = int(self._dll.GetVJDStatus(self.device_id))
        return f"vJoy device {self.device_id}: {STATUS_NAMES.get(status, 'unknown')} · {len(self._axis_ranges)} axes · {self._button_count} buttons"

    def acquire(self) -> None:
        if self._dll is None:
            raise VJoyUnavailable("vJoy is unavailable.")
        status = int(self._dll.GetVJDStatus(self.device_id))
        if status != 1:  # VJD_STAT_FREE
            raise VJoyUnavailable(f"vJoy device {self.device_id} is {STATUS_NAMES.get(status, 'not available')}.")
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
        if not self._acquired or bounds is None:
            return
        low, high = bounds
        value = round(low + ((self._clamp(normalized, -1.0, 1.0) + 1.0) * 0.5 * (high - low)))
        self._dll.SetAxis(value, self.device_id, usage)

    def apply(self, data: dict) -> None:
        if not self._acquired:
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
            self._dll.ResetVJD(self.device_id)
            self._dll.RelinquishVJD(self.device_id)
        self._acquired = False
