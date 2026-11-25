"""Windows receiver for Pocket Controller (stdlib-only network layer)."""

from __future__ import annotations

import asyncio
import json
import secrets
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from controller import ControllerDevice, ControllerUnavailable, VJoyUnavailable


if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
    ROOT = Path(sys._MEIPASS)
else:
    ROOT = Path(__file__).resolve().parent

WEB = ROOT / "web" / "index.html"
HTTP_HOST, HTTP_PORT = "127.0.0.1", 8765
INPUT_HOST, INPUT_PORT = "0.0.0.0", 8766
DISCOVERY_PORT = 8767
MAX_LINE_BYTES = 4096
PAIRING_PIN = f"{secrets.randbelow(1_000_000):06d}"
VERSION = "0.1.0-beta.3"

state_lock = threading.Lock()
shutdown_event = threading.Event()
active_writer: asyncio.StreamWriter | None = None
phone_writers: set[asyncio.StreamWriter] = set()
phone_handlers: set[asyncio.Task] = set()
controller_device: ControllerDevice | None = None

state = {
    "version": VERSION,
    "connected": False,
    "phone": "",
    "pin": PAIRING_PIN,
    "addresses": [],
    "vjoy": "Checking…",
    "driver": "auto",
    "active_driver": "",
    "drivers": {},
    "controller_mode": "racepad",
    "driver_error": "",
    "message": "Waiting for phone",
    "steer": 0.0,
    "steer_deg": 0.0,
    "throttle": 0.0,
    "brake": 0.0,
    "clutch": 0.0,
    "handbrake": 0.0,
    "lx": 0.0,
    "ly": 0.0,
    "rx": 0.0,
    "ry": 0.0,
    "lt": 0.0,
    "rt": 0.0,
    "dpad": {},
    "buttons": [],
    "last_packet": 0.0,
}


def update_state(**values: object) -> None:
    with state_lock:
        state.update(values)


def snapshot() -> dict:
    with state_lock:
        result = dict(state)
    result.pop("last_packet", None)
    return result


def refresh_driver_state(device: ControllerDevice, **values: object) -> None:
    update_state(
        vjoy=device.description,
        driver=device.selected_driver,
        active_driver=device.active_driver or "",
        drivers=device.drivers,
        **values,
    )


def local_ipv4_addresses() -> list[str]:
    """Finds valid local LAN IPv4 addresses, putting the primary LAN interface first."""
    found: list[str] = []
    primary: str | None = None

    # Probe primary outbound route (does not transmit data)
    probe = None
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        primary = probe.getsockname()[0]
    except OSError:
        pass
    finally:
        if probe is not None:
            probe.close()

    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET, socket.SOCK_STREAM):
            address = item[4][0]
            # Ignore loopback and APIPA link-local addresses (169.254.x.x)
            if address.startswith("127.") or address.startswith("169.254.") or address == "0.0.0.0":
                continue
            if address not in found:
                found.append(address)
    except OSError:
        pass

    def ip_priority(ip: str) -> int:
        if ip.startswith("192.168."):
            return 0
        if ip.startswith("10."):
            return 1
        if ip.startswith("172."):
            return 2
        return 3

    found.sort(key=ip_priority)

    if primary and not primary.startswith("127.") and not primary.startswith("169.254."):
        if found and ip_priority(primary) <= ip_priority(found[0]):
            if primary in found:
                found.remove(primary)
            found.insert(0, primary)
        elif primary not in found:
            found.append(primary)

    return found


class DashboardHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/status":
            body = json.dumps(snapshot()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/shutdown":
            body = b'{"ok":true,"message":"Shutting down receiver"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            shutdown_event.set()
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
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        if self.path == "/shutdown":
            self.do_GET()
        elif self.path == "/mode":
            self._set_driver()
        else:
            self.send_error(404)

    def _json_response(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _set_driver(self) -> None:
        if controller_device is None:
            self._json_response(503, {"ok": False, "error": "Controller backend is not ready."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 1024:
                raise ValueError("Expected a small JSON body with a driver field.")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            driver = payload.get("driver") if isinstance(payload, dict) else None
            controller_device.set_driver(str(driver or ""))
        except (ValueError, TypeError, json.JSONDecodeError, UnicodeDecodeError) as exc:
            self._json_response(400, {"ok": False, "error": str(exc)})
            return
        except (ControllerUnavailable, VJoyUnavailable) as exc:
            self._json_response(409, {"ok": False, "error": str(exc)})
            return
        refresh_driver_state(
            controller_device,
            steer=0.0, throttle=0.0, brake=0.0, clutch=0.0, handbrake=0.0,
            lx=0.0, ly=0.0, rx=0.0, ry=0.0, lt=0.0, rt=0.0,
            dpad={}, buttons=[], driver_error="",
        )
        self._json_response(200, {"ok": True, "driver": controller_device.selected_driver})

    def log_message(self, _format: str, *_args: object) -> None:
        return


def start_dashboard() -> ThreadingHTTPServer:
    ThreadingHTTPServer.allow_reuse_address = True
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


async def serve_phone(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, device: ControllerDevice) -> None:
    global active_writer
    peer = writer.get_extra_info("peername")
    phone_name = "Phone"
    authenticated = False
    stale_applied = False
    task = asyncio.current_task()
    phone_writers.add(writer)
    if task is not None:
        phone_handlers.add(task)

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

        # Claim the new session before closing the old socket. Its finally block
        # must not release vJoy or overwrite state after this writer takes ownership.
        old_writer = active_writer if active_writer is not writer else None
        active_writer = writer
        authenticated = True
        if old_writer is not None:
            try:
                old_writer.close()
            except Exception:
                pass

        phone_name = str(hello.get("name", "Phone"))[:48] or "Phone"
        current_vjoy = device.description
        update_state(
            connected=True,
            phone=phone_name,
            message=f"Connected from {peer[0] if peer else 'phone'}",
            vjoy=current_vjoy,
            driver=device.selected_driver,
            active_driver=device.active_driver or "",
            drivers=device.drivers,
            driver_error="",
            last_packet=time.monotonic(),
        )
        await send_json(writer, {"type": "ready", "ok": True, "vjoy": current_vjoy})

        while not shutdown_event.is_set():
            try:
                raw = await asyncio.wait_for(reader.readline(), timeout=0.25)
            except asyncio.TimeoutError:
                with state_lock:
                    stale = time.monotonic() - float(state["last_packet"]) > 0.35
                if stale and not stale_applied:
                    device.neutral()
                    refresh_driver_state(
                        device,
                        steer=0.0, steer_deg=0.0, throttle=0.0, brake=0.0, clutch=0.0, handbrake=0.0,
                        lx=0.0, ly=0.0, rx=0.0, ry=0.0, lt=0.0, rt=0.0,
                        dpad={}, buttons=[], driver_error="",
                    )
                    stale_applied = True
                continue

            if not raw or len(raw) > MAX_LINE_BYTES:
                break

            try:
                packet = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            if not isinstance(packet, dict) or packet.get("type") != "input":
                continue

            buttons_value = packet.get("buttons", [])
            if not isinstance(buttons_value, list):
                buttons_value = []
            mode = str(packet.get("mode", "racepad")).strip().lower()
            if mode not in ("gamepad", "racepad"):
                mode = "racepad"
            dpad_value = packet.get("dpad", {})
            if not isinstance(dpad_value, (dict, str)):
                dpad_value = {}
            buttons = [bool(value) for value in buttons_value[:16]]
            values = {
                "controller_mode": mode,
                "steer": clamp(packet.get("steer", 0.0), -1.0, 1.0),
                "steer_deg": clamp(packet["steer_deg"], -360.0, 360.0) if "steer_deg" in packet else None,
                "throttle": clamp(packet.get("throttle"), 0.0, 1.0),
                "brake": clamp(packet.get("brake"), 0.0, 1.0),
                "clutch": clamp(packet.get("clutch"), 0.0, 1.0),
                "handbrake": clamp(packet.get("handbrake"), 0.0, 1.0),
                "lx": clamp(packet.get("lx", 0.0), -1.0, 1.0),
                "ly": clamp(packet.get("ly", 0.0), -1.0, 1.0),
                "rx": clamp(packet.get("rx", 0.0), -1.0, 1.0),
                "ry": clamp(packet.get("ry", 0.0), -1.0, 1.0),
                "lt": clamp(packet.get("lt"), 0.0, 1.0),
                "rt": clamp(packet.get("rt"), 0.0, 1.0),
                "dpad": dpad_value,
                "buttons": buttons,
                "last_packet": time.monotonic(),
            }
            try:
                device.apply({**values, "mode": mode})
                values["driver_error"] = ""
            except Exception as exc:
                values["driver_error"] = str(exc)
            values["driver"] = device.selected_driver
            values["active_driver"] = device.active_driver or ""
            values["drivers"] = device.drivers
            values["vjoy"] = device.description if not values["driver_error"] else str(values["driver_error"])
            update_state(**values)
            stale_applied = False
    except (asyncio.TimeoutError, ConnectionError, OSError):
        pass
    finally:
        if authenticated and active_writer is writer:
            device.close()
            active_writer = None
            refresh_driver_state(
                device,
                connected=False,
                phone="",
                message="Waiting for phone",
                steer=0.0,
                steer_deg=0.0,
                throttle=0.0,
                brake=0.0,
                clutch=0.0,
                handbrake=0.0,
                lx=0.0,
                ly=0.0,
                rx=0.0,
                ry=0.0,
                lt=0.0,
                rt=0.0,
                dpad={},
                buttons=[],
                driver_error="",
                controller_mode="racepad",
                last_packet=0.0,
            )
        writer.close()
        try:
            await asyncio.wait_for(writer.wait_closed(), timeout=0.5)
        except (OSError, asyncio.TimeoutError):
            pass
        finally:
            phone_writers.discard(writer)
            if task is not None:
                phone_handlers.discard(task)


async def broadcast_presence() -> None:
    """Broadcasts PC presence on UDP port 8767 so phones can auto-discover it without typing IPs."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setblocking(False)

    try:
        while not shutdown_event.is_set():
            current = snapshot()
            addrs = local_ipv4_addresses()
            if addrs != current.get("addresses"):
                update_state(addresses=addrs)
            if not current.get("connected"):
                beacon = {
                    "service": "pocket-controller",
                    "version": VERSION,
                    "hostname": socket.gethostname(),
                    "addresses": addrs,
                    "port": INPUT_PORT,
                    "pin": PAIRING_PIN,
                }
                payload = json.dumps(beacon, separators=(",", ":")).encode("utf-8")
                for target in ("255.255.255.255", "<broadcast>"):
                    try:
                        sock.sendto(payload, (target, DISCOVERY_PORT))
                    except OSError:
                        pass
                for addr in addrs:
                    parts = addr.split(".")
                    if len(parts) == 4:
                        subnet_bcast = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                        try:
                            sock.sendto(payload, (subnet_bcast, DISCOVERY_PORT))
                        except OSError:
                            pass
            await asyncio.sleep(1.5)
    except asyncio.CancelledError:
        pass
    finally:
        sock.close()


async def check_shutdown(server: asyncio.Server) -> None:
    while not shutdown_event.is_set():
        await asyncio.sleep(0.5)
    server.close()
    await server.wait_closed()


async def main() -> None:
    global controller_device
    device = ControllerDevice()
    controller_device = device
    vjoy_status = device.description
    addresses = local_ipv4_addresses()
    refresh_driver_state(device, addresses=addresses)

    dashboard = start_dashboard()
    try:
        server = await asyncio.start_server(lambda r, w: serve_phone(r, w, device), INPUT_HOST, INPUT_PORT)
    except OSError as exc:
        dashboard.shutdown()
        dashboard.server_close()
        raise SystemExit(f"Could not listen on port {INPUT_PORT}: {exc}") from exc

    print("Pocket Controller receiver is ready.")
    print(f"Dashboard: http://{HTTP_HOST}:{HTTP_PORT}")
    print(f"Phone port: {INPUT_PORT}")
    print(f"Pairing PIN: {PAIRING_PIN}")
    print("PC addresses: " + (", ".join(addresses) or "No LAN IPv4 address found"))
    print(f"Controller output: {vjoy_status}")
    print("Press Ctrl+C to stop.")

    shutdown_watcher = None
    bcast_task = None
    try:
        async with server:
            shutdown_watcher = asyncio.create_task(check_shutdown(server))
            bcast_task = asyncio.create_task(broadcast_presence())
            await server.serve_forever()
    except (asyncio.CancelledError, KeyboardInterrupt):
        pass
    finally:
        shutdown_event.set()
        server.close()
        await server.wait_closed()
        for connection_writer in tuple(phone_writers):
            connection_writer.close()
        background_tasks = [task for task in (shutdown_watcher, bcast_task) if task is not None]
        for task in background_tasks:
            task.cancel()
        if background_tasks:
            await asyncio.gather(*background_tasks, return_exceptions=True)
        if phone_handlers:
            try:
                await asyncio.wait_for(
                    asyncio.gather(*tuple(phone_handlers), return_exceptions=True), timeout=1.5
                )
            except asyncio.TimeoutError:
                pass
        device.close()
        refresh_driver_state(
            device,
            connected=False, phone="", steer=0.0, throttle=0.0, brake=0.0,
            clutch=0.0, handbrake=0.0, lx=0.0, ly=0.0, rx=0.0, ry=0.0,
            lt=0.0, rt=0.0, dpad={}, buttons=[], driver_error="",
        )
        dashboard.shutdown()
        dashboard.server_close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Receiver stopped.")
