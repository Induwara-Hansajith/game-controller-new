import UIKit

final class ControllerPadView: UIView {
    static let modeRacepad = 0
    static let modeGamepad = 1

    static let layoutRacepad = 0
    static let layoutGamepad = 1
    static let layoutRacepadGyro = 2

    private static let minScale: CGFloat = 0.55
    private static let maxScale: CGFloat = 2.0

    private enum Kind {
        case wheel, pedal, stickL, stickR, dpad, trigger, pillBtn, circleBtn
    }

    private final class ControlItem {
        let id: String
        let label: String
        let kind: Kind
        let buttonSlot: Int
        let accentColor: UIColor
        let altColor: UIColor?
        let defCxFrac: CGFloat
        let defCyFrac: CGFloat
        var cxFrac: CGFloat
        var cyFrac: CGFloat
        var scale: CGFloat = 1.0

        init(
            _ id: String, _ label: String, _ kind: Kind, _ buttonSlot: Int,
            _ accentColor: UIColor, _ altColor: UIColor?, _ cxFrac: CGFloat, _ cyFrac: CGFloat
        ) {
            self.id = id
            self.label = label
            self.kind = kind
            self.buttonSlot = buttonSlot
            self.accentColor = accentColor
            self.altColor = altColor
            self.defCxFrac = cxFrac
            self.defCyFrac = cyFrac
            self.cxFrac = cxFrac
            self.cyFrac = cyFrac
        }

        func reset() {
            cxFrac = defCxFrac
            cyFrac = defCyFrac
            scale = 1.0
        }

        var isModified: Bool {
            abs(cxFrac - defCxFrac) > 0.002 || abs(cyFrac - defCyFrac) > 0.002 || abs(scale - 1.0) > 0.01
        }
    }

    // Callbacks
    var onInputChanged: ((ControllerInputState) -> Void)?
    var onOpenSetup: (() -> Void)?
    var onToggleMode: (() -> Void)?
    var onCalibrate: (() -> Void)?
    var onLayoutChanged: ((_ layoutKey: String, _ json: String) -> Void)?

    private var currentMode = ControllerPadView.modeRacepad
    private var connected = false
    private var reconnecting = false
    private var pcDisplayName = "PC"
    private var latencyText = "250Hz"
    private var gyroEnabled = true
    private var instantTapPedals = false
    private var wheelMaxDeg: Float = 90

    private var raceItems: [ControlItem] = []
    private var raceGyroItems: [ControlItem] = []
    private var gpItems: [ControlItem] = []

    // Edit state
    private(set) var editMode = false
    private var snapToGrid = true
    private var selectedItem: ControlItem?
    private var editDragMode = 0 // 0=none, 1=move, 2=resizeHandle, 3=pinch
    private var editTouch: UITouch?
    private var dragOffset = CGPoint.zero
    private var resizeStartDist: CGFloat = 1
    private var resizeStartScale: CGFloat = 1
    private var pinchStartDist: CGFloat = 1
    private var pinchStartScale: CGFloat = 1
    private var guideCenterX = false
    private var guideCenterY = false

    // Active play state
    private var steer: Float = 0
    private var sensorSteer: Float = 0
    private var touchWheelDeg: Float = 0
    private var displayWheelDeg: Float = 0
    private var lastAnimTime: CFTimeInterval = 0
    private var displayLink: CADisplayLink?
    private var steerTouch: UITouch?
    private var leftStickTouch: UITouch?
    private var rightStickTouch: UITouch?
    private var activeTouches: [UITouch: CGPoint] = [:]
    private var blendingFromTouch = false
    private var lastTouchAngleDeg: Float = 0
    private var lastTouchX: CGFloat = 0
    private var hitFullLockHaptic = false
    private var steeringTouch = false
    private var leftStickTouched = false

    private var throttle: Float = 0
    private var brake: Float = 0
    private var clutch: Float = 0
    private var handbrake: Float = 0
    private var lx: Float = 0
    private var ly: Float = 0
    private var rx: Float = 0
    private var ry: Float = 0
    private var lt: Float = 0
    private var rt: Float = 0
    private var dpadUp = false
    private var dpadDown = false
    private var dpadLeft = false
    private var dpadRight = false
    private var buttons = Array(repeating: false, count: 16)

    private let hapticQueue = DispatchQueue(label: "com.pocketcontroller.haptics", qos: .userInitiated)
    private lazy var impactLight = UIImpactFeedbackGenerator(style: .light)
    private lazy var impactMedium = UIImpactFeedbackGenerator(style: .medium)

