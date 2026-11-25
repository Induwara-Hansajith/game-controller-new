"""Pocket Controller's Windows desktop companion."""

from __future__ import annotations

import json
import asyncio
import math
import subprocess
import sys
import threading
import tkinter as tk
import urllib.error
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import ttk


APP_DIR = Path(__file__).resolve().parent
VERSION = "0.1.0-beta.1"


def run_receiver() -> None:
    import receiver

    asyncio.run(receiver.main())


class PocketControllerApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title(f"Pocket Controller · Cockpit v{VERSION}")
        self.root.geometry("820x680")
        self.root.minsize(740, 620)
        self.root.configure(bg="#07050b")

        self.receiver: subprocess.Popen | None = None
        self.closed = False

        self.address_var = tk.StringVar(value="Discovering LAN…")
        self.pin_var = tk.StringVar(value="------")
        self.connection_var = tk.StringVar(value="Starting receiver…")
        self.phone_var = tk.StringVar(value="Waiting for phone…")
        self.vjoy_var = tk.StringVar(value="Checking vJoy device 1…")
        self.message_var = tk.StringVar(value="Keep this window open while playing.")
        self.vjoy_badge_var = tk.StringVar(value="CHECKING")
        self.vjoy_badge_color = "#f59e0b"

        self.current_steer = 0.0
        self.current_throttle = 0.0
        self.current_brake = 0.0
        self.current_buttons = [False] * 8

        self.button_widgets: list[tk.Label] = []

        self._style()
        self._build()

        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.start_receiver()
        self.root.after(350, self.refresh)

    def _style(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#07050b")
        style.configure("Card.TFrame", background="#120e1c")
        style.configure("TLabel", background="#120e1c", foreground="#f1f5f9", font=("Segoe UI", 10))
        style.configure("Muted.TLabel", background="#120e1c", foreground="#94a3b8", font=("Segoe UI", 9))
        style.configure("Eyebrow.TLabel", background="#120e1c", foreground="#a78bfa", font=("Segoe UI Semibold", 9))
        style.configure("Accent.TButton", background="#7c3aed", foreground="white", font=("Segoe UI Semibold", 10), padding=(14, 8), borderwidth=0)
        style.map("Accent.TButton", background=[("active", "#8b5cf6")])
        style.configure("Outline.TButton", background="#1a1426", foreground="#cbd5e1", font=("Segoe UI", 9), padding=(10, 6), borderwidth=1)
        style.map("Outline.TButton", background=[("active", "#2a203d")])

    def _build(self) -> None:
        outer = tk.Frame(self.root, bg="#07050b", padx=24, pady=20)
        outer.pack(fill="both", expand=True)

        # 1. Top Header Row
        header = tk.Frame(outer, bg="#07050b")
        header.pack(fill="x", pady=(0, 16))

        title_box = tk.Frame(header, bg="#07050b")
        title_box.pack(side="left")
        tk.Label(title_box, text=f"POCKET CONTROLLER · v{VERSION}", fg="#a78bfa", bg="#07050b",
                 font=("Segoe UI Semibold", 9)).pack(anchor="w")
        tk.Label(title_box, text="PC Cockpit & Companion", fg="#f8fafc", bg="#07050b",
                 font=("Segoe UI", 20, "bold")).pack(anchor="w", pady=(2, 0))

        btn_box = tk.Frame(header, bg="#07050b")
        btn_box.pack(side="right", pady=(4, 0))
        ttk.Button(btn_box, text="⚡ Open Web Cockpit HUD", style="Accent.TButton",
                   command=lambda: webbrowser.open("http://127.0.0.1:8765")).pack(side="left", padx=(0, 8))
        ttk.Button(btn_box, text="↻ Restart Receiver", style="Outline.TButton",
                   command=self.restart_receiver).pack(side="left")

        # 2. Connection & Pairing Banner Card
        conn_card = self._card(outer, "NETWORK & PAIRING STATUS")
        top_line = tk.Frame(conn_card, bg="#120e1c")
        top_line.pack(fill="x", pady=(0, 10))

        self.dot = tk.Label(top_line, text="●", fg="#f59e0b", bg="#120e1c", font=("Segoe UI", 16))
        self.dot.pack(side="left", padx=(0, 8))
        self.conn_label = tk.Label(top_line, textvariable=self.connection_var, fg="#f8fafc", bg="#120e1c",
                                   font=("Segoe UI Semibold", 13))
        self.conn_label.pack(side="left")

        self.phone_label = tk.Label(conn_card, textvariable=self.phone_var, fg="#94a3b8", bg="#120e1c",
                                    font=("Segoe UI", 9))
        self.phone_label.pack(anchor="w", pady=(0, 12))

        # Two Tiles: IP and PIN
        tiles = tk.Frame(conn_card, bg="#120e1c")
        tiles.pack(fill="x")
        tiles.columnconfigure((0, 1), weight=1)

        self._tile(tiles, "PC WI-FI IP (LAN)", self.address_var, 0, is_ip=True)
        self._tile(tiles, "PAIRING PIN (6 DIGIT)", self.pin_var, 1, is_pin=True)

        # 3. Cockpit HUD Telemetry Card (Canvas-driven)
        hud_card = self._card(outer, "LIVE COCKPIT TELEMETRY")
        hud_grid = tk.Frame(hud_card, bg="#120e1c")
        hud_grid.pack(fill="both", expand=True)

        # Left: Rotating Steering Wheel Canvas
        wheel_frame = tk.Frame(hud_grid, bg="#120e1c", padx=10)
        wheel_frame.pack(side="left", fill="both")

        self.wheel_canvas = tk.Canvas(wheel_frame, width=220, height=210, bg="#120e1c", highlightthickness=0)
        self.wheel_canvas.pack()
        self.steer_deg_label = tk.Label(wheel_frame, text="0°", fg="#ffffff", bg="#120e1c",
                                        font=("Segoe UI", 15, "bold"))
        self.steer_deg_label.pack()
        tk.Label(wheel_frame, text="STEERING (X AXIS)", fg="#a78bfa", bg="#120e1c",
                 font=("Segoe UI", 8, "bold")).pack()

        # Right: Pedals and Buttons
        right_panel = tk.Frame(hud_grid, bg="#120e1c", padx=16)
        right_panel.pack(side="left", fill="both", expand=True)

        # Throttle Meter
        self.throttle_bar = self._build_meter(right_panel, "THROTTLE (Y AXIS)", "#10b981", "#06b6d4")
        # Brake Meter
        self.brake_bar = self._build_meter(right_panel, "BRAKE (Z AXIS)", "#f43f5e", "#fb7185")

        # Buttons Matrix
        tk.Label(right_panel, text="CONTROLLER INPUTS", fg="#a78bfa", bg="#120e1c",
                 font=("Segoe UI", 8, "bold")).pack(anchor="w", pady=(12, 6))

        btn_row = tk.Frame(right_panel, bg="#120e1c")
        btn_row.pack(fill="x")
        btn_names = ["A", "B", "X", "Y", "LB", "RB", "LT", "RT"]
        for idx, name in enumerate(btn_names):
            box = tk.Label(btn_row, text=f"{name}\n{idx+1}", bg="#181324", fg="#94a3b8", width=5, pady=4,
                           font=("Segoe UI Semibold", 8), highlightthickness=1, highlightbackground="#2e253e")
            box.pack(side="left", padx=(0, 6))
            self.button_widgets.append(box)

        # 4. Virtual Device Card
        vjoy_card = self._card(outer, "VIRTUAL CONTROLLER & UDP BEACON")
        vjoy_row = tk.Frame(vjoy_card, bg="#120e1c")
        vjoy_row.pack(fill="x")

        self.vjoy_badge = tk.Label(vjoy_row, textvariable=self.vjoy_badge_var, bg="#1a1426", fg="#10b981",
                                   padx=8, pady=2, font=("Segoe UI", 8, "bold"))
        self.vjoy_badge.pack(side="left", padx=(0, 10))

        tk.Label(vjoy_row, textvariable=self.vjoy_var, fg="#f1f5f9", bg="#120e1c",
                 font=("Segoe UI", 9)).pack(side="left")

        sub_info = tk.Frame(vjoy_card, bg="#120e1c")
        sub_info.pack(fill="x", pady=(6, 0))
        tk.Label(sub_info, text="UDP Beacon: Broadcasting on port 8767 for automatic phone discovery",
                 fg="#64748b", bg="#120e1c", font=("Segoe UI", 8)).pack(anchor="w")

        self._draw_wheel(0.0)

    def _card(self, parent: tk.Widget, title: str) -> tk.Frame:
        card = tk.Frame(parent, bg="#120e1c", padx=18, pady=14, highlightthickness=1, highlightbackground="#241b36")
        card.pack(fill="x", pady=(0, 12))
        tk.Label(card, text=title, fg="#a78bfa", bg="#120e1c", font=("Segoe UI Semibold", 8)).pack(anchor="w", pady=(0, 8))
        return card

    def _tile(self, parent: tk.Widget, title: str, var: tk.StringVar, col: int, is_ip: bool = False, is_pin: bool = False) -> None:
        tile = tk.Frame(parent, bg="#181324", padx=14, pady=10, highlightthickness=1, highlightbackground="#2e253e")
        tile.grid(row=0, column=col, sticky="nsew", padx=(0 if col == 0 else 6, 6 if col == 0 else 0))

        head_row = tk.Frame(tile, bg="#181324")
        head_row.pack(fill="x")
        tk.Label(head_row, text=title, fg="#94a3b8", bg="#181324", font=("Segoe UI", 8, "bold")).pack(side="left")

        copy_btn = tk.Label(head_row, text="COPY", fg="#c084fc", bg="#221935", padx=6, pady=1,
                            font=("Segoe UI", 7, "bold"), cursor="hand2")
        copy_btn.pack(side="right")
        copy_btn.bind("<Button-1>", lambda e, v=var: self._copy_to_clipboard(v.get(), copy_btn))

        val_font = ("Consolas", 15, "bold") if is_pin else ("Segoe UI Semibold", 13)
        val_color = "#d8b4fe" if is_pin else "#f8fafc"
        tk.Label(tile, textvariable=var, fg=val_color, bg="#181324", font=val_font).pack(anchor="w", pady=(4, 0))

    def _copy_to_clipboard(self, text: str, btn: tk.Label) -> None:
        clean = text.split(" · ")[0].strip()
        self.root.clipboard_clear()
        self.root.clipboard_append(clean)
        btn.configure(text="COPIED!", fg="#34d399")
        self.root.after(1200, lambda: btn.configure(text="COPY", fg="#c084fc"))

    def _build_meter(self, parent: tk.Widget, label: str, c1: str, c2: str) -> dict:
        row = tk.Frame(parent, bg="#120e1c")
        row.pack(fill="x", pady=4)

        head = tk.Frame(row, bg="#120e1c")
        head.pack(fill="x")
        tk.Label(head, text=label, fg=c1, bg="#120e1c", font=("Segoe UI", 8, "bold")).pack(side="left")
        val_lbl = tk.Label(head, text="0%", fg="#ffffff", bg="#181324", font=("Consolas", 9, "bold"), padx=6)
        val_lbl.pack(side="right")

        canvas = tk.Canvas(row, height=12, bg="#1a1426", highlightthickness=1, highlightbackground="#2e253e")
        canvas.pack(fill="x", pady=(3, 0))

        return {"canvas": canvas, "label": val_lbl, "color": c1}

    def _update_meter(self, meter: dict, pct: float) -> None:
        pct = max(0.0, min(1.0, pct))
        c = meter["canvas"]
        c.delete("fill")
        w = c.winfo_width()
        if w > 1:
            fill_w = w * pct
            c.create_rectangle(0, 0, fill_w, 12, fill=meter["color"], outline="", tags="fill")
        meter["label"].configure(text=f"{round(pct * 100)}%")

    def _draw_wheel(self, steer: float) -> None:
        c = self.wheel_canvas
        c.delete("all")

        cx, cy, r = 110, 105, 78

        # Arc track (-45 to +45 deg)
        c.create_arc(cx - r - 8, cy - r - 8, cx + r + 8, cy + r + 8,
                     start=45, extent=90, style="arc", outline="#292038", width=4)

        # Rotate wheel angle
        deg = steer * 45.0
        rad = math.radians(deg)

        # Rim
        c.create_oval(cx - r, cy - r, cx + r, cy + r, outline="#1f182c", width=12)
        c.create_oval(cx - r + 6, cy - r + 6, cx + r - 6, cy + r - 6,
                      outline="#a855f7" if abs(steer) > 0.05 else "#3b2c52", width=1.5)

        # Top Amber Stripe
        top_rad = math.radians(deg - 90)
        sx = cx + (r + 1) * math.cos(top_rad)
        sy = cy + (r + 1) * math.sin(top_rad)
        c.create_oval(sx - 5, sy - 5, sx + 5, sy + 5, fill="#f59e0b", outline="#fbbf24")

        # Spokes
        spoke_len = r * 0.72
        # Horizontal spoke
        c.create_line(cx - spoke_len * math.cos(rad), cy - spoke_len * math.sin(rad),
                      cx + spoke_len * math.cos(rad), cy + spoke_len * math.sin(rad),
                      fill="#2a203c", width=6)
        # Vertical bottom spoke
        v_rad = math.radians(deg + 90)
        c.create_line(cx, cy, cx + spoke_len * math.cos(v_rad), cy + spoke_len * math.sin(v_rad),
                      fill="#2a203c", width=6)

        # Center Hub
        c.create_oval(cx - 24, cy - 24, cx + 24, cy + 24, fill="#120e1c", outline="#8b5cf6", width=2)
        c.create_text(cx, cy, text="⌁", fill="#c084fc", font=("Segoe UI", 13, "bold"))

        # Update angle readout
        angle_int = round(deg)
        self.steer_deg_label.configure(text=f"{angle_int:+d}°" if angle_int != 0 else "0°")

    def start_receiver(self) -> None:
        if self.receiver and self.receiver.poll() is None:
            return
        if getattr(sys, "frozen", False):
            command = [sys.executable, "--receiver"]
        else:
            command = [sys.executable, str(Path(__file__).resolve()), "--receiver"]
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            self.receiver = subprocess.Popen(command, cwd=str(APP_DIR), creationflags=flags,
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError as exc:
            self.connection_var.set("Could not start receiver")
            self.message_var.set(str(exc))

    def restart_receiver(self) -> None:
        self.stop_receiver()
        self.address_var.set("Restarting…")
        self.pin_var.set("------")
        self.connection_var.set("Restarting receiver…")
        self.dot.configure(fg="#f59e0b")
        self.root.after(300, self.start_receiver)

    def stop_receiver(self) -> None:
        process = self.receiver
        self.receiver = None
        if process and process.poll() is None:
            try:
                urllib.request.urlopen("http://127.0.0.1:8765/shutdown", timeout=0.5)
            except Exception:
                pass
            try:
                process.wait(timeout=1.2)
            except (subprocess.TimeoutExpired, OSError):
                process.terminate()
                threading.Thread(target=process.wait, kwargs={"timeout": 5}, daemon=True).start()

    def refresh(self) -> None:
        if self.closed:
            return
        self._load_status()
        self.root.after(150, self.refresh)

    def _load_status(self) -> None:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8765/status", timeout=0.25) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            process_running = self.receiver is not None and self.receiver.poll() is None
            self.connection_var.set("Starting receiver…" if process_running else "Receiver stopped")
            self.dot.configure(fg="#f59e0b" if process_running else "#e11d48")
            return

        addresses = data.get("addresses") or []
        self.address_var.set("   ·   ".join(addresses) if addresses else "127.0.0.1")
        self.pin_var.set(str(data.get("pin", "------")))

        connected = bool(data.get("connected"))
        self.connection_var.set("Controller Connected & Active" if connected else "Waiting for Phone Connection")
        self.dot.configure(fg="#10b981" if connected else "#f59e0b")

        phone_name = data.get("phone") or ""
        msg = data.get("message") or ""
        if connected:
            self.phone_var.set(f"Phone: {phone_name}   ·   {msg}")
        else:
            self.phone_var.set("Connect phone to same Wi-Fi and open Pocket Controller app (auto-discovery active).")

        vjoy_str = str(data.get("vjoy", "vJoy unavailable"))
        self.vjoy_var.set(vjoy_str)
        is_vjoy_ok = "owned" in vjoy_str or "available" in vjoy_str
        self.vjoy_badge_var.set("VJOY ONLINE" if is_vjoy_ok else "VJOY OFFLINE")
        self.vjoy_badge.configure(fg="#10b981" if is_vjoy_ok else "#f43f5e",
                                  bg="#0f291e" if is_vjoy_ok else "#2d1219")

        # Telemetry
        steer = max(-1.0, min(1.0, float(data.get("steer", 0) or 0)))
        throttle = max(0.0, min(1.0, float(data.get("throttle", 0) or 0)))
        brake = max(0.0, min(1.0, float(data.get("brake", 0) or 0)))

        self._draw_wheel(steer)
        self._update_meter(self.throttle_bar, throttle)
        self._update_meter(self.brake_bar, brake)

        buttons = data.get("buttons") or []
        for i, box in enumerate(self.button_widgets):
            pressed = i < len(buttons) and bool(buttons[i])
            box.configure(bg="#8b5cf6" if pressed else "#181324",
                          fg="#ffffff" if pressed else "#94a3b8",
                          highlightbackground="#ffffff" if pressed else "#2e253e")

    def close(self) -> None:
        self.closed = True
        self.stop_receiver()
        self.root.destroy()


if __name__ == "__main__":
    if "--receiver" in sys.argv:
        run_receiver()
    else:
        window = tk.Tk()
        PocketControllerApp(window)
        window.mainloop()
