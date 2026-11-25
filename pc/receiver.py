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

from controller import VJoyDevice, VJoyUnavailable


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
VERSION = "0.1.0-beta.1"

state_lock = threading.Lock()
shutdown_event = threading.Event()
active_writer: asyncio.StreamWriter | None = None

state = {
    "version": VERSION,
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


def update_state(**values: object) -> None:
    with state_lock:
        state.update(values)


def snapshot() -> dict:
    with state_lock:
        result = dict(state)
    result.pop("last_packet", None)
    return result


def local_ipv4_addresses() -> list[str]:
    """Finds valid local LAN IPv4 addresses, putting the primary LAN interface first."""
    found: list[str] = []
    primary: str | None = None

    # Probe primary outbound route (does not transmit data)
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        primary = probe.getsockname()[0]
        probe.close()
    except OSError:
        pass

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

    if primary and primary in found:
        found.remove(primary)
        found.insert(0, primary)
    elif primary and not primary.startswith("127.") and not primary.startswith("169.254."):
        found.insert(0, primary)

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
        else:
            self.send_error(404)

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
    global active_writer
    peer = writer.get_extra_info("peername")
    phone_name = "Phone"
    acquired = False
    authenticated = False

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

        # If a phone reconnects with matching PIN while a previous socket is hanging, close old socket
        if active_writer is not None and active_writer is not writer:
            old_writer = active_writer
            active_writer = None
            try:
                old_writer.close()
            except Exception:
                pass

        phone_name = str(hello.get("name", "Phone"))[:48] or "Phone"
        try:
            device.acquire()
            acquired = True
        except VJoyUnavailable as exc:
            # Keep the live dashboard and preview usable even if vJoy was busy
            update_state(vjoy=str(exc), message="Connected for preview; vJoy is unavailable")

        active_writer = writer
        authenticated = True
        current_vjoy = device.description if acquired else snapshot()["vjoy"]
        update_state(
            connected=True,
            phone=phone_name,
            message=f"Connected from {peer[0] if peer else 'phone'}",
            vjoy=current_vjoy,
            last_packet=time.monotonic(),
        )
        await send_json(writer, {"type": "ready", "ok": True, "vjoy": current_vjoy})

        while not shutdown_event.is_set():
            try:
                raw = await asyncio.wait_for(reader.readline(), timeout=0.25)
            except asyncio.TimeoutError:
                with state_lock:
                    stale = time.monotonic() - float(state["last_packet"]) > 0.35
                if stale and acquired:
                    device.neutral()
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
                vjoy=device.description,
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
            await asyncio.wait_for(writer.wait_closed(), timeout=0.5)
        except (OSError, asyncio.TimeoutError):
            pass


async def broadcast_presence() -> None:
    """Broadcasts PC presence on UDP port 8767 so phones can auto-discover it without typing IPs."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setblocking(False)

    try:
        while not shutdown_event.is_set():
            if not state.get("connected"):
                addrs = local_ipv4_addresses()
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
    device = VJoyDevice()
    vjoy_status = device.description
    addresses = local_ipv4_addresses()
    update_state(vjoy=vjoy_status, addresses=addresses)

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
    print("PC addresses: " + (", ".join(addresses) or "No LAN IPv4 address found"))
    print(f"vJoy: {vjoy_status}")
    print("Press Ctrl+C to stop.")

    try:
        async with server:
            shutdown_watcher = asyncio.create_task(check_shutdown(server))
            bcast_task = asyncio.create_task(broadcast_presence())
            await server.serve_forever()
    except (asyncio.CancelledError, KeyboardInterrupt):
        pass
    finally:
        dashboard.shutdown()
        device.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Receiver stopped.")
