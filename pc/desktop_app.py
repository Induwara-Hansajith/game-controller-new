"""Pocket Controller's Windows desktop companion."""

from __future__ import annotations

import json
import asyncio
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


def run_receiver() -> None:
    import receiver

    asyncio.run(receiver.main())


class PocketControllerApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("Pocket Controller · PC")
        self.root.geometry("760x600")
        self.root.minsize(680, 540)
        self.root.configure(bg="#0b0910")
        self.receiver: subprocess.Popen | None = None
        self.closed = False
        self.address_var = tk.StringVar(value="Starting receiver…")
        self.pin_var = tk.StringVar(value="------")
        self.connection_var = tk.StringVar(value="Starting receiver")
        self.phone_var = tk.StringVar(value="Waiting for phone")
        self.vjoy_var = tk.StringVar(value="Checking vJoy…")
        self.message_var = tk.StringVar(value="Keep this window open while playing.")
        self.values = {key: tk.DoubleVar(value=0.0) for key in ("steer", "throttle", "brake")}
        self.button_labels: list[tk.Label] = []
        self._style()
        self._build()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.start_receiver()
        self.root.after(400, self.refresh)

    def _style(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#0b0910")
        style.configure("Card.TFrame", background="#17131f")
        style.configure("TLabel", background="#17131f", foreground="#f3eff8", font=("Segoe UI", 10))
        style.configure("Muted.TLabel", background="#17131f", foreground="#aaa0b6", font=("Segoe UI", 9))
        style.configure("Title.TLabel", background="#0b0910", foreground="#f3eff8", font=("Segoe UI Semibold", 22))
        style.configure("Eyebrow.TLabel", background="#17131f", foreground="#c4b5fd", font=("Segoe UI Semibold", 9))
        style.configure("Value.TLabel", background="#0e0c12", foreground="#f3eff8", font=("Segoe UI Semibold", 16))
        style.configure("Accent.TButton", background="#7545d8", foreground="white", padding=(14, 9), borderwidth=0)
        style.map("Accent.TButton", background=[("active", "#8b5cf6")])
        style.configure("TButton", padding=(10, 8))
        style.configure("Horizontal.TProgressbar", troughcolor="#28232f", background="#9b72f4", bordercolor="#28232f", lightcolor="#9b72f4", darkcolor="#7c3aed")

    def _build(self) -> None:
        outer = ttk.Frame(self.root, padding=(26, 22))
        outer.pack(fill="both", expand=True)
        header = ttk.Frame(outer)
        header.pack(fill="x", pady=(0, 18))
        ttk.Label(header, text="POCKET CONTROLLER", style="Eyebrow.TLabel").pack(anchor="w")
        ttk.Label(header, text="PC companion", style="Title.TLabel").pack(anchor="w", pady=(3, 0))

        connection = self._card(outer, "CONNECTION")
        line = ttk.Frame(connection, style="Card.TFrame")
        line.pack(fill="x")
        self.dot = tk.Label(line, text="●", fg="#f5c46a", bg="#17131f", font=("Segoe UI", 16))
        self.dot.pack(side="left", padx=(0, 9))
        ttk.Label(line, textvariable=self.connection_var, font=("Segoe UI Semibold", 15)).pack(side="left")
        ttk.Label(connection, textvariable=self.phone_var, style="Muted.TLabel").pack(anchor="w", pady=(5, 13))
        tiles = ttk.Frame(connection, style="Card.TFrame")
        tiles.pack(fill="x")
        tiles.columnconfigure((0, 1), weight=1)
        self._tile(tiles, "PC ADDRESS", self.address_var, 0)
        self._tile(tiles, "PAIRING PIN", self.pin_var, 1)

        device = self._card(outer, "VIRTUAL CONTROLLER")
        ttk.Label(device, textvariable=self.vjoy_var, wraplength=670).pack(anchor="w")
        ttk.Label(device, textvariable=self.message_var, style="Muted.TLabel", wraplength=670).pack(anchor="w", pady=(6, 0))
        ttk.Label(device, text="Steering → X   ·   Throttle → Y   ·   Brake → Z   ·   Buttons 1–8", style="Muted.TLabel").pack(anchor="w", pady=(12, 0))

        live = self._card(outer, "LIVE INPUT")
        for key, label in (("steer", "Steering"), ("throttle", "Throttle"), ("brake", "Brake")):
            row = ttk.Frame(live, style="Card.TFrame")
            row.pack(fill="x", pady=6)
            ttk.Label(row, text=label, width=11).pack(side="left")
            bar = ttk.Progressbar(row, maximum=100, variable=self.values[key])
            bar.pack(side="left", fill="x", expand=True, padx=12)
            value = tk.Label(row, text="0%", fg="#f3eff8", bg="#17131f", width=6, anchor="e", font=("Segoe UI", 9))
            value.pack(side="right")
            setattr(self, key + "_label", value)
        buttons = ttk.Frame(live, style="Card.TFrame")
        buttons.pack(fill="x", pady=(8, 0))
        for index in range(8):
            label = tk.Label(buttons, text=str(index + 1), bg="#0f0d13", fg="#8e8797", padx=12, pady=6,
                             font=("Segoe UI Semibold", 9), highlightthickness=1, highlightbackground="#40384a")
            label.pack(side="left", padx=(0, 8))
            self.button_labels.append(label)

        actions = ttk.Frame(outer)
        actions.pack(fill="x", pady=(16, 0))
        ttk.Button(actions, text="Open dashboard", command=lambda: webbrowser.open("http://127.0.0.1:8765")).pack(side="left")
        ttk.Button(actions, text="Restart receiver", style="Accent.TButton", command=self.restart_receiver).pack(side="right")

    def _card(self, parent: ttk.Frame, title: str) -> ttk.Frame:
        card = ttk.Frame(parent, style="Card.TFrame", padding=16)
        card.pack(fill="x", pady=(0, 12))
        ttk.Label(card, text=title, style="Eyebrow.TLabel").pack(anchor="w", pady=(0, 12))
        return card

    def _tile(self, parent: ttk.Frame, title: str, value: tk.StringVar, column: int) -> None:
        tile = tk.Frame(parent, bg="#0e0c12", padx=14, pady=10, highlightthickness=1, highlightbackground="#30283a")
        tile.grid(row=0, column=column, sticky="nsew", padx=(0 if column == 0 else 7, 7 if column == 0 else 0))
        tk.Label(tile, text=title, bg="#0e0c12", fg="#aaa0b6", font=("Segoe UI", 8, "bold")).pack(anchor="w")
        tk.Label(tile, textvariable=value, bg="#0e0c12", fg="#d8c7ff", font=("Segoe UI Semibold", 15)).pack(anchor="w", pady=(3, 0))

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
        self.connection_var.set("Restarting receiver")
        self.root.after(300, self.start_receiver)

    def stop_receiver(self) -> None:
        process = self.receiver
        self.receiver = None
        if process and process.poll() is None:
            process.terminate()
            threading.Thread(target=process.wait, kwargs={"timeout": 5}, daemon=True).start()

    def refresh(self) -> None:
        if self.closed:
            return
        self._load_status()
        self.root.after(500, self.refresh)

    def _load_status(self) -> None:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8765/status", timeout=0.35) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            process_running = self.receiver is not None and self.receiver.poll() is None
            self.connection_var.set("Starting receiver…" if process_running else "Receiver stopped")
            self.dot.configure(fg="#f5c46a" if process_running else "#e879a8")
            return
        self.address_var.set("   ·   ".join(data.get("addresses") or ["No LAN address found"]))
        self.pin_var.set(str(data.get("pin", "------")))
        connected = bool(data.get("connected"))
        self.connection_var.set("Phone connected" if connected else "Waiting for phone")
        self.phone_var.set(data.get("phone") or "Connect your phone to the same Wi-Fi and enter the address + PIN above.")
        self.dot.configure(fg="#56e0b2" if connected else "#aaa0b6")
        self.vjoy_var.set(str(data.get("vjoy", "vJoy status unavailable")))
        self.message_var.set(str(data.get("message", "")))
        steer = max(-1.0, min(1.0, float(data.get("steer", 0) or 0)))
        self.values["steer"].set(abs(steer) * 100)
        self.steer_label.configure(text=f"{round(steer * 100)}%")
        for key in ("throttle", "brake"):
            value = max(0.0, min(1.0, float(data.get(key, 0) or 0)))
            self.values[key].set(value * 100)
            getattr(self, key + "_label").configure(text=f"{round(value * 100)}%")
        for i, label in enumerate(self.button_labels):
            pressed = i < len(data.get("buttons", [])) and bool(data["buttons"][i])
            label.configure(bg="#673ab7" if pressed else "#0f0d13", fg="#ffffff" if pressed else "#8e8797",
                            highlightbackground="#9b72f4" if pressed else "#40384a")

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
