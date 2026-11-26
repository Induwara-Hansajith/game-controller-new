import UIKit

final class MainViewController: UIViewController {
    private let padView = ControllerPadView()
    private let sensor = SteeringSensor()
    private let client = ControllerClient()
    private weak var setupSheet: SetupViewController?

    private let prefs = UserDefaults.standard
    private var gyroEnabled = true
    private var wheelMaxDeg: Float = 90
    private var responseProfile: ResponseProfile = .f1Crisp
    private var instantTapPedals = false

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }

    override func loadView() {
        view = padView
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        wheelMaxDeg = (prefs.object(forKey: "wheel_max_deg") as? NSNumber)?.floatValue ?? 90
        gyroEnabled = sensor.isAvailable && ((prefs.object(forKey: "wheel_rotation_enabled") as? NSNumber)?.boolValue ?? true)
        responseProfile = ResponseProfile(rawValue: prefs.integer(forKey: "response_profile")) ?? .f1Crisp
        instantTapPedals = prefs.bool(forKey: "instant_tap_pedals")

        sensor.wheelMaxDeg = wheelMaxDeg
        sensor.profile = responseProfile
        padView.setWheelMaxDeg(wheelMaxDeg)
        padView.setGyroEnabled(gyroEnabled)
        padView.setInstantTapPedals(instantTapPedals)
        client.updateConfig(gyroEnabled: gyroEnabled, wheelMaxDeg: wheelMaxDeg)

        padView.importLayoutJson(ControllerPadView.layoutRacepad, json: prefs.string(forKey: "layout_racepad_v1") ?? "")
        padView.importLayoutJson(ControllerPadView.layoutRacepadGyro, json: prefs.string(forKey: "layout_racepad_gyro_v1") ?? "")
        padView.importLayoutJson(ControllerPadView.layoutGamepad, json: prefs.string(forKey: "layout_gamepad_v1") ?? "")

        wireCallbacks()
        sensor.start()
        client.startDiscovery()

        if (prefs.string(forKey: "last_host") ?? "").isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.openSetup()
            }
        }
    }

    private func wireCallbacks() {
        sensor.onSteerChanged = { [weak self] steer, notify in
            guard let self = self else { return }
            self.client.updateGyroSteer(steer, notify: notify)
            self.padView.setDisplayedGyroSteer(steer)
        }

        padView.onInputChanged = { [weak self] state in
            self?.client.updateInput(state)
        }
        padView.onOpenSetup = { [weak self] in
            self?.openSetup()
        }
        padView.onCalibrate = { [weak self] in
            self?.calibrate()
        }
        padView.onLayoutChanged = { [weak self] key, json in
            self?.prefs.set(json, forKey: "layout_\(key)_v1")
        }

        client.onConnectedCalibrate = { [weak self] in
            self?.calibrate()
        }
        client.onDiscoveredPc = { [weak self] host, pin, name in
            self?.setupSheet?.updateDiscovery(host: host, pin: pin, name: name)
        }
        client.onStateChanged = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .disconnected(let msg):
                self.padView.setConnectionStatus(connected: false, pcName: "DISCONNECTED", badge: "0Hz")
                self.setupSheet?.updateConnectionUi(status: msg, color: UIColor(hex: 0xf43f5e), active: false)
            case .connecting(let host):
                self.setupSheet?.updateConnectionUi(status: "Connecting to \(host)…", color: UIColor(hex: 0x38bdf8), active: true)
            case .connected(let host, let displayName, let badge):
                self.padView.setConnectionStatus(connected: true, pcName: displayName, badge: badge)
                self.setupSheet?.updateConnectionUi(status: "Connected to \(host)", color: UIColor(hex: 0x10b981), active: true)
                self.setupSheet?.dismiss(animated: true)
            case .reconnecting(let host, let displayName, let reason):
                self.padView.setReconnectingStatus(pcName: displayName)
                self.setupSheet?.updateConnectionUi(status: "Reconnecting to \(host) (\(reason))…", color: UIColor(hex: 0xf59e0b), active: true)
            }
        }
    }

    private func calibrate() {
        sensor.calibrate()
        padView.resetSteering()
        client.updateGyroSteer(0, notify: true)
    }

    private func openSetup() {
        guard presentedViewController == nil else { return }
        let sheet = SetupViewController()
        sheet.modalPresentationStyle = .overFullScreen
        sheet.modalTransitionStyle = .crossDissolve
        sheet.savedHost = prefs.string(forKey: "last_host") ?? ""
        sheet.savedPin = prefs.string(forKey: "last_pin") ?? ""
        sheet.gyroEnabled = gyroEnabled
        sheet.sensorAvailable = sensor.isAvailable
        sheet.wheelMaxDeg = wheelMaxDeg
        sheet.responseProfile = responseProfile
        sheet.instantTapPedals = instantTapPedals

        sheet.onConnectTapped = { [weak self] host, pin in
            guard let self = self else { return }
            self.prefs.set(host, forKey: "last_host")
            self.prefs.set(pin, forKey: "last_pin")
            self.client.connect(host: host, pin: pin)
        }
        sheet.onDisconnectTapped = { [weak self] in
            self?.client.disconnect()
        }
        sheet.onGyroToggled = { [weak self] enabled in
            guard let self = self else { return }
            self.gyroEnabled = enabled
            self.prefs.set(enabled, forKey: "wheel_rotation_enabled")
            self.padView.setGyroEnabled(enabled)
            self.client.updateConfig(gyroEnabled: enabled, wheelMaxDeg: self.wheelMaxDeg)
            if enabled { self.calibrate() }
        }
        sheet.onWheelMaxChanged = { [weak self] deg in
            guard let self = self else { return }
            self.wheelMaxDeg = deg
            self.prefs.set(deg, forKey: "wheel_max_deg")
            self.sensor.wheelMaxDeg = deg
            self.padView.setWheelMaxDeg(deg)
            self.client.updateConfig(gyroEnabled: self.gyroEnabled, wheelMaxDeg: deg)
        }
        sheet.onProfileChanged = { [weak self] prof in
            guard let self = self else { return }
            self.responseProfile = prof
            self.prefs.set(prof.rawValue, forKey: "response_profile")
            self.sensor.profile = prof
        }
        sheet.onInstantPedalsToggled = { [weak self] instant in
            guard let self = self else { return }
            self.instantTapPedals = instant
            self.prefs.set(instant, forKey: "instant_tap_pedals")
            self.padView.setInstantTapPedals(instant)
        }
        sheet.onCalibrateTapped = { [weak self] in
            self?.calibrate()
        }
        sheet.onCustomizeTapped = { [weak self] in
            self?.padView.setEditMode(true)
        }
        sheet.onResetLayoutsTapped = { [weak self] in
            self?.padView.resetAllLayouts()
        }

        setupSheet = sheet
        present(sheet, animated: true)
    }

    func handleEnterBackground() {
        client.stopDiscovery()
        sensor.stop()
        client.disconnect()
    }

    func handleEnterForeground() {
        sensor.start()
        client.startDiscovery()
    }

    func handleTerminate() {
        client.stopDiscovery()
        sensor.stop()
        client.disconnect()
    }
}