    // Colors
    private let cBg = UIColor(hex: 0x07050b)
    private let cPanel = UIColor(hex: 0x120e1c)
    private let cBorder = UIColor(hex: 0x292038)
    private let cAccent = UIColor(hex: 0x8b5cf6)
    private let cCyan = UIColor(hex: 0x38bdf8)
    private let cThrottle = UIColor(hex: 0x10b981)
    private let cBrake = UIColor(hex: 0xf43f5e)
    private let cClutch = UIColor(hex: 0xf59e0b)
    private let cMuted = UIColor(hex: 0x94a3b8)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = cBg
        initDefaultLayouts()
        impactLight.prepare()
        impactMedium.prepare()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        stopDisplayLink()
    }

    private func initDefaultLayouts() {
        let cLightPurple = UIColor(hex: 0xc4b5fd)
        let cSlate = UIColor(hex: 0xcbd5e1)
        let cRoseSoft = UIColor(hex: 0xfda4af)
        let cRoseBtn = UIColor(hex: 0xe11d48)

        raceItems = [
            ControlItem("gear_down", "◀ GEAR DOWN", .pillBtn, 4, cAccent, cLightPurple, 0.11, 0.06),
            ControlItem("cam", "CAM", .pillBtn, 6, cAccent, cSlate, 0.255, 0.06),
            ControlItem("map", "MAP", .pillBtn, 7, cAccent, cSlate, 0.345, 0.06),
            ControlItem("gear_up", "GEAR UP ▶", .pillBtn, 5, cAccent, cLightPurple, 0.88, 0.06),
            ControlItem("wheel", "STEERING WHEEL", .wheel, -1, cAccent, nil, 0.22, 0.49),
            ControlItem("lights", "LIGHTS", .pillBtn, 10, cAccent, cMuted, 0.065, 0.945),
            ControlItem("horn", "HORN", .pillBtn, 9, cAccent, cMuted, 0.17, 0.945),
            ControlItem("wipers", "WIPERS", .pillBtn, 11, cAccent, cMuted, 0.275, 0.945),
            ControlItem("ign", "IGN", .pillBtn, 12, cAccent, cMuted, 0.38, 0.945),
            ControlItem("clutch", "CLUTCH", .pedal, -1, cClutch, UIColor(hex: 0xb45309), 0.505, 0.485),
            ControlItem("brake", "BRAKE", .pedal, -1, cBrake, UIColor(hex: 0xbe123c), 0.602, 0.485),
            ControlItem("gas", "GAS", .pedal, -1, cThrottle, UIColor(hex: 0x047857), 0.70, 0.485),
            ControlItem("handbrake", "HANDBRAKE", .pillBtn, 15, cRoseBtn, cRoseSoft, 0.485, 0.945),
            ControlItem("nitro", "NITRO", .pillBtn, 13, cRoseBtn, cRoseSoft, 0.57, 0.945),
            ControlItem("cruise", "CRUISE", .pillBtn, 14, cRoseBtn, cRoseSoft, 0.65, 0.945),
            ControlItem("pit", "PIT", .pillBtn, 8, cRoseBtn, cRoseSoft, 0.73, 0.945),
            ControlItem("btn_y", "Y", .circleBtn, 3, cClutch, nil, 0.88, 0.295),
            ControlItem("btn_x", "X", .circleBtn, 2, UIColor(hex: 0x06b6d4), nil, 0.822, 0.445),
            ControlItem("btn_b", "B", .circleBtn, 1, cBrake, nil, 0.938, 0.445),
            ControlItem("btn_a", "A", .circleBtn, 0, cThrottle, nil, 0.88, 0.595),
            ControlItem("dpad", "D-PAD", .dpad, -1, cAccent, nil, 0.88, 0.885),
        ]

        raceGyroItems = [
            ControlItem("gear_down", "◀ GEAR DOWN", .pillBtn, 4, cAccent, cLightPurple, 0.11, 0.06),
            ControlItem("cam", "CAM", .pillBtn, 6, cAccent, cSlate, 0.255, 0.06),
            ControlItem("map", "MAP", .pillBtn, 7, cAccent, cSlate, 0.345, 0.06),
            ControlItem("gear_up", "GEAR UP ▶", .pillBtn, 5, cAccent, cLightPurple, 0.88, 0.06),
            ControlItem("lights", "LIGHTS", .pillBtn, 10, cAccent, cMuted, 0.065, 0.945),
            ControlItem("horn", "HORN", .pillBtn, 9, cAccent, cMuted, 0.17, 0.945),
            ControlItem("wipers", "WIPERS", .pillBtn, 11, cAccent, cMuted, 0.275, 0.945),
            ControlItem("ign", "IGN", .pillBtn, 12, cAccent, cMuted, 0.38, 0.945),
            ControlItem("brake", "BRAKE", .pedal, -1, cBrake, UIColor(hex: 0xbe123c), 0.155, 0.485),
            ControlItem("clutch", "CLUTCH", .pedal, -1, cClutch, UIColor(hex: 0xb45309), 0.415, 0.485),
            ControlItem("gas", "GAS", .pedal, -1, cThrottle, UIColor(hex: 0x047857), 0.650, 0.485),
            ControlItem("handbrake", "HANDBRAKE", .pillBtn, 15, cRoseBtn, cRoseSoft, 0.485, 0.945),
            ControlItem("nitro", "NITRO", .pillBtn, 13, cRoseBtn, cRoseSoft, 0.57, 0.945),
            ControlItem("cruise", "CRUISE", .pillBtn, 14, cRoseBtn, cRoseSoft, 0.65, 0.945),
            ControlItem("pit", "PIT", .pillBtn, 8, cRoseBtn, cRoseSoft, 0.73, 0.945),
            ControlItem("btn_y", "Y", .circleBtn, 3, cClutch, nil, 0.88, 0.295),
            ControlItem("btn_x", "X", .circleBtn, 2, UIColor(hex: 0x06b6d4), nil, 0.822, 0.445),
            ControlItem("btn_b", "B", .circleBtn, 1, cBrake, nil, 0.938, 0.445),
            ControlItem("btn_a", "A", .circleBtn, 0, cThrottle, nil, 0.88, 0.595),
            ControlItem("dpad", "D-PAD", .dpad, -1, cAccent, nil, 0.88, 0.885),
        ]

        gpItems = [
            ControlItem("lb", "LB (BUMPER)", .pillBtn, 4, cAccent, cLightPurple, 0.105, 0.06),
            ControlItem("lt", "LT", .trigger, -1, cBrake, nil, 0.265, 0.06),
            ControlItem("rt", "RT", .trigger, -1, cThrottle, nil, 0.735, 0.06),
            ControlItem("rb", "RB (BUMPER)", .pillBtn, 5, cAccent, cLightPurple, 0.895, 0.06),
            ControlItem("lstick", "LEFT STICK", .stickL, -1, cAccent, nil, 0.18, 0.40),
            ControlItem("dpad", "D-PAD", .dpad, -1, cAccent, nil, 0.18, 0.81),
            ControlItem("l3", "L3", .pillBtn, 9, cAccent, cSlate, 0.325, 0.79),
            ControlItem("view", "VIEW", .pillBtn, 6, cAccent, cSlate, 0.405, 0.24),
            ControlItem("guide", "⌘", .circleBtn, 8, cThrottle, nil, 0.50, 0.24),
            ControlItem("menu", "MENU", .pillBtn, 7, cAccent, cSlate, 0.595, 0.24),
            ControlItem("r3", "R3", .pillBtn, 10, cAccent, cSlate, 0.475, 0.79),
            ControlItem("rstick", "RIGHT STICK", .stickR, -1, cAccent, nil, 0.615, 0.69),
            ControlItem("btn_y", "Y", .circleBtn, 3, cClutch, nil, 0.86, 0.33),
            ControlItem("btn_x", "X", .circleBtn, 2, UIColor(hex: 0x06b6d4), nil, 0.792, 0.495),
            ControlItem("btn_b", "B", .circleBtn, 1, cBrake, nil, 0.928, 0.495),
            ControlItem("btn_a", "A", .circleBtn, 0, cThrottle, nil, 0.86, 0.66),
        ]
    }

    private func activeLayoutId() -> Int {
        if currentMode == Self.modeGamepad { return Self.layoutGamepad }
        return gyroEnabled ? Self.layoutRacepadGyro : Self.layoutRacepad
    }

    private func itemsForLayout(_ layoutId: Int) -> [ControlItem] {
        if layoutId == Self.layoutGamepad { return gpItems }
        if layoutId == Self.layoutRacepadGyro { return raceGyroItems }
        return raceItems
    }

    private func activeItems() -> [ControlItem] {
        itemsForLayout(activeLayoutId())
    }

    func setConnectionStatus(connected: Bool, pcName: String, badge: String) {
        self.connected = connected
        self.reconnecting = false
        self.pcDisplayName = pcName.isEmpty ? "PC" : pcName
        self.latencyText = badge.isEmpty ? "250Hz" : badge
        setNeedsDisplay()
    }

    func setReconnectingStatus(pcName: String) {
        self.connected = false
        self.reconnecting = true
        self.pcDisplayName = pcName.isEmpty ? "PC" : pcName
        self.latencyText = "RETRY"
        setNeedsDisplay()
    }

    func setGyroEnabled(_ enabled: Bool) {
        guard gyroEnabled != enabled else { return }
        gyroEnabled = enabled
        selectedItem = nil
        editDragMode = 0
        editTouch = nil
        if enabled {
            steeringTouch = false
            blendingFromTouch = false
            steerTouch = nil
        }
        setNeedsDisplay()
    }

    func setInstantTapPedals(_ instant: Bool) {
        instantTapPedals = instant
    }

    func setWheelMaxDeg(_ maxDeg: Float) {
        wheelMaxDeg = max(30, min(360, maxDeg))
        touchWheelDeg = max(-wheelMaxDeg, min(wheelMaxDeg, touchWheelDeg))
        displayWheelDeg = max(-wheelMaxDeg, min(wheelMaxDeg, displayWheelDeg))
        setNeedsDisplay()
    }

    func setEditMode(_ enabled: Bool) {
        guard editMode != enabled else { return }
        editMode = enabled
        selectedItem = nil
        editDragMode = 0
        editTouch = nil
        guideCenterX = false
        guideCenterY = false
        clearInput()
        setNeedsDisplay()
    }

    func resetSteering() {
        sensorSteer = 0
        touchWheelDeg = 0
        displayWheelDeg = 0
        steer = 0
        steeringTouch = false
        blendingFromTouch = false
        steerTouch = nil
        lastAnimTime = 0
        stopDisplayLink()
        if currentMode == Self.modeGamepad && !leftStickTouched {
            lx = 0
        }
        setNeedsDisplay()
    }

    func setDisplayedGyroSteer(_ gyroVal: Float) {
        sensorSteer = max(-1, min(1, gyroVal))
        if !steeringTouch && gyroEnabled && !editMode {
            if !blendingFromTouch {
                displayWheelDeg = sensorSteer * wheelMaxDeg
                steer = sensorSteer
                if currentMode == Self.modeGamepad && !leftStickTouched {
                    lx = steer
                }
            }
            DispatchQueue.main.async { [weak self] in
                self?.setNeedsDisplay()
            }
        }
    }

    func exportLayoutJson(_ layoutId: Int) -> String {
        var dict: [String: [String: Double]] = [:]
        for item in itemsForLayout(layoutId) {
            dict[item.id] = [
                "x": Double(round(item.cxFrac * 10000) / 10000),
                "y": Double(round(item.cyFrac * 10000) / 10000),
                "s": Double(round(item.scale * 1000) / 1000),
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else { return "{}" }
        return str
    }

    func importLayoutJson(_ layoutId: Int, json: String) {
        guard !json.isEmpty,
              let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else { return }
        let items = itemsForLayout(layoutId)
        for (id, obj) in root {
            guard let item = items.first(where: { $0.id == id }) else { continue }
            if let x = (obj["x"] as? NSNumber)?.doubleValue, x.isFinite {
                item.cxFrac = clamp(CGFloat(x), 0.02, 0.98)
            }
            if let y = (obj["y"] as? NSNumber)?.doubleValue, y.isFinite {
                item.cyFrac = clamp(CGFloat(y), 0.02, 0.98)
            }
            if let s = (obj["s"] as? NSNumber)?.doubleValue, s.isFinite {
                item.scale = clamp(CGFloat(s), Self.minScale, Self.maxScale)
            }
        }
        setNeedsDisplay()
    }

    func resetLayout(_ layoutId: Int) {
        for item in itemsForLayout(layoutId) { item.reset() }
        if layoutId == activeLayoutId() {
            guideCenterX = false
            guideCenterY = false
        }
        notifyLayoutChanged(layoutId)
        setNeedsDisplay()
    }

    func resetAllLayouts() {
        resetLayout(Self.layoutRacepad)
        resetLayout(Self.layoutRacepadGyro)
        resetLayout(Self.layoutGamepad)
    }

    private func notifyLayoutChanged(_ layoutId: Int) {
        let key = layoutId == Self.layoutGamepad
            ? "gamepad"
            : (layoutId == Self.layoutRacepadGyro ? "racepad_gyro" : "racepad")
        onLayoutChanged?(key, exportLayoutJson(layoutId))
    }

    private var leftInset: CGFloat { max(0, safeAreaInsets.left * 0.5) }
    private var rightInset: CGFloat { max(0, safeAreaInsets.right * 0.5) }
    private var playTop: CGFloat { 48 }
    private func playHeight(_ h: CGFloat) -> CGFloat { max(120, (h - 6) - playTop) }

    private func baseHalfW(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat {
        let ph = playHeight(h)
        switch item.kind {
        case .wheel:
            return min(w * 0.18, ph * 0.38)
        case .stickL:
            return min(w * 0.092, ph * 0.195)
        case .stickR:
            return min(w * 0.092, ph * 0.195) * 0.90
        case .dpad:
            return currentMode == Self.modeRacepad ? min(18, ph * 0.068) : min(23, ph * 0.082)
        case .pedal:
            if currentMode == Self.modeRacepad && gyroEnabled && (item.id == "brake" || item.id == "gas") {
                return w * 0.098
            }
            return w * 0.041
        case .trigger:
            return w * 0.065
        case .circleBtn:
            return item.id == "guide" ? 17 : min(w * 0.045, ph * 0.095)
        case .pillBtn:
            if item.id == "gear_down" || item.id == "gear_up" { return w * 0.095 }
            if item.id == "lb" || item.id == "rb" { return w * 0.085 }
            if item.id == "cam" || item.id == "map" { return clamp(w * 0.036, 20, 28) }
            if item.id == "view" || item.id == "menu" { return 27 }
            if item.id == "l3" || item.id == "r3" { return 24 }
            return clamp(w * 0.046, 25, 38)
        }
    }

    private func baseHalfH(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat {
        let ph = playHeight(h)
        switch item.kind {
        case .wheel, .stickL, .stickR, .dpad, .circleBtn:
            return baseHalfW(item, w: w, h: h)
        case .pedal:
            return ph * 0.34
        case .trigger:
            return 16
        case .pillBtn:
            if item.id == "gear_down" || item.id == "gear_up" { return 17 }
            if item.id == "lb" || item.id == "rb" { return 16 }
            if ["cam", "map", "view", "menu", "l3", "r3"].contains(item.id) { return 15 }
            return 13.5
        }
    }

    private func itemHalfW(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat { baseHalfW(item, w: w, h: h) * item.scale }
    private func itemHalfH(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat { baseHalfH(item, w: w, h: h) * item.scale }

    private func itemCx(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat {
        let hw = itemHalfW(item, w: w, h: h)
        let minX = min(w * 0.5, hw + 6 + leftInset)
        let maxX = max(minX, w - hw - 6 - rightInset)
        return clamp(item.cxFrac * w, minX, maxX)
    }

    private func itemCy(_ item: ControlItem, w: CGFloat, h: CGFloat) -> CGFloat {
        let ph = playHeight(h)
        let hh = itemHalfH(item, w: w, h: h)
        let minY = min(playTop + ph * 0.5, playTop + hh + 4)
        let maxY = max(minY, h - hh - 4)
        return clamp(playTop + item.cyFrac * ph, minY, maxY)
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let w = bounds.width
        let h = bounds.height
        guard w > 0, h > 0 else { return }

        ctx.setFillColor(cBg.cgColor)
        ctx.fill(bounds)

        if editMode {
            drawEditBackground(ctx, w: w, h: h)
            drawEditHud(ctx, w: w)
        } else {
            drawTopHud(ctx, w: w)
        }

        if !editMode && currentMode == Self.modeRacepad {
            updateSteeringAnimation()
        }

        for item in activeItems() {
            drawControlItem(ctx, item: item, w: w, h: h)
        }

        if editMode {
            drawEditOverlays(ctx, w: w, h: h)
        }
    }

    private func drawEditBackground(_ ctx: CGContext, w: CGFloat, h: CGFloat) {
        if snapToGrid {
            ctx.setFillColor(UIColor(hex: 0x1d1630).cgColor)
            var gx: CGFloat = 14 + leftInset
            while gx < w - 10 - rightInset {
                var gy: CGFloat = playTop + 10
                while gy < h - 10 {
                    ctx.fillEllipse(in: CGRect(x: gx - 1, y: gy - 1, width: 2, height: 2))
                    gy += 18
                }
                gx += 18
            }
        }
        ctx.setLineWidth(1)
        ctx.setStrokeColor((guideCenterX ? UIColor(hex: 0xa855f7) : UIColor(hex: 0x3c2e5c, alpha: 0.3)).cgColor)
        ctx.move(to: CGPoint(x: w / 2, y: playTop))
        ctx.addLine(to: CGPoint(x: w / 2, y: h))
        ctx.strokePath()

        let midY = playTop + playHeight(h) / 2
        ctx.setStrokeColor((guideCenterY ? UIColor(hex: 0xa855f7) : UIColor(hex: 0x3c2e5c, alpha: 0.3)).cgColor)
        ctx.move(to: CGPoint(x: 0, y: midY))
        ctx.addLine(to: CGPoint(x: w, y: midY))
        ctx.strokePath()
    }

    private func hudLayout(w: CGFloat) -> (badge: CGRect, mode: CGRect, edit: CGRect, center: CGRect, setup: CGRect) {
        let barH: CGFloat = 36
        let top: CGFloat = 6
        let padL: CGFloat = 12 + leftInset
        let padR: CGFloat = 12 + rightInset
        let gap: CGFloat = 6

        let badgeW = clamp(w * 0.22, 126, 165)
        let setupW = clamp(w * 0.105, 60, 74)
        let centerW = clamp(w * 0.112, 64, 80)
        let editW = clamp(w * 0.098, 56, 70)

        let setupL = w - padR - setupW
        let centerL = setupL - gap - centerW
        let editL = centerL - gap - editW
        let badgeR = padL + badgeW

        let avail = (editL - badgeR) - gap * 2
        let modeW = min(clamp(w * 0.24, 122, 170), max(98, avail))
        let modeL = badgeR + (editL - badgeR - modeW) / 2

        return (
            CGRect(x: padL, y: top, width: badgeW, height: barH),
            CGRect(x: modeL, y: top, width: modeW, height: barH),
            CGRect(x: editL, y: top, width: editW, height: barH),
            CGRect(x: centerL, y: top, width: centerW, height: barH),
            CGRect(x: setupL, y: top, width: setupW, height: barH)
        )
    }

    private func drawTopHud(_ ctx: CGContext, w: CGFloat) {
        let r = hudLayout(w: w)

        drawPill(ctx, r.badge, fill: cPanel, stroke: reconnecting ? cClutch : cBorder)
        ctx.setFillColor((connected ? cThrottle : (reconnecting ? cClutch : cBrake)).cgColor)
        ctx.fillEllipse(in: CGRect(x: r.badge.minX + 9, y: r.badge.midY - 4, width: 8, height: 8))
        let connStr = connected ? "\(pcDisplayName) · \(latencyText)" : (reconnecting ? "RECONNECTING…" : "TAP TO CONNECT")
        let connCol = connected ? UIColor(hex: 0x34d399) : (reconnecting ? UIColor(hex: 0xfbbf24) : UIColor(hex: 0xcbd5e1))
        drawText(connStr, at: CGPoint(x: r.badge.minX + 21, y: r.badge.midY), size: 10, color: connCol, align: .left, bold: true)

        drawPill(ctx, r.mode, fill: UIColor(hex: 0x1e182a), stroke: cAccent)
        if currentMode == Self.modeRacepad && gyroEnabled {
            let deg = Int(roundf(displayWheelDeg))
            let degText = "\(deg > 0 ? "+" : "")\(deg)°"
            drawText("🏎 RACE · \(degText)", at: CGPoint(x: r.mode.midX, y: r.mode.midY - 3), size: 10.2, color: UIColor(hex: 0xe9d5ff), align: .center, bold: true)
            let trackL = r.mode.minX + 16
            let trackR = r.mode.maxX - 16
            let trackY = r.mode.maxY - 6
            let midX = (trackL + trackR) * 0.5
            ctx.setLineWidth(2.6)
            ctx.setLineCap(.round)
            ctx.setStrokeColor(UIColor(hex: 0x2c223f).cgColor)
            ctx.move(to: CGPoint(x: trackL, y: trackY))
            ctx.addLine(to: CGPoint(x: trackR, y: trackY))
            ctx.strokePath()
            let steerVal = CGFloat(max(-1, min(1, displayWheelDeg / max(1, wheelMaxDeg))))
            if abs(steerVal) > 0.02 {
                ctx.setStrokeColor((abs(steerVal) > 0.72 ? cCyan : cAccent).cgColor)
                ctx.move(to: CGPoint(x: midX, y: trackY))
                ctx.addLine(to: CGPoint(x: midX + steerVal * (trackR - midX), y: trackY))
                ctx.strokePath()
            }
            ctx.setFillColor(cClutch.cgColor)
            ctx.fillEllipse(in: CGRect(x: midX - 2, y: trackY - 2, width: 4, height: 4))
        } else {
            let modeName = currentMode == Self.modeRacepad ? "🏎 RACEPAD" : "🎮 GAMEPAD"
            drawText(modeName, at: CGPoint(x: r.mode.midX, y: r.mode.midY), size: 11, color: UIColor(hex: 0xe9d5ff), align: .center, bold: true)
        }

        drawPill(ctx, r.edit, fill: cPanel, stroke: cBorder)
        drawText("✎ EDIT", at: CGPoint(x: r.edit.midX, y: r.edit.midY), size: 10, color: cCyan, align: .center, bold: true)

        drawPill(ctx, r.center, fill: cPanel, stroke: cBorder)
        drawText("⌖ CENTER", at: CGPoint(x: r.center.midX, y: r.center.midY), size: 10, color: UIColor(hex: 0xcbd5e1), align: .center, bold: true)

        drawPill(ctx, r.setup, fill: cPanel, stroke: cBorder)
        drawText("⚙ SETUP", at: CGPoint(x: r.setup.midX, y: r.setup.midY), size: 10, color: UIColor(hex: 0xcbd5e1), align: .center, bold: true)
    }

    private func editBarRects(w: CGFloat) -> [CGRect] {
        let s = clamp(w / 650, 0.78, 1.0)
        let gap: CGFloat = 5 * s
        let top: CGFloat = 6
        let h: CGFloat = 36
        let padR = 12 + rightInset
        let doneW = 66 * s
        let allW = 56 * s
        let oneW = 60 * s
        let snapW = 54 * s
        let pmW = 34 * s

        let doneL = w - padR - doneW
        let allL = doneL - gap - allW
        let oneL = allL - gap - oneW
        let snapL = oneL - gap - snapW
        let plusL = snapL - gap - pmW
        let minusL = plusL - gap - pmW

        return [
            CGRect(x: minusL, y: top, width: pmW, height: h),
            CGRect(x: plusL, y: top, width: pmW, height: h),
            CGRect(x: snapL, y: top, width: snapW, height: h),
            CGRect(x: oneL, y: top, width: oneW, height: h),
            CGRect(x: allL, y: top, width: allW, height: h),
            CGRect(x: doneL, y: top, width: doneW, height: h),
        ]
    }

    private func drawEditHud(_ ctx: CGContext, w: CGFloat) {
        let btns = editBarRects(w: w)
        let padL = 12 + leftInset
        let titleRect = CGRect(x: padL, y: 6, width: max(80, btns[0].minX - 8 - padL), height: 36)
        let title: String
        let titleCol: UIColor
        if let sel = selectedItem {
            title = "\(sel.label) · \(Int(round(sel.scale * 100)))%"
            titleCol = cCyan
        } else {
            let modeName = currentMode == Self.modeGamepad ? "GAMEPAD" : (gyroEnabled ? "RACEPAD (GYRO)" : "RACEPAD (WHEEL)")
            title = "EDIT \(modeName) · DRAG OR PINCH"
            titleCol = UIColor(hex: 0xe9d5ff)
        }
        drawPill(ctx, titleRect, fill: UIColor(hex: 0x161124), stroke: selectedItem != nil ? cCyan : cAccent)
        drawText(title, at: CGPoint(x: titleRect.minX + 12, y: titleRect.midY), size: 10, color: titleCol, align: .left, bold: true)

        let hasSel = selectedItem != nil
        drawPill(ctx, btns[0], fill: cPanel, stroke: hasSel ? cCyan : cBorder)
        drawText("−", at: CGPoint(x: btns[0].midX, y: btns[0].midY), size: 14, color: hasSel ? .white : UIColor(hex: 0x475569), align: .center, bold: true)

        drawPill(ctx, btns[1], fill: cPanel, stroke: hasSel ? cCyan : cBorder)
        drawText("+", at: CGPoint(x: btns[1].midX, y: btns[1].midY), size: 14, color: hasSel ? .white : UIColor(hex: 0x475569), align: .center, bold: true)

        drawPill(ctx, btns[2], fill: snapToGrid ? UIColor(hex: 0x2b1f47) : cPanel, stroke: snapToGrid ? cAccent : cBorder)
        drawText("SNAP", at: CGPoint(x: btns[2].midX, y: btns[2].midY), size: 9.5, color: snapToGrid ? UIColor(hex: 0xe9d5ff) : cMuted, align: .center, bold: true)

        let canResetOne = selectedItem?.isModified ?? false
        drawPill(ctx, btns[3], fill: cPanel, stroke: canResetOne ? cClutch : cBorder)
        drawText("↺ 1", at: CGPoint(x: btns[3].midX, y: btns[3].midY), size: 9.5, color: canResetOne ? UIColor(hex: 0xfbbf24) : UIColor(hex: 0x64748b), align: .center, bold: true)

        drawPill(ctx, btns[4], fill: cPanel, stroke: cBrake)
        drawText("↺ ALL", at: CGPoint(x: btns[4].midX, y: btns[4].midY), size: 9.5, color: UIColor(hex: 0xfda4af), align: .center, bold: true)

        drawPill(ctx, btns[5], fill: UIColor(hex: 0x065f46), stroke: cThrottle)
        drawText("✓ DONE", at: CGPoint(x: btns[5].midX, y: btns[5].midY), size: 9.8, color: .white, align: .center, bold: true)
    }

    private func drawControlItem(_ ctx: CGContext, item: ControlItem, w: CGFloat, h: CGFloat) {
        let cx = itemCx(item, w: w, h: h)
        let cy = itemCy(item, w: w, h: h)
        let hw = itemHalfW(item, w: w, h: h)
        let hh = itemHalfH(item, w: w, h: h)
        let s = item.scale

        switch item.kind {
        case .wheel:
            drawSteeringGauge(ctx, cx: cx, cy: cy, radius: hw, scale: s)
        case .pedal:
            let val = item.id == "clutch" ? clutch : (item.id == "brake" ? brake : throttle)
            drawPedal(ctx, rect: CGRect(x: cx - hw, y: cy - hh, width: hw * 2, height: hh * 2), label: item.label, value: val, colorTop: item.accentColor, colorBottom: item.altColor ?? item.accentColor, scale: s)
        case .stickL:
            drawStick(ctx, cx: cx, cy: cy, radius: hw, valX: lx, valY: ly, label: "LEFT STICK", click: buttons[9], scale: s)
        case .stickR:
            drawStick(ctx, cx: cx, cy: cy, radius: hw, valX: rx, valY: ry, label: "RIGHT STICK", click: buttons[10], scale: s)
        case .dpad:
            drawDpad(ctx, cx: cx, cy: cy, span: hw, scale: s)
        case .trigger:
            let val = item.id == "lt" ? lt : rt
            drawTriggerPill(ctx, rect: CGRect(x: cx - hw, y: cy - hh, width: hw * 2, height: hh * 2), label: item.label, value: val, color: item.accentColor, scale: s)
        case .circleBtn:
            drawCircleButton(ctx, cx: cx, cy: cy, radius: hw, label: item.label, slot: item.buttonSlot, glow: item.accentColor, scale: s)
        case .pillBtn:
            let active = item.buttonSlot >= 0 && item.buttonSlot < buttons.count && buttons[item.buttonSlot]
            let fill = active ? item.accentColor : cPanel
            let stroke = active ? UIColor.white : cBorder
            let textCol = active ? UIColor.white : (item.altColor ?? UIColor(hex: 0xcbd5e1))
            drawPill(ctx, CGRect(x: cx - hw, y: cy - hh, width: hw * 2, height: hh * 2), fill: fill, stroke: stroke)
            let sp = (item.label.count > 7 ? 8.5 : 9.5) * clamp(s, 0.72, 1.45)
            drawText(item.label, at: CGPoint(x: cx, y: cy), size: sp, color: textCol, align: .center, bold: true)
        }
    }

    private func drawEditOverlays(_ ctx: CGContext, w: CGFloat, h: CGFloat) {
        for item in activeItems() {
            let cx = itemCx(item, w: w, h: h)
            let cy = itemCy(item, w: w, h: h)
            let hw = itemHalfW(item, w: w, h: h)
            let hh = itemHalfH(item, w: w, h: h)
            let isSel = item === selectedItem
            let rect = CGRect(x: cx - hw - 2, y: cy - hh - 2, width: hw * 2 + 4, height: hh * 2 + 4)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 10)
            ctx.saveGState()
            ctx.setStrokeColor((isSel ? cCyan : UIColor(hex: 0x6d5996, alpha: 0.65)).cgColor)
            ctx.setLineWidth(isSel ? 2 : 1.1)
            if !isSel { ctx.setLineDash(phase: 0, lengths: [6, 5]) }
            ctx.addPath(path.cgPath)
            ctx.strokePath()
            ctx.restoreGState()

            if isSel {
                let hx = min(w - 10, rect.maxX)
                let hy = min(h - 10, rect.maxY)
                let handle = CGRect(x: hx - 9, y: hy - 9, width: 18, height: 18)
                ctx.setFillColor(cCyan.cgColor)
                ctx.fillEllipse(in: handle)
                ctx.setStrokeColor(UIColor.white.cgColor)
                ctx.setLineWidth(1.8)
                ctx.strokeEllipse(in: handle)
                drawText("↘", at: CGPoint(x: hx, y: hy), size: 9.5, color: UIColor(hex: 0x06121e), align: .center, bold: true)
            }
        }
    }

    private func drawSteeringGauge(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, radius: CGFloat, scale s: CGFloat) {
        let steerVal = CGFloat(max(-1, min(1, displayWheelDeg / max(1, wheelMaxDeg))))
        let arcHalf = CGFloat(min(135, wheelMaxDeg))
        let startRad = (270 - arcHalf) * .pi / 180
        let endRad = (270 + arcHalf) * .pi / 180

        ctx.setLineWidth(5.5 * clamp(s, 0.7, 1.5))
        ctx.setLineCap(.round)
        ctx.setStrokeColor(UIColor(hex: 0x1b1528).cgColor)
        ctx.addArc(center: CGPoint(x: cx, y: cy), radius: radius, startAngle: startRad, endAngle: endRad, clockwise: false)
        ctx.strokePath()

        let sweep = steerVal * arcHalf
        if abs(sweep) > 0.5 {
            let sweepCol = steeringTouch ? UIColor(hex: 0xa855f7) : (abs(steerVal) > 0.72 ? cCyan : cAccent)
            ctx.setStrokeColor(sweepCol.cgColor)
            let topRad = 270 * CGFloat.pi / 180
            ctx.addArc(center: CGPoint(x: cx, y: cy), radius: radius, startAngle: topRad, endAngle: topRad + sweep * .pi / 180, clockwise: sweep < 0)
            ctx.strokePath()
        }

        let maxTick = Int(round(arcHalf))
        for deg in stride(from: -maxTick, through: maxTick, by: 15) {
            let major = deg == 0 || abs(deg) % 30 == 0 || abs(deg) == maxTick
            let rad = (270 + CGFloat(deg)) * .pi / 180
            let inner = radius - (deg == 0 ? 13 : (major ? 8.5 : 5.5)) * clamp(s, 0.7, 1.4)
            let outer = radius + 3.5 * clamp(s, 0.7, 1.4)
            ctx.setLineWidth(deg == 0 ? 2.4 : (major ? 1.5 : 1))
            ctx.setStrokeColor((deg == 0 ? cClutch : (major ? UIColor(hex: 0x5b4a7a) : UIColor(hex: 0x2f2543))).cgColor)
            ctx.move(to: CGPoint(x: cx + cos(rad) * inner, y: cy + sin(rad) * inner))
            ctx.addLine(to: CGPoint(x: cx + cos(rad) * outer, y: cy + sin(rad) * outer))
            ctx.strokePath()
        }

        let rimR = radius * 0.76
        let rimThick = max(9, radius * 0.135)
        let hubR = radius * 0.27

        ctx.saveGState()
        ctx.translateBy(x: cx, y: cy)
        ctx.rotate(by: CGFloat(displayWheelDeg) * .pi / 180)

        drawSpoke(ctx, outerLen: rimR * 0.94, innerLen: hubR * 0.7, angleDeg: 188, outerW: 10 * s, innerW: 14 * s)
        drawSpoke(ctx, outerLen: rimR * 0.94, innerLen: hubR * 0.7, angleDeg: -8, outerW: 10 * s, innerW: 14 * s)
        drawSpoke(ctx, outerLen: rimR * 0.94, innerLen: hubR * 0.7, angleDeg: 90, outerW: 12 * s, innerW: 17 * s)

        ctx.setLineWidth(rimThick)
        ctx.setStrokeColor(UIColor(hex: 0x1a1524).cgColor)
        ctx.strokeEllipse(in: CGRect(x: -rimR, y: -rimR, width: rimR * 2, height: rimR * 2))

        ctx.setLineWidth(1.8)
        let rimAccent = steeringTouch ? UIColor(hex: 0xc084fc) : (abs(steerVal) > 0.04 ? UIColor(hex: 0x7c3aed) : UIColor(hex: 0x463761))
        ctx.setStrokeColor(rimAccent.cgColor)
        let bevelR = rimR + rimThick * 0.5 - 0.5
        ctx.strokeEllipse(in: CGRect(x: -bevelR, y: -bevelR, width: bevelR * 2, height: bevelR * 2))

        // 12 o'clock center stripe
        let stripeHalfW = max(3, rimThick * 0.34)
        let stripeRect = CGRect(x: -stripeHalfW, y: -rimR - rimThick * 0.54, width: stripeHalfW * 2, height: rimThick * 1.08)
        ctx.setFillColor(cClutch.cgColor)
        ctx.addPath(UIBezierPath(roundedRect: stripeRect, cornerRadius: 2).cgPath)
        ctx.fillPath()

        ctx.setFillColor(UIColor(hex: 0x130f1d).cgColor)
        ctx.fillEllipse(in: CGRect(x: -hubR, y: -hubR, width: hubR * 2, height: hubR * 2))
        ctx.setLineWidth(2)
        ctx.setStrokeColor((steeringTouch ? UIColor(hex: 0xc084fc) : UIColor(hex: 0x5b4878)).cgColor)
        ctx.strokeEllipse(in: CGRect(x: -hubR, y: -hubR, width: hubR * 2, height: hubR * 2))

        ctx.restoreGState()

        let angle = Int(roundf(displayWheelDeg))
        let angleStr = "\(angle > 0 ? "+" : "")\(angle)°"
        let badgeW = max(42 * clamp(s, 0.75, 1.4), hubR * 1.85)
        let badgeH = 22 * clamp(s, 0.75, 1.4)
        drawPill(ctx, CGRect(x: cx - badgeW / 2, y: cy - badgeH / 2, width: badgeW, height: badgeH), fill: UIColor(hex: 0x100c18, alpha: 0.9), stroke: steeringTouch ? UIColor(hex: 0xc084fc) : UIColor(hex: 0x463761))
        drawText(angleStr, at: CGPoint(x: cx, y: cy), size: 12.5 * clamp(s, 0.72, 1.4), color: .white, align: .center, bold: true)

        let tag = steeringTouch ? "TOUCH · ±\(Int(round(wheelMaxDeg)))°" : "DRAG · ±\(Int(round(wheelMaxDeg)))°"
        drawText(tag, at: CGPoint(x: cx, y: cy + radius - 6), size: 7.8 * clamp(s, 0.75, 1.3), color: cMuted, align: .center, bold: true)
    }

    private func drawSpoke(_ ctx: CGContext, outerLen: CGFloat, innerLen: CGFloat, angleDeg: CGFloat, outerW: CGFloat, innerW: CGFloat) {
        let rad = angleDeg * .pi / 180
        let c = cos(rad), s = sin(rad)
        let px = -s, py = c
        let path = CGMutablePath()
        path.move(to: CGPoint(x: c * innerLen + px * innerW * 0.5, y: s * innerLen + py * innerW * 0.5))
        path.addLine(to: CGPoint(x: c * outerLen + px * outerW * 0.5, y: s * outerLen + py * outerW * 0.5))
        path.addLine(to: CGPoint(x: c * outerLen - px * outerW * 0.5, y: s * outerLen - py * outerW * 0.5))
        path.addLine(to: CGPoint(x: c * innerLen - px * innerW * 0.5, y: s * innerLen - py * innerW * 0.5))
        path.closeSubpath()
        ctx.setFillColor(UIColor(hex: 0x251d36).cgColor)
        ctx.addPath(path)
        ctx.fillPath()
    }

    private func drawPedal(_ ctx: CGContext, rect: CGRect, label: String, value: Float, colorTop: UIColor, colorBottom: UIColor, scale s: CGFloat) {
        let bgPath = UIBezierPath(roundedRect: rect, cornerRadius: 12)
        ctx.setFillColor(cPanel.cgColor)
        ctx.addPath(bgPath.cgPath)
        ctx.fillPath()
        ctx.setLineWidth(1.5)
        ctx.setStrokeColor((value > 0.05 ? colorTop : cBorder).cgColor)
        ctx.addPath(bgPath.cgPath)
        ctx.strokePath()

        if value > 0.01 {
            let fillH = rect.height * CGFloat(value)
            let fillRect = CGRect(x: rect.minX + 2.5, y: rect.maxY - fillH + 1, width: rect.width - 5, height: max(4, fillH - 3.5))
            ctx.saveGState()
            ctx.addPath(UIBezierPath(roundedRect: fillRect, cornerRadius: 10).cgPath)
            ctx.clip()
            if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [colorTop.cgColor, colorBottom.cgColor] as CFArray, locations: [0, 1]) {
                ctx.drawLinearGradient(grad, start: CGPoint(x: rect.midX, y: fillRect.minY), end: CGPoint(x: rect.midX, y: rect.maxY), options: [])
            }
            ctx.restoreGState()
        }

        drawText(label, at: CGPoint(x: rect.midX, y: rect.minY + 12 * clamp(s, 0.8, 1.3)), size: 9 * clamp(s, 0.72, 1.4), color: value > 0.05 ? .white : cMuted, align: .center, bold: true)
        drawText("\(Int(round(value * 100)))%", at: CGPoint(x: rect.midX, y: rect.maxY - 12), size: 11 * clamp(s, 0.72, 1.4), color: .white, align: .center, bold: true)
    }

    private func drawTriggerPill(_ ctx: CGContext, rect: CGRect, label: String, value: Float, color: UIColor, scale s: CGFloat) {
        drawPill(ctx, rect, fill: cPanel, stroke: cBorder)
        let fillW = rect.width * CGFloat(value)
        if fillW > 4 {
            let fillRect = CGRect(x: rect.minX + 2, y: rect.minY + 2, width: fillW - 4, height: rect.height - 4)
            drawPill(ctx, fillRect, fill: color, stroke: nil)
        }
        drawText("\(label) \(Int(round(value * 100)))%", at: CGPoint(x: rect.midX, y: rect.midY), size: 9.5 * clamp(s, 0.72, 1.45), color: .white, align: .center, bold: true)
    }

    private func drawStick(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, radius: CGFloat, valX: Float, valY: Float, label: String, click: Bool, scale s: CGFloat) {
        let baseRect = CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)
        ctx.setFillColor(cPanel.cgColor)
        ctx.fillEllipse(in: baseRect)
        ctx.setLineWidth(1.8)
        ctx.setStrokeColor((click ? cAccent : cBorder).cgColor)
        ctx.strokeEllipse(in: baseRect)

        ctx.setStrokeColor(UIColor(hex: 0x231b32).cgColor)
        ctx.setLineWidth(1.2)
        ctx.move(to: CGPoint(x: cx - radius * 0.7, y: cy))
        ctx.addLine(to: CGPoint(x: cx + radius * 0.7, y: cy))
        ctx.move(to: CGPoint(x: cx, y: cy - radius * 0.7))
        ctx.addLine(to: CGPoint(x: cx, y: cy + radius * 0.7))
        ctx.strokePath()

        let knobR = radius * 0.44
        let kx = cx + CGFloat(valX) * (radius - knobR)
        let ky = cy + CGFloat(valY) * (radius - knobR)
        let knobRect = CGRect(x: kx - knobR, y: ky - knobR, width: knobR * 2, height: knobR * 2)
        ctx.setFillColor((click ? cAccent : UIColor(hex: 0x1e172a)).cgColor)
        ctx.fillEllipse(in: knobRect)
        ctx.setLineWidth(2)
        ctx.setStrokeColor((click ? UIColor.white : UIColor(hex: 0x5b4878)).cgColor)
        ctx.strokeEllipse(in: knobRect)

        drawText(label, at: CGPoint(x: cx, y: cy + radius - 9), size: 7.8 * clamp(s, 0.75, 1.35), color: cMuted, align: .center, bold: true)
    }

    private func drawDpad(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, span: CGFloat, scale s: CGFloat) {
        let armW = span * 0.7
        let armH = span * 0.6
        let sp = 8.5 * clamp(s, 0.72, 1.45)

        let upR = CGRect(x: cx - armW / 2, y: cy - span, width: armW, height: armH)
        drawPill(ctx, upR, fill: dpadUp ? cAccent : cPanel, stroke: dpadUp ? .white : cBorder)
        drawText("▲", at: CGPoint(x: upR.midX, y: upR.midY), size: sp, color: dpadUp ? .white : cMuted, align: .center, bold: true)

        let dnR = CGRect(x: cx - armW / 2, y: cy + span - armH, width: armW, height: armH)
        drawPill(ctx, dnR, fill: dpadDown ? cAccent : cPanel, stroke: dpadDown ? .white : cBorder)
        drawText("▼", at: CGPoint(x: dnR.midX, y: dnR.midY), size: sp, color: dpadDown ? .white : cMuted, align: .center, bold: true)

        let ltR = CGRect(x: cx - span, y: cy - armW / 2, width: armH, height: armW)
        drawPill(ctx, ltR, fill: dpadLeft ? cAccent : cPanel, stroke: dpadLeft ? .white : cBorder)
        drawText("◀", at: CGPoint(x: ltR.midX, y: ltR.midY), size: sp, color: dpadLeft ? .white : cMuted, align: .center, bold: true)

        let rtR = CGRect(x: cx + span - armH, y: cy - armW / 2, width: armH, height: armW)
        drawPill(ctx, rtR, fill: dpadRight ? cAccent : cPanel, stroke: dpadRight ? .white : cBorder)
        drawText("▶", at: CGPoint(x: rtR.midX, y: rtR.midY), size: sp, color: dpadRight ? .white : cMuted, align: .center, bold: true)
    }

    private func drawCircleButton(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, radius: CGFloat, label: String, slot: Int, glow: UIColor, scale s: CGFloat) {
        let pressed = slot >= 0 && slot < buttons.count && buttons[slot]
        let rect = CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2)
        ctx.setFillColor((pressed ? glow : cPanel).cgColor)
        ctx.fillEllipse(in: rect)
        ctx.setLineWidth(2)
        ctx.setStrokeColor((pressed ? UIColor.white : cBorder).cgColor)
        ctx.strokeEllipse(in: rect)
        let col = pressed ? UIColor.white : (label == "⌘" ? UIColor(hex: 0xa78bfa) : UIColor(hex: 0xe2e8f0))
        drawText(label, at: CGPoint(x: cx, y: cy), size: 14.5 * clamp(s, 0.7, 1.55), color: col, align: .center, bold: true)
    }

    private func drawPill(_ ctx: CGContext, _ rect: CGRect, fill: UIColor, stroke: UIColor?) {
        let path = UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2)
        ctx.setFillColor(fill.cgColor)
        ctx.addPath(path.cgPath)
        ctx.fillPath()
        if let stroke = stroke {
            ctx.setLineWidth(1.2)
            ctx.setStrokeColor(stroke.cgColor)
            ctx.addPath(path.cgPath)
            ctx.strokePath()
        }
    }

    private func drawText(_ text: String, at point: CGPoint, size: CGFloat, color: UIColor, align: NSTextAlignment, bold: Bool) {
        let font = bold ? UIFont.systemFont(ofSize: size, weight: .bold) : UIFont.systemFont(ofSize: size, weight: .regular)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        let sz = (text as NSString).size(withAttributes: attrs)
        let x: CGFloat
        switch align {
        case .center: x = point.x - sz.width / 2
        case .right: x = point.x - sz.width
        default: x = point.x
        }
        (text as NSString).draw(at: CGPoint(x: x, y: point.y - sz.height / 2), withAttributes: attrs)
    }

    // MARK: - Animation
    private func ensureDisplayLink() {
        guard displayLink == nil else { return }
        let dl = CADisplayLink(target: self, selector: #selector(onDisplayFrame))
        if #available(iOS 15.0, *) {
            dl.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        }
        dl.add(to: .main, forMode: .common)
        displayLink = dl
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func onDisplayFrame() {
        setNeedsDisplay()
    }

    private func updateSteeringAnimation() {
        let now = CACurrentMediaTime()
        let dt = lastAnimTime == 0 ? 0.016 : Float(clamp(CGFloat(now - lastAnimTime), 0.001, 0.05))
        lastAnimTime = now

        if steeringTouch {
            displayWheelDeg = touchWheelDeg
            return
        }
        if gyroEnabled {
            let targetDeg = max(-1, min(1, sensorSteer)) * wheelMaxDeg
            if blendingFromTouch {
                let diff = targetDeg - displayWheelDeg
                if abs(diff) > 0.35 {
                    let k = 1 - expf(-dt * 32)
                    displayWheelDeg += diff * k
                    steer = max(-1, min(1, displayWheelDeg / wheelMaxDeg))
                    touchWheelDeg = displayWheelDeg
                    if currentMode == Self.modeGamepad && !leftStickTouched { lx = steer }
                    notifyInputChanged()
                    ensureDisplayLink()
                    return
                }
                blendingFromTouch = false
                stopDisplayLink()
            }
            displayWheelDeg = targetDeg
            steer = max(-1, min(1, sensorSteer))
            return
        }

        if abs(displayWheelDeg) > 0.35 {
            let k = 1 - expf(-dt * 22)
            displayWheelDeg += (0 - displayWheelDeg) * k
            touchWheelDeg = displayWheelDeg
            steer = max(-1, min(1, displayWheelDeg / wheelMaxDeg))
            notifyInputChanged()
            ensureDisplayLink()
        } else if displayWheelDeg != 0 || steer != 0 || touchWheelDeg != 0 {
            displayWheelDeg = 0
            touchWheelDeg = 0
            steer = 0
            stopDisplayLink()
            notifyInputChanged()
        }
    }

    // MARK: - Touch Handling
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        let w = bounds.width, h = bounds.height
        if editMode {
            handleEditBegan(touches, w: w, h: h)
            return
        }
        for touch in touches {
            let pt = touch.location(in: self)
            if pt.y <= 46 {
                handleHudTouch(pt.x, w: w)
                return
            }
            activeTouches[touch] = pt
            if let hit = findHitControl(pt.x, pt.y, w: w, h: h) {
                if currentMode == Self.modeRacepad && hit.kind == .wheel && steerTouch == nil {
                    let cx = itemCx(hit, w: w, h: h)
                    let cy = itemCy(hit, w: w, h: h)
                    steerTouch = touch
                    steeringTouch = true
                    blendingFromTouch = false
                    touchWheelDeg = displayWheelDeg
                    lastTouchAngleDeg = Float(atan2(pt.y - cy, pt.x - cx) * 180 / .pi)
                    lastTouchX = pt.x
                    hitFullLockHaptic = abs(touchWheelDeg) >= wheelMaxDeg - 0.5
                    lastAnimTime = 0
                    stopDisplayLink()
                } else if currentMode == Self.modeGamepad && hit.kind == .stickL && leftStickTouch == nil {
                    leftStickTouch = touch
                } else if currentMode == Self.modeGamepad && hit.kind == .stickR && rightStickTouch == nil {
                    rightStickTouch = touch
                }
            }
        }
        recomputePlayTouches()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        let w = bounds.width, h = bounds.height
        if editMode {
            handleEditMoved(touches, w: w, h: h)
            return
        }
        for touch in touches where activeTouches[touch] != nil {
            activeTouches[touch] = touch.location(in: self)
        }
        recomputePlayTouches()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if editMode {
            handleEditEnded(touches)
            return
        }
        for touch in touches {
            activeTouches.removeValue(forKey: touch)
            if touch == steerTouch {
                steerTouch = nil
                blendingFromTouch = true
                lastAnimTime = CACurrentMediaTime()
                ensureDisplayLink()
            }
            if touch == leftStickTouch { leftStickTouch = nil }
            if touch == rightStickTouch { rightStickTouch = nil }
        }
        recomputePlayTouches()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if editMode {
            editDragMode = 0
            editTouch = nil
            guideCenterX = false
            guideCenterY = false
            setNeedsDisplay()
            return
        }
        for touch in touches {
            activeTouches.removeValue(forKey: touch)
            if touch == steerTouch { steerTouch = nil }
            if touch == leftStickTouch { leftStickTouch = nil }
            if touch == rightStickTouch { rightStickTouch = nil }
        }
        if activeTouches.isEmpty {
            clearInput()
        } else {
            recomputePlayTouches()
        }
    }

    private func recomputePlayTouches() {
        let w = bounds.width, h = bounds.height
        let items = activeItems()
        var nextSteer: Float = 0
        var nextSteeringTouch = false
        var nextLeftStickTouch = false
        var nextThrottle: Float = 0, nextBrake: Float = 0, nextClutch: Float = 0, nextHandbrake: Float = 0
        var nextLx: Float = 0, nextLy: Float = 0, nextRx: Float = 0, nextRy: Float = 0
        var nextLt: Float = 0, nextRt: Float = 0
        var nUp = false, nDn = false, nLt = false, nRt = false
        var nextButtons = Array(repeating: false, count: 16)

        for (touch, pt) in activeTouches {
            if currentMode == Self.modeRacepad && touch == steerTouch, let wheel = items.first(where: { $0.id == "wheel" }) {
                let cx = itemCx(wheel, w: w, h: h)
                let cy = itemCy(wheel, w: w, h: h)
                let radius = itemHalfW(wheel, w: w, h: h)
                let dist = hypot(pt.x - cx, pt.y - cy)
                let prevDeg = touchWheelDeg
                if dist >= radius * 0.20 {
                    let curDeg = Float(atan2(pt.y - cy, pt.x - cx) * 180 / .pi)
                    let delta = wrapDeg180(curDeg - lastTouchAngleDeg)
                    lastTouchAngleDeg = curDeg
                    touchWheelDeg = max(-wheelMaxDeg, min(wheelMaxDeg, touchWheelDeg + delta))
                } else {
                    let dx = Float(pt.x - lastTouchX)
                    touchWheelDeg = max(-wheelMaxDeg, min(wheelMaxDeg, touchWheelDeg + (dx / max(1, Float(radius) * 0.72)) * 45))
                    lastTouchAngleDeg = Float(atan2(pt.y - cy, pt.x - cx) * 180 / .pi)
                }
                lastTouchX = pt.x
                displayWheelDeg = touchWheelDeg
                nextSteeringTouch = true
                nextSteer = max(-1, min(1, touchWheelDeg / max(1, wheelMaxDeg)))
                if (prevDeg < 0 && touchWheelDeg >= 0) || (prevDeg > 0 && touchWheelDeg <= 0) {
                    triggerLightTick()
                }
                let atLock = abs(touchWheelDeg) >= wheelMaxDeg - 0.5
                if atLock && !hitFullLockHaptic { triggerLightTick() }
                hitFullLockHaptic = atLock
                continue
            }

            if currentMode == Self.modeGamepad && touch == leftStickTouch, let stick = items.first(where: { $0.id == "lstick" }) {
                let r = max(1, itemHalfW(stick, w: w, h: h))
                var dx = pt.x - itemCx(stick, w: w, h: h)
                var dy = pt.y - itemCy(stick, w: w, h: h)
                let dist = hypot(dx, dy)
                if dist > r { dx *= r / dist; dy *= r / dist }
                nextLeftStickTouch = true
                nextLx = max(-1, min(1, Float(dx / r)))
                nextLy = max(-1, min(1, Float(dy / r)))
                continue
            }

            if currentMode == Self.modeGamepad && touch == rightStickTouch, let stick = items.first(where: { $0.id == "rstick" }) {
                let r = max(1, itemHalfW(stick, w: w, h: h))
                var dx = pt.x - itemCx(stick, w: w, h: h)
                var dy = pt.y - itemCy(stick, w: w, h: h)
                let dist = hypot(dx, dy)
                if dist > r { dx *= r / dist; dy *= r / dist }
                nextRx = max(-1, min(1, Float(dx / r)))
                nextRy = max(-1, min(1, Float(dy / r)))
                continue
            }

            if pt.y <= 46 { continue }
            guard let hit = findHitControl(pt.x, pt.y, w: w, h: h) else { continue }
            switch hit.kind {
            case .pedal:
                let val: Float
                if instantTapPedals {
                    val = 1.0
                } else {
                    let cy = itemCy(hit, w: w, h: h)
                    let hh = itemHalfH(hit, w: w, h: h)
                    let top = cy - hh, bottom = cy + hh
                    let hSpan = max(1, bottom - top)
                    let effBottom = bottom - hSpan * 0.06
                    let effTop = top + hSpan * 0.22
                    val = max(0.03, min(1.0, Float((effBottom - pt.y) / max(1, effBottom - effTop))))
                }
                if hit.id == "clutch" { nextClutch = max(nextClutch, val) }
                else if hit.id == "brake" { nextBrake = max(nextBrake, val) }
                else if hit.id == "gas" { nextThrottle = max(nextThrottle, val) }
            case .trigger:
                if hit.id == "lt" { nextLt = 1.0 }
                else if hit.id == "rt" { nextRt = 1.0 }
            case .dpad:
                let dx = pt.x - itemCx(hit, w: w, h: h)
                let dy = pt.y - itemCy(hit, w: w, h: h)
                if abs(dx) > abs(dy) {
                    if dx < 0 { nLt = true } else { nRt = true }
                } else {
                    if dy < 0 { nUp = true } else { nDn = true }
                }
            case .circleBtn, .pillBtn:
                if hit.buttonSlot >= 0 && hit.buttonSlot < 16 {
                    nextButtons[hit.buttonSlot] = true
                    if hit.buttonSlot == 15 { nextHandbrake = 1.0 }
                }
            default:
                break
            }
        }

        var newPress = false
        for b in 0..<16 where nextButtons[b] && !buttons[b] {
            newPress = true
            break
        }

        if nextSteeringTouch {
            steer = nextSteer
        } else {
            steer = max(-1, min(1, displayWheelDeg / max(1, wheelMaxDeg)))
        }
        steeringTouch = nextSteeringTouch
        leftStickTouched = nextLeftStickTouch
        throttle = nextThrottle
        brake = nextBrake
        clutch = nextClutch
        handbrake = nextHandbrake
        lx = (currentMode == Self.modeGamepad && gyroEnabled && !nextLeftStickTouch) ? steer : nextLx
        ly = nextLy
        rx = nextRx
        ry = nextRy
        lt = currentMode == Self.modeGamepad ? nextLt : nextBrake
        rt = currentMode == Self.modeGamepad ? nextRt : nextThrottle
        dpadUp = nUp; dpadDown = nDn; dpadLeft = nLt; dpadRight = nRt
        buttons = nextButtons

        notifyInputChanged()
        if newPress { triggerHaptic() }
        setNeedsDisplay()
    }

    // MARK: - Edit Touches
    private func handleEditBegan(_ touches: Set<UITouch>, w: CGFloat, h: CGFloat) {
        for t in touches {
            activeTouches[t] = t.location(in: self)
        }
        if activeTouches.count >= 2 {
            let pts = Array(activeTouches.values)
            if selectedItem == nil {
                let mid = CGPoint(x: (pts[0].x + pts[1].x) * 0.5, y: (pts[0].y + pts[1].y) * 0.5)
                selectedItem = findEditHitControl(mid.x, mid.y, w: w, h: h)
            }
            if let sel = selectedItem {
                editDragMode = 3
                pinchStartDist = max(20, hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y))
                pinchStartScale = sel.scale
                guideCenterX = false
                guideCenterY = false
                setNeedsDisplay()
            }
            return
        }

        guard let first = touches.first else { return }
        let pt = first.location(in: self)
        if pt.y <= 46 {
            handleEditHudTouch(pt.x, w: w)
            activeTouches.removeValue(forKey: first)
            return
        }

        if let sel = selectedItem {
            let cx = itemCx(sel, w: w, h: h)
            let cy = itemCy(sel, w: w, h: h)
            let hw = itemHalfW(sel, w: w, h: h)
            let hh = itemHalfH(sel, w: w, h: h)
            let hx = min(w - 10, cx + hw + 2)
            let hy = min(h - 10, cy + hh + 2)
            if hypot(pt.x - hx, pt.y - hy) <= 24 {
                editDragMode = 2
                editTouch = first
                resizeStartDist = max(16, hypot(pt.x - cx, pt.y - cy))
                resizeStartScale = sel.scale
                triggerLightTick()
                setNeedsDisplay()
                return
            }
        }

        if let hit = findEditHitControl(pt.x, pt.y, w: w, h: h) {
            selectedItem = hit
            editDragMode = 1
            editTouch = first
            dragOffset = CGPoint(x: itemCx(hit, w: w, h: h) - pt.x, y: itemCy(hit, w: w, h: h) - pt.y)
            triggerLightTick()
        } else {
            selectedItem = nil
            editDragMode = 0
            editTouch = nil
        }
        setNeedsDisplay()
    }

    private func handleEditMoved(_ touches: Set<UITouch>, w: CGFloat, h: CGFloat) {
        for t in touches where activeTouches[t] != nil {
            activeTouches[t] = t.location(in: self)
        }
        guard let sel = selectedItem else { return }

        if editDragMode == 3 && activeTouches.count >= 2 {
            let pts = Array(activeTouches.values)
            let curDist = hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y)
            sel.scale = clamp(pinchStartScale * (curDist / max(1, pinchStartDist)), Self.minScale, Self.maxScale)
            setNeedsDisplay()
            return
        }

        guard let t = editTouch, let pt = activeTouches[t] else { return }
        if editDragMode == 2 {
            let cx = itemCx(sel, w: w, h: h)
            let cy = itemCy(sel, w: w, h: h)
            let dist = max(10, hypot(pt.x - cx, pt.y - cy))
            sel.scale = clamp(resizeStartScale * (dist / resizeStartDist), Self.minScale, Self.maxScale)
            setNeedsDisplay()
            return
        }

        if editDragMode == 1 {
            let ph = playHeight(h)
            let hw = itemHalfW(sel, w: w, h: h)
            let hh = itemHalfH(sel, w: w, h: h)
            let minX = hw + 6 + leftInset, maxX = max(minX, w - hw - 6 - rightInset)
            let minY = playTop + hh + 4, maxY = max(minY, h - hh - 4)
            var rawCx = clamp(pt.x + dragOffset.x, minX, maxX)
            var rawCy = clamp(pt.y + dragOffset.y, minY, maxY)

            guideCenterX = false
            guideCenterY = false
            let midX = w * 0.5
            let midY = playTop + ph * 0.5
            if snapToGrid {
                if abs(rawCx - midX) <= 7 {
                    rawCx = midX
                    guideCenterX = true
                } else {
                    rawCx = clamp(round(rawCx / 8) * 8, minX, maxX)
                }
                if abs(rawCy - midY) <= 7 {
                    rawCy = midY
                    guideCenterY = true
                } else {
                    rawCy = clamp(playTop + round((rawCy - playTop) / 8) * 8, minY, maxY)
                }
            }

            sel.cxFrac = clamp(rawCx / max(1, w), 0.02, 0.98)
            sel.cyFrac = clamp((rawCy - playTop) / max(1, ph), 0.02, 0.98)
            setNeedsDisplay()
        }
    }

    private func handleEditEnded(_ touches: Set<UITouch>) {
        for t in touches {
            activeTouches.removeValue(forKey: t)
            if t == editTouch { editTouch = nil }
        }
        if activeTouches.isEmpty {
            if editDragMode != 0 {
                notifyLayoutChanged(activeLayoutId())
            }
            editDragMode = 0
            guideCenterX = false
            guideCenterY = false
            setNeedsDisplay()
        } else if editDragMode == 3 && activeTouches.count < 2 {
            notifyLayoutChanged(activeLayoutId())
            editDragMode = 0
            setNeedsDisplay()
        }
    }

    private func handleEditHudTouch(_ x: CGFloat, w: CGFloat) {
        let btns = editBarRects(w: w)
        triggerHaptic()
        if x >= btns[0].minX && x <= btns[0].maxX {
            if let sel = selectedItem {
                sel.scale = clamp(round((sel.scale - 0.1) * 20) / 20, Self.minScale, Self.maxScale)
                notifyLayoutChanged(activeLayoutId())
                setNeedsDisplay()
            }
        } else if x >= btns[1].minX && x <= btns[1].maxX {
            if let sel = selectedItem {
                sel.scale = clamp(round((sel.scale + 0.1) * 20) / 20, Self.minScale, Self.maxScale)
                notifyLayoutChanged(activeLayoutId())
                setNeedsDisplay()
            }
        } else if x >= btns[2].minX && x <= btns[2].maxX {
            snapToGrid.toggle()
            setNeedsDisplay()
        } else if x >= btns[3].minX && x <= btns[3].maxX {
            if let sel = selectedItem {
                sel.reset()
                notifyLayoutChanged(activeLayoutId())
                setNeedsDisplay()
            }
        } else if x >= btns[4].minX && x <= btns[4].maxX {
            resetLayout(activeLayoutId())
        } else if x >= btns[5].minX && x <= btns[5].maxX {
            setEditMode(false)
        }
    }

    private func handleHudTouch(_ x: CGFloat, w: CGFloat) {
        triggerHaptic()
        let r = hudLayout(w: w)
        if x <= r.badge.maxX {
            onOpenSetup?()
        } else if x >= r.edit.minX && x < r.center.minX {
            setEditMode(true)
        } else if x >= r.center.minX && x < r.setup.minX {
            onCalibrate?()
        } else if x >= r.setup.minX {
            onOpenSetup?()
        } else if x >= r.mode.minX && x <= r.mode.maxX {
            currentMode = currentMode == Self.modeRacepad ? Self.modeGamepad : Self.modeRacepad
            selectedItem = nil
            clearInput()
            onToggleMode?()
            setNeedsDisplay()
        }
    }

    private func findHitControl(_ x: CGFloat, _ y: CGFloat, w: CGFloat, h: CGFloat) -> ControlItem? {
        var bestBtn: ControlItem?
        var bestBtnDist = CGFloat.greatestFiniteMagnitude
        var bestLarge: ControlItem?
        var bestLargeDist = CGFloat.greatestFiniteMagnitude

        for item in activeItems() {
            let cx = itemCx(item, w: w, h: h)
            let cy = itemCy(item, w: w, h: h)
            let hw = itemHalfW(item, w: w, h: h)
            let hh = itemHalfH(item, w: w, h: h)
            let dx = x - cx, dy = y - cy
            let d2 = dx * dx + dy * dy

            switch item.kind {
            case .wheel:
                let r = hw * 1.18
                if d2 <= r * r && d2 < bestLargeDist {
                    bestLargeDist = d2
                    bestLarge = item
                }
            case .stickL, .stickR:
                let r = hw * 1.22
                if d2 <= r * r && d2 < bestLargeDist {
                    bestLargeDist = d2
                    bestLarge = item
                }
            case .circleBtn:
                let r = max(1, hw * 1.16)
                let norm = d2 / (r * r)
                if norm <= 1 && norm < bestBtnDist {
                    bestBtnDist = norm
                    bestBtn = item
                }
            case .dpad:
                let r = max(1, hw * 1.32)
                let norm = d2 / (r * r)
                if norm <= 1 && norm < bestBtnDist {
                    bestBtnDist = norm
                    bestBtn = item
                }
            default:
                let pad: CGFloat = item.kind == .pedal ? 5 : 3
                if x >= cx - hw - pad && x <= cx + hw + pad && y >= cy - hh - pad && y <= cy + hh + pad {
                    let norm = d2 / max(1, hw * hw + hh * hh)
                    if norm < bestBtnDist {
                        bestBtnDist = norm
                        bestBtn = item
                    }
                }
            }
        }
        return bestBtn ?? bestLarge
    }

    private func findEditHitControl(_ x: CGFloat, _ y: CGFloat, w: CGFloat, h: CGFloat) -> ControlItem? {
        var best: ControlItem?
        var bestScore = CGFloat.greatestFiniteMagnitude
        for item in activeItems() {
            let cx = itemCx(item, w: w, h: h)
            let cy = itemCy(item, w: w, h: h)
            let hw = itemHalfW(item, w: w, h: h) + 4
            let hh = itemHalfH(item, w: w, h: h) + 4
            if x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh {
                let d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                let weight: CGFloat = (item.kind == .wheel || item.kind == .pedal) ? 2.5 : 1.0
                if d2 * weight < bestScore {
                    bestScore = d2 * weight
                    best = item
                }
            }
        }
        return best
    }

    private func notifyInputChanged() {
        var st = ControllerInputState()
        st.mode = currentMode == Self.modeRacepad ? "racepad" : "gamepad"
        st.steer = steer
        st.steerDeg = roundf(steer * wheelMaxDeg * 10) / 10
        st.throttle = throttle
        st.brake = brake
        st.clutch = clutch
        st.handbrake = handbrake
        st.lx = lx
        st.ly = ly
        st.rx = rx
        st.ry = ry
        st.lt = lt
        st.rt = rt
        st.dpadUp = dpadUp
        st.dpadDown = dpadDown
        st.dpadLeft = dpadLeft
        st.dpadRight = dpadRight
        st.buttons = buttons
        st.touchingWheel = steeringTouch || blendingFromTouch
        st.touchingLeftStick = leftStickTouched
        onInputChanged?(st)
    }

    private func clearInput() {
        activeTouches.removeAll()
        steer = 0; sensorSteer = 0; touchWheelDeg = 0; displayWheelDeg = 0
        steerTouch = nil; leftStickTouch = nil; rightStickTouch = nil
        blendingFromTouch = false; lastAnimTime = 0
        stopDisplayLink()
        steeringTouch = false; leftStickTouched = false
        throttle = 0; brake = 0; clutch = 0; handbrake = 0
        lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        buttons = Array(repeating: false, count: 16)
        notifyInputChanged()
        setNeedsDisplay()
    }

    private func triggerHaptic() {
        hapticQueue.async { [weak self] in
            DispatchQueue.main.async {
                self?.impactMedium.impactOccurred()
            }
        }
    }

    private func triggerLightTick() {
        hapticQueue.async { [weak self] in
            DispatchQueue.main.async {
                self?.impactLight.impactOccurred()
            }
        }
    }

    private func wrapDeg180(_ deg: Float) -> Float {
        var d = deg
        while d > 180 { d -= 360 }
        while d <= -180 { d += 360 }
        return d
    }

    private func clamp(_ value: CGFloat, _ low: CGFloat, _ high: CGFloat) -> CGFloat {
        max(low, min(high, value))
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1.0) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: alpha
        )
    }
}
