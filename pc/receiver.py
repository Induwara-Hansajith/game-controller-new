"""Windows receiver for Pocket Controller (stdlib-only network layer)."""

from __future__ import annotations

import asyncio
import json
import secrets
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from controller import VJoyDevice, VJoyUnavailable


ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web" / "index.html"
HTTP_HOST, HTTP_PORT = "127.0.0.1", 8765
INPUT_HOST, INPUT_PORT = "0.0.0.0", 8766
MAX_LINE_BYTES = 4096
PAIRING_PIN = f"{secrets.randbelow(1_000_000):06d}"

state_lock = threading.Lock()
state = {
    "connected": False,
    "phone": "",
    "pin": PAIRING_PIN,
    "addresses": [],
    "vjoy": "Checking…",
    "message": "Waiting for phone",
    "steer": 0.0,
    "throttle": 0.0,
    "brake": 0.0,
    "buttons": [],
    "last_packet": 0.0,
}
active_writer: asyncio.StreamWriter | None = None


class UnavailableDevice:
    def __init__(self, reason: str) -> None:
        self.reason = reason
        self.description = reason

    def acquire(self) -> None:
        raise VJoyUnavailable(self.reason)

    def apply(self, _data: dict) -> None:
        return

    def neutral(self) -> None:
        return

    def close(self) -> None:
        return


def update_state(**values: object) -> None:
    with state_lock:
        state.update(values)


def snapshot() -> dict:
    with state_lock:
        result = dict(state)
    result.pop("last_packet", None)
    return result


def local_ipv4_addresses() -> list[str]:
    found: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET, socket.SOCK_STREAM):
            address = item[4][0]
            if not address.startswith("127."):
                found.add(address)
    except OSError:
        pass
    return sorted(found)


class DashboardHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path == "/status":
            body = json.dumps(snapshot()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
        elif self.path in ("/", "/index.html"):
            try:
                body = WEB.read_bytes()
            except OSError:
                body = b"Dashboard file is missing. Restore pc/web/index.html."
                self.send_response(500)
            else:
                self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
        else:
            self.send_error(404)
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def start_dashboard() -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((HTTP_HOST, HTTP_PORT), DashboardHandler)
    threading.Thread(target=server.serve_forever, name="controller-dashboard", daemon=True).start()
    return server


def clamp(value: object, low: float, high: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return low
    if number != number or number in (float("inf"), float("-inf")):
        return low
    return max(low, min(high, number))


async def send_json(writer: asyncio.StreamWriter, data: dict) -> None:
    writer.write((json.dumps(data, separators=(",", ":")) + "\n").encode("utf-8"))
    await writer.drain()


async def serve_phone(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, device: VJoyDevice) -> None:
    peer = writer.get_extra_info("peername")
    phone_name = "Phone"
    acquired = False
    authenticated = False
    global active_writer
    try:
        raw = await asyncio.wait_for(reader.readline(), timeout=8)
        if not raw or len(raw) > MAX_LINE_BYTES:
            return
        try:
            hello = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            await send_json(writer, {"type": "error", "message": "Invalid pairing message."})
            return
        if not isinstance(hello, dict):
            await send_json(writer, {"type": "error", "message": "Invalid pairing message."})
            return
        if hello.get("type") != "hello" or str(hello.get("pin", "")) != PAIRING_PIN:
            await send_json(writer, {"type": "error", "message": "Pairing PIN did not match."})
            return
        if active_writer is not None and not active_writer.is_closing():
            await send_json(writer, {"type": "error", "message": "Another phone is already connected."})
            return

        phone_name = str(hello.get("name", "Phone"))[:48] or "Phone"
        try:
            device.acquire()
            acquired = True
        except VJoyUnavailable as exc:
            # Keep the live dashboard usable even if another application owns vJoy.
            update_state(vjoy=str(exc), message="Connected for preview; vJoy is unavailable")

        active_writer = writer
        authenticated = True
        update_state(
            connected=True,
            phone=phone_name,
            message=f"Connected from {peer[0] if peer else 'phone'}",
            vjoy=device.description if acquired else snapshot()["vjoy"],
            last_packet=time.monotonic(),
        )
        await send_json(writer, {"type": "ready", "ok": True, "vjoy": snapshot()["vjoy"]})

        while True:
            try:
                raw = await asyncio.wait_for(reader.readline(), timeout=0.25)
            except asyncio.TimeoutError:
                with state_lock:
                    stale = time.monotonic() - float(state["last_packet"]) > 0.35
                if stale and acquired:
                    device.neutral()
                continue
            if not raw:
                break
            if len(raw) > MAX_LINE_BYTES:
                break
            try:
                packet = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            if not isinstance(packet, dict):
                continue
            if packet.get("type") != "input":
                continue
            buttons_value = packet.get("buttons", [])
            if not isinstance(buttons_value, list):
                buttons_value = []
            buttons = [bool(value) for value in buttons_value[:8]]
            values = {
                "steer": clamp(packet.get("steer"), -1.0, 1.0),
                "throttle": clamp(packet.get("throttle"), 0.0, 1.0),
                "brake": clamp(packet.get("brake"), 0.0, 1.0),
                "buttons": buttons,
                "last_packet": time.monotonic(),
            }
            if acquired:
                device.apply(values)
            update_state(**values)
    except (asyncio.TimeoutError, ConnectionError, OSError):
        pass
    finally:
        if authenticated:
            if acquired:
                device.close()
            update_state(
                connected=False,
                phone="",
                message="Waiting for phone",
                steer=0.0,
                throttle=0.0,
                brake=0.0,
                buttons=[],
                last_packet=0.0,
            )
            if active_writer is writer:
                active_writer = None
        writer.close()
        try:
            await writer.wait_closed()
        except OSError:
            pass


async def main() -> None:
    try:
        device = VJoyDevice()
        vjoy_status = device.description
    except VJoyUnavailable as exc:
        device = UnavailableDevice(str(exc))
        vjoy_status = str(exc)
    update_state(vjoy=vjoy_status, addresses=local_ipv4_addresses())
    dashboard = start_dashboard()
    try:
        server = await asyncio.start_server(lambda r, w: serve_phone(r, w, device), INPUT_HOST, INPUT_PORT)
    except OSError as exc:
        dashboard.shutdown()
        raise SystemExit(f"Could not listen on port {INPUT_PORT}: {exc}") from exc

    print("Pocket Controller receiver is ready.")
    print(f"Dashboard: http://{HTTP_HOST}:{HTTP_PORT}")
    print(f"Phone port: {INPUT_PORT}")
    print(f"Pairing PIN: {PAIRING_PIN}")
    print("PC addresses: " + (", ".join(local_ipv4_addresses()) or "No LAN IPv4 address found"))
    print(f"vJoy: {vjoy_status}")
    print("Press Ctrl+C to stop.")
    try:
        async with server:
            await server.serve_forever()
    finally:
        dashboard.shutdown()
        device.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Receiver stopped.")
