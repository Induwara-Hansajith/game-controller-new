import UIKit

final class SetupViewController: UIViewController {
    var discoveredHost = ""
    var discoveredPin = ""
    var discoveredName = ""
    var savedHost = ""
    var savedPin = ""
    var statusText = "Enter your PC address and pairing PIN"
    var statusColor = UIColor(hex: 0x94a3b8)
    var isConnectedOrRetrying = false
    var gyroEnabled = true
    var sensorAvailable = true
    var wheelMaxDeg: Float = 90
    var responseProfile: ResponseProfile = .f1Crisp
    var instantTapPedals = false

    var onConnectTapped: ((_ host: String, _ pin: String) -> Void)?
    var onDisconnectTapped: (() -> Void)?
    var onGyroToggled: ((Bool) -> Void)?
    var onWheelMaxChanged: ((Float) -> Void)?
    var onProfileChanged: ((ResponseProfile) -> Void)?
    var onInstantPedalsToggled: ((Bool) -> Void)?
    var onCalibrateTapped: (() -> Void)?
    var onCustomizeTapped: (() -> Void)?
    var onResetLayoutsTapped: (() -> Void)?

    private let hostField = UITextField()
    private let pinField = UITextField()
    private let discoveredLabel = UILabel()
    private let quickBtn = UIButton(type: .system)
    private let connectBtn = UIButton(type: .system)
    private let statusLabel = UILabel()
    private var lockButtons: [UIButton] = []
    private var profileButtons: [UIButton] = []
    private let lockOptions: [Float] = [45, 60, 90, 135, 180]

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.65)

        let card = UIView()
        card.backgroundColor = UIColor(hex: 0x120e1c)
        card.layer.cornerRadius = 18
        card.layer.borderWidth = 1.2
        card.layer.borderColor = UIColor(hex: 0x292038).cgColor
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        let prefWidth = card.widthAnchor.constraint(equalTo: view.safeAreaLayoutGuide.widthAnchor, multiplier: 0.78)
        prefWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 480),
            prefWidth,
            card.heightAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.heightAnchor, multiplier: 0.92),
        ])

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: card.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: card.trailingAnchor),
        ])

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 18),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -18),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
        ])

        // Header
        let header = UIStackView()
        header.axis = .horizontal
        let titleLbl = UILabel()
        titleLbl.text = "Pocket Controller Setup"
        titleLbl.textColor = .white
        titleLbl.font = .systemFont(ofSize: 17, weight: .bold)
        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕", for: .normal)
        closeBtn.setTitleColor(UIColor(hex: 0x94a3b8), for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 18, weight: .bold)
        closeBtn.addTarget(self, action: #selector(closeSelf), for: .touchUpInside)
        header.addArrangedSubview(titleLbl)
        header.addArrangedSubview(closeBtn)
        stack.addArrangedSubview(header)

        // Discovery Card
        stack.addArrangedSubview(sectionLabel("AUTO-DETECTED PC COMPANION"))
        let discBox = innerCard()
        discoveredLabel.numberOfLines = 0
        discoveredLabel.font = .systemFont(ofSize: 12.5)
        discoveredLabel.textColor = UIColor(hex: 0xcbd5e1)
        discBox.addArrangedSubview(discoveredLabel)

        styleAccentButton(quickBtn, title: "⚡ Quick Connect")
        quickBtn.addTarget(self, action: #selector(quickConnectTapped), for: .touchUpInside)
        discBox.addArrangedSubview(quickBtn)
        stack.addArrangedSubview(discBox)

        // Manual Card
        stack.addArrangedSubview(sectionLabel("MANUAL CONNECTION"))
        let manBox = innerCard()
        styleField(hostField, placeholder: "PC IPv4 (e.g. 192.168.1.85)", keyboard: .URL, secure: false)
        hostField.text = !discoveredHost.isEmpty ? discoveredHost : savedHost
        manBox.addArrangedSubview(hostField)

        styleField(pinField, placeholder: "6-digit PIN", keyboard: .numberPad, secure: true)
        pinField.text = !discoveredPin.isEmpty ? discoveredPin : savedPin
        manBox.addArrangedSubview(pinField)

        styleAccentButton(connectBtn, title: isConnectedOrRetrying ? "Disconnect" : "Connect to PC")
        connectBtn.addTarget(self, action: #selector(connectOrDisconnect), for: .touchUpInside)
        manBox.addArrangedSubview(connectBtn)

        statusLabel.text = statusText
        statusLabel.textColor = statusColor
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        manBox.addArrangedSubview(statusLabel)
        stack.addArrangedSubview(manBox)

        // Controls Card
        stack.addArrangedSubview(sectionLabel("CONTROLS & CALIBRATION"))
        let ctrlBox = innerCard()

        let gyroSwitch = UISwitch()
        gyroSwitch.isOn = gyroEnabled
        gyroSwitch.isEnabled = sensorAvailable
        gyroSwitch.onTintColor = UIColor(hex: 0x7c3aed)
        gyroSwitch.addTarget(self, action: #selector(gyroChanged(_:)), for: .valueChanged)
        ctrlBox.addArrangedSubview(switchRow("Steering Wheel Rotation", toggle: gyroSwitch))

        let hint = UILabel()
        hint.text = sensorAvailable
            ? "Rotate iPhone left/right like a wheel; hides on-screen wheel for wide left Brake & right Gas pedals."
            : "No motion sensor found; drag the virtual steering wheel on screen."
        hint.textColor = UIColor(hex: 0x94a3b8)
        hint.font = .systemFont(ofSize: 11)
        hint.numberOfLines = 0
        ctrlBox.addArrangedSubview(hint)

        ctrlBox.addArrangedSubview(subLabel("Wheel Lock Range (each side)"))
        let lockRow = UIStackView()
        lockRow.distribution = .fillEqually
        lockRow.spacing = 6
        lockButtons = lockOptions.enumerated().map { idx, deg in
            let btn = UIButton(type: .system)
            btn.setTitle("\(Int(deg))°", for: .normal)
            btn.tag = idx
            btn.heightAnchor.constraint(equalToConstant: 34).isActive = true
            btn.addTarget(self, action: #selector(lockTapped(_:)), for: .touchUpInside)
            lockRow.addArrangedSubview(btn)
            return btn
        }
        ctrlBox.addArrangedSubview(lockRow)
        refreshLockButtons()

        ctrlBox.addArrangedSubview(subLabel("Steering & Response Feel"))
        let profRow = UIStackView()
        profRow.distribution = .fillEqually
        profRow.spacing = 6
        profileButtons = ResponseProfile.allCases.map { prof in
            let btn = UIButton(type: .system)
            btn.setTitle(prof.title, for: .normal)
            btn.tag = prof.rawValue
            btn.heightAnchor.constraint(equalToConstant: 34).isActive = true
            btn.addTarget(self, action: #selector(profileTapped(_:)), for: .touchUpInside)
            profRow.addArrangedSubview(btn)
            return btn
        }
        ctrlBox.addArrangedSubview(profRow)
        refreshProfileButtons()

        let tapSwitch = UISwitch()
        tapSwitch.isOn = instantTapPedals
        tapSwitch.onTintColor = UIColor(hex: 0x7c3aed)
        tapSwitch.addTarget(self, action: #selector(instantPedalsChanged(_:)), for: .valueChanged)
        ctrlBox.addArrangedSubview(switchRow("Instant 100% Tap Pedals (F1)", toggle: tapSwitch))

        let calBtn = outlineButton("⌖ Calibrate Neutral Center")
        calBtn.addTarget(self, action: #selector(calTapped), for: .touchUpInside)
        ctrlBox.addArrangedSubview(calBtn)

        let editBtn = outlineButton("✎ Customize Buttons (Rearrange & Resize)")
        editBtn.addTarget(self, action: #selector(editTapped), for: .touchUpInside)
        ctrlBox.addArrangedSubview(editBtn)

        let resetBtn = outlineButton("↺ Reset All Layouts to Default")
        resetBtn.addTarget(self, action: #selector(resetTapped), for: .touchUpInside)
        ctrlBox.addArrangedSubview(resetBtn)

        stack.addArrangedSubview(ctrlBox)
        refreshDiscoveryUi()
    }

    func updateConnectionUi(status: String, color: UIColor, active: Bool) {
        statusText = status
        statusColor = color
        isConnectedOrRetrying = active
        guard isViewLoaded else { return }
        statusLabel.text = status
        statusLabel.textColor = color
        connectBtn.setTitle(active ? "Disconnect" : "Connect to PC", for: .normal)
    }

    func updateDiscovery(host: String, pin: String, name: String) {
        discoveredHost = host
        discoveredPin = pin
        discoveredName = name
        guard isViewLoaded else { return }
        if (hostField.text ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            hostField.text = host
        }
        if (pinField.text ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            pinField.text = pin
        }
        refreshDiscoveryUi()
    }

    private func refreshDiscoveryUi() {
        if !discoveredHost.isEmpty && !discoveredPin.isEmpty {
            let name = discoveredName.isEmpty ? "PC" : discoveredName
            discoveredLabel.text = "Detected: \(name)\n\(discoveredHost) · PIN: \(discoveredPin)"
            quickBtn.isHidden = false
        } else {
            discoveredLabel.text = "Scanning Wi-Fi broadcast (port 8767)…\nKeep PC companion open on the same network."
            quickBtn.isHidden = true
        }
    }

    private func refreshLockButtons() {
        for (i, btn) in lockButtons.enumerated() {
            let sel = abs(wheelMaxDeg - lockOptions[i]) < 1
            styleChoicePill(btn, selected: sel)
        }
    }

    private func refreshProfileButtons() {
        for btn in profileButtons {
            styleChoicePill(btn, selected: btn.tag == responseProfile.rawValue)
        }
    }

    private func styleChoicePill(_ btn: UIButton, selected: Bool) {
        btn.backgroundColor = selected ? UIColor(hex: 0x7c3aed) : UIColor(hex: 0x120e1c)
        btn.setTitleColor(selected ? .white : UIColor(hex: 0xcbd5e1), for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 11.5, weight: .semibold)
        btn.layer.cornerRadius = 8
        btn.layer.borderWidth = 1
        btn.layer.borderColor = (selected ? UIColor(hex: 0xc4b5fd) : UIColor(hex: 0x292038)).cgColor
    }

    @objc private func closeSelf() { dismiss(animated: true) }

    @objc private func quickConnectTapped() {
        guard !discoveredHost.isEmpty, !discoveredPin.isEmpty else { return }
        hostField.text = discoveredHost
        pinField.text = discoveredPin
        onConnectTapped?(discoveredHost, discoveredPin)
    }

    @objc private func connectOrDisconnect() {
        if isConnectedOrRetrying {
            onDisconnectTapped?()
            return
        }
        let host = (hostField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let pin = (pinField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, pin.count == 6 else {
            updateConnectionUi(status: "Enter valid PC IP and 6-digit PIN", color: UIColor(hex: 0xf43f5e), active: false)
            return
        }
        onConnectTapped?(host, pin)
    }

    @objc private func gyroChanged(_ sender: UISwitch) {
        gyroEnabled = sender.isOn && sensorAvailable
        onGyroToggled?(gyroEnabled)
    }

    @objc private func lockTapped(_ sender: UIButton) {
        wheelMaxDeg = lockOptions[sender.tag]
        refreshLockButtons()
        onWheelMaxChanged?(wheelMaxDeg)
    }

    @objc private func profileTapped(_ sender: UIButton) {
        if let p = ResponseProfile(rawValue: sender.tag) {
            responseProfile = p
            refreshProfileButtons()
            onProfileChanged?(p)
        }
    }

    @objc private func instantPedalsChanged(_ sender: UISwitch) {
        instantTapPedals = sender.isOn
        onInstantPedalsToggled?(instantTapPedals)
    }

    @objc private func calTapped() {
        onCalibrateTapped?()
        updateConnectionUi(status: "Neutral center calibrated!", color: UIColor(hex: 0x34d399), active: isConnectedOrRetrying)
    }

    @objc private func editTapped() {
        dismiss(animated: true) { [weak self] in
            self?.onCustomizeTapped?()
        }
    }

    @objc private func resetTapped() {
        onResetLayoutsTapped?()
        updateConnectionUi(status: "All Racepad & Gamepad layouts reset to default!", color: UIColor(hex: 0x34d399), active: isConnectedOrRetrying)
    }

    private func sectionLabel(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text
        l.textColor = UIColor(hex: 0xa78bfa)
        l.font = .systemFont(ofSize: 10.5, weight: .bold)
        return l
    }

    private func subLabel(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text
        l.textColor = UIColor(hex: 0xcbd5e1)
        l.font = .systemFont(ofSize: 12, weight: .medium)
        return l
    }

    private func innerCard() -> UIStackView {
        let box = UIStackView()
        box.axis = .vertical
        box.spacing = 8
        box.isLayoutMarginsRelativeArrangement = true
        box.layoutMargins = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
        box.backgroundColor = UIColor(hex: 0x181324)
        box.layer.cornerRadius = 12
        box.layer.borderWidth = 1
        box.layer.borderColor = UIColor(hex: 0x2e253e).cgColor
        return box
    }

    private func switchRow(_ title: String, toggle: UISwitch) -> UIStackView {
        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .center
        let lbl = UILabel()
        lbl.text = title
        lbl.textColor = .white
        lbl.font = .systemFont(ofSize: 13.5, weight: .medium)
        row.addArrangedSubview(lbl)
        row.addArrangedSubview(toggle)
        return row
    }

    private func styleField(_ tf: UITextField, placeholder: String, keyboard: UIKeyboardType, secure: Bool) {
        tf.attributedPlaceholder = NSAttributedString(string: placeholder, attributes: [.foregroundColor: UIColor(hex: 0x64748b)])
        tf.textColor = .white
        tf.font = .systemFont(ofSize: 13.5)
        tf.keyboardType = keyboard
        tf.isSecureTextEntry = secure
        tf.autocorrectionType = .no
        tf.autocapitalizationType = .none
        tf.backgroundColor = UIColor(hex: 0x0f0c18)
        tf.layer.cornerRadius = 8
        tf.layer.borderWidth = 1
        tf.layer.borderColor = UIColor(hex: 0x292038).cgColor
        tf.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 10))
        tf.leftViewMode = .always
        tf.heightAnchor.constraint(equalToConstant: 40).isActive = true
    }

    private func styleAccentButton(_ btn: UIButton, title: String) {
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 13, weight: .bold)
        btn.backgroundColor = UIColor(hex: 0x7c3aed)
        btn.layer.cornerRadius = 8
        btn.heightAnchor.constraint(equalToConstant: 42).isActive = true
    }

    private func outlineButton(_ title: String) -> UIButton {
        let btn = UIButton(type: .system)
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(UIColor(hex: 0xc4b5fd), for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 12.5, weight: .medium)
        btn.backgroundColor = UIColor(hex: 0x1f192c)
        btn.layer.cornerRadius = 8
        btn.layer.borderWidth = 1
        btn.layer.borderColor = UIColor(hex: 0x473b5e).cgColor
        btn.heightAnchor.constraint(equalToConstant: 40).isActive = true
        return btn
    }
}
