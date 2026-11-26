import Darwin
import Foundation
import UIKit

struct ControllerInputState {
    var mode: String = "racepad"
    var steer: Float = 0
    var steerDeg: Float = 0
    var throttle: Float = 0
    var brake: Float = 0
    var clutch: Float = 0
    var handbrake: Float = 0
    var lx: Float = 0
    var ly: Float = 0
    var rx: Float = 0
    var ry: Float = 0
    var lt: Float = 0
    var rt: Float = 0
    var dpadUp: Bool = false
    var dpadDown: Bool = false
    var dpadLeft: Bool = false
    var dpadRight: Bool = 0 != 0
    var buttons: [Bool] = Array(repeating: false, count: 16)
    var touchingWheel: Bool = false
    var touchingLeftStick: Bool = false

    var isAnyControlActive: Bool {
        if touchingWheel || touchingLeftStick || throttle > 0.005 || brake > 0.005
            || clutch > 0.005 || handbrake > 0.005 || lt > 0.005 || rt > 0.005
            || abs(lx) > 0.01 || abs(ly) > 0.01 || abs(rx) > 0.01 || abs(ry) > 0.01
            || dpadUp || dpadDown || dpadLeft || dpadRight {
            return true
        }
        return buttons.contains(true)
    }
}

final class ControllerClient {
    enum ConnectionState {
        case disconnected(String)
        case connecting(String)
        case connected(host: String, displayName: String, badge: String)
        case reconnecting(host: String, displayName: String, reason: String)
    }

    private let tcpPort: UInt16 = 8766
    private let discoveryPort: UInt16 = 8767

    private let stateLock = NSLock()
    private var inputState = ControllerInputState()
    private var gyroSteer: Float = 0
    private var gyroEnabled: Bool = true
    private var wheelMaxDeg: Float = 90

    private let sendCondition = NSCondition()
    private var sendPending = false
    private var urgentEdgePending = false
    private var prevEdgeBits: Int = 0

    private let tcpWriteLock = NSLock()
    private var activeTcpFd: Int32 = -1
    private var activeUdpFd: Int32 = -1
    private var desiredConnected = false
    private var connectionToken: Int64 = 0
    private var udpAcked = false
    private var tcpAlive = false
    private var lastServerRxMs: Int64 = 0
    private var activeHost = ""
    private var activePin = ""
    private var discoveredHost = ""
    private var discoveredPin = ""
    private var discoveredName = ""

    private var discovering = false
    private var discoveryGen: Int64 = 0
    private var discoveryFd: Int32 = -1

    var onStateChanged: ((ConnectionState) -> Void)?
    var onDiscoveredPc: ((_ host: String, _ pin: String, _ hostname: String) -> Void)?
    var onDiscoveryUnavailable: (() -> Void)?
    var onConnectedCalibrate: (() -> Void)?

    func updateConfig(gyroEnabled: Bool, wheelMaxDeg: Float) {
        stateLock.lock()
        self.gyroEnabled = gyroEnabled
        self.wheelMaxDeg = wheelMaxDeg
        stateLock.unlock()
    }

    func updateGyroSteer(_ value: Float, notify: Bool) {
        stateLock.lock()
        gyroSteer = clamp(value, -1, 1)
        stateLock.unlock()
        if notify {
            sendCondition.lock()
            sendPending = true
            sendCondition.broadcast()
            sendCondition.unlock()
        }
    }

    func updateInput(_ next: ControllerInputState) {
        var edgeBits = 0
        for i in 0..<min(16, next.buttons.count) where next.buttons[i] {
            edgeBits |= (1 << i)
        }
        if next.dpadUp { edgeBits |= (1 << 16) }
        if next.dpadDown { edgeBits |= (1 << 17) }
        if next.dpadLeft { edgeBits |= (1 << 18) }
        if next.dpadRight { edgeBits |= (1 << 19) }
        if next.throttle > 0.01 { edgeBits |= (1 << 20) }
        if next.brake > 0.01 { edgeBits |= (1 << 21) }
        if next.clutch > 0.01 { edgeBits |= (1 << 22) }
        if next.handbrake > 0.01 { edgeBits |= (1 << 23) }
        if next.lt > 0.01 { edgeBits |= (1 << 24) }
        if next.rt > 0.01 { edgeBits |= (1 << 25) }
        if next.touchingWheel { edgeBits |= (1 << 26) }
        if next.touchingLeftStick { edgeBits |= (1 << 27) }

        stateLock.lock()
        inputState = next
        let isEdge = edgeBits != prevEdgeBits
        prevEdgeBits = edgeBits
        stateLock.unlock()

        sendCondition.lock()
        if isEdge { urgentEdgePending = true }
        sendPending = true
        sendCondition.broadcast()
        sendCondition.unlock()
    }

    func connect(host: String, pin: String) {
        activeHost = host
        activePin = pin
        desiredConnected = true
        connectionToken &+= 1
        let token = connectionToken
        notifyState(.connecting(host))
        Thread.detachNewThread { [weak self] in
            self?.runConnectionLoop(token: token)
        }
    }

    func disconnect() {
        connectionToken &+= 1
        desiredConnected = false
        tcpAlive = false
        udpAcked = false

        let oldTcp = activeTcpFd
        let oldUdp = activeUdpFd
        activeTcpFd = -1
        activeUdpFd = -1

        sendCondition.lock()
        sendCondition.broadcast()
        sendCondition.unlock()

        if oldTcp >= 0 || oldUdp >= 0 {
            Thread.detachNewThread { [weak self] in
                if oldTcp >= 0 {
                    _ = self?.sendTcpLine(fd: oldTcp, text: "{\"type\":\"bye\"}")
                    Darwin.close(oldTcp)
                }
                if oldUdp >= 0 {
                    Darwin.close(oldUdp)
                }
            }
        }
        notifyState(.disconnected("Disconnected"))
    }

    func startDiscovery() {
        guard !discovering else { return }
        discoveryGen &+= 1
        let gen = discoveryGen
        discovering = true
        Thread.detachNewThread { [weak self] in
            self?.runDiscoveryLoop(gen: gen)
        }
    }

    func stopDiscovery() {
        discoveryGen &+= 1
        discovering = false
        let fd = discoveryFd
        discoveryFd = -1
        if fd >= 0 {
            Darwin.close(fd)
        }
    }

    private func runDiscoveryLoop(gen: Int64) {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else {
            discovering = false
            DispatchQueue.main.async { [weak self] in self?.onDiscoveryUnavailable?() }
            return
        }
        discoveryFd = fd
        var yes: Int32 = 1
        Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 1, tv_usec: 200_000)
        Darwin.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = discoveryPort.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            Darwin.close(fd)
            if discoveryFd == fd { discoveryFd = -1 }
            discovering = false
            DispatchQueue.main.async { [weak self] in self?.onDiscoveryUnavailable?() }
            return
        }

        var buffer = [UInt8](repeating: 0, count: 2048)
        var senderAddr = sockaddr_in()
        while discovering && discoveryGen == gen {
            var senderLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &senderAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.recvfrom(fd, &buffer, buffer.count, 0, $0, &senderLen)
                }
            }
            if count <= 0 {
                let err = errno
                if err == EAGAIN || err == EWOULDBLOCK || err == EINTR {
                    continue
                }
                break
            }
            let data = Data(bytes: buffer, count: count)
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (json["service"] as? String) == "pocket-controller",
                  let pin = json["pin"] as? String,
                  pin.count == 6,
                  pin.allSatisfy({ $0.isNumber }) else {
                continue
            }
            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var inAddr = senderAddr.sin_addr
            Darwin.inet_ntop(AF_INET, &inAddr, &ipBuf, socklen_t(INET_ADDRSTRLEN))
            let foundHost = String(cString: ipBuf)
            let hostname = (json["hostname"] as? String) ?? "PC"
            discoveredHost = foundHost
            discoveredPin = pin
            discoveredName = hostname
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.discoveryGen == gen else { return }
                self.onDiscoveredPc?(foundHost, pin, hostname)
            }
        }

        if discoveryFd == fd { discoveryFd = -1 }
        Darwin.close(fd)
        if discoveryGen == gen { discovering = false }
    }

    private func runConnectionLoop(token: Int64) {
        let backoffsMs: [UInt32] = [180, 350, 650, 1000, 1500, 2000]
        var attempt = 0

        while desiredConnected && connectionToken == token {
            let host = activeHost
            var pin = activePin
            if !discoveredHost.isEmpty && discoveredHost == host && discoveredPin.count == 6 {
                pin = discoveredPin
                activePin = pin
            }

            var tcpFd: Int32 = -1
            var udpFd: Int32 = -1
            var pinRejected = false
            var lastError = "Connection lost"

            do {
                tcpFd = try connectTcpSocket(host: host, port: tcpPort, timeoutMs: 4000)
                activeTcpFd = tcpFd
                guard desiredConnected && connectionToken == token else {
                    throw NSError(domain: "PC", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cancelled"])
                }

                let deviceName = UIDevice.current.name.isEmpty ? "iPhone" : UIDevice.current.name
                let helloObj: [String: Any] = ["type": "hello", "pin": pin, "name": deviceName, "udp": true]
                let helloData = try JSONSerialization.data(withJSONObject: helloObj)
                guard let helloStr = String(data: helloData, encoding: .utf8),
                      sendTcpLine(fd: tcpFd, text: helloStr) else {
                    throw NSError(domain: "PC", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to send pairing handshake"])
                }

                var tcpLineBuffer = Data()
                guard let readyLine = readTcpLine(fd: tcpFd, buffer: &tcpLineBuffer, timeoutMs: 4500) else {
                    throw NSError(domain: "PC", code: 3, userInfo: [NSLocalizedDescriptionKey: "PC closed connection"])
                }
                guard let readyData = readyLine.data(using: .utf8),
                      let readyObj = try? JSONSerialization.jsonObject(with: readyData) as? [String: Any] else {
                    throw NSError(domain: "PC", code: 4, userInfo: [NSLocalizedDescriptionKey: "Invalid pairing response"])
                }
                if (readyObj["type"] as? String) != "ready" {
                    let msg = (readyObj["message"] as? String) ?? "Pairing rejected"
                    if msg.lowercased().contains("pin") { pinRejected = true }
                    throw NSError(domain: "PC", code: 5, userInfo: [NSLocalizedDescriptionKey: msg])
                }

                guard desiredConnected && connectionToken == token else {
                    throw NSError(domain: "PC", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cancelled"])
                }

                let sessionStr = (readyObj["session"] as? String) ?? ""
                let binaryEnabled = (readyObj["binary_udp"] as? Bool) ?? false
                let sessionBytes = binaryEnabled ? parseHex8(sessionStr) : nil
                let udpPortInt = (readyObj["udp_port"] as? Int) ?? 0

                udpAcked = false
                tcpAlive = true
                lastServerRxMs = nowMs()

                if udpPortInt > 0 && !sessionStr.isEmpty {
                    udpFd = (try? connectUdpSocket(host: host, port: UInt16(udpPortInt))) ?? -1
                    activeUdpFd = udpFd
                }

                attempt = 0
                let displayName = discoveredName.isEmpty ? host : discoveredName
                notifyState(.connected(host: host, displayName: displayName, badge: "250Hz TCP"))
                DispatchQueue.main.async { [weak self] in
                    self?.onConnectedCalibrate?()
                }

                let readerFd = tcpFd
                let initialBuf = tcpLineBuffer
                Thread.detachNewThread { [weak self] in
                    self?.runTcpReader(fd: readerFd, initialBuffer: initialBuf, token: token, host: host, displayName: displayName)
                }

                var udpSeq: UInt32 = 0
                var udpBuf = [UInt8](repeating: 0, count: 68)
                let connectedAt = nowMs()
                var lastTcpKeepalive = connectedAt
                var lastUdpProbe: Int64 = 0

                while desiredConnected && tcpAlive && connectionToken == token && activeTcpFd == tcpFd {
                    sendCondition.lock()
                    let hadUrgent = urgentEdgePending
                    urgentEdgePending = false
                    sendPending = false
                    sendCondition.unlock()

                    let sentAtNs = DispatchTime.now().uptimeNanoseconds
                    let curMs = nowMs()
                    if curMs - lastServerRxMs > 5500 {
                        throw NSError(domain: "PC", code: 6, userInfo: [NSLocalizedDescriptionKey: "Heartbeat timeout"])
                    }

                    stateLock.lock()
                    let snap = inputState
                    let gSteer = gyroSteer
                    let gEnabled = gyroEnabled
                    let wMax = wheelMaxDeg
                    stateLock.unlock()

                    let activeInput = hadUrgent || snap.isAnyControlActive

                    if udpAcked && udpFd >= 0 {
                        let sentOk: Bool
                        if let sBytes = sessionBytes {
                            udpSeq &+= 1
                            packBinaryUdp(buffer: &udpBuf, sessionBytes: sBytes, seq: udpSeq, snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax)
                            sentOk = Darwin.send(udpFd, &udpBuf, udpBuf.count, 0) == udpBuf.count
                        } else {
                            udpSeq &+= 1
                            let line = buildJsonPacket(snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax, session: sessionStr, seq: udpSeq)
                            let bytes = Array(line.utf8)
                            sentOk = bytes.withUnsafeBytes { Darwin.send(udpFd, $0.baseAddress, bytes.count, 0) == bytes.count }
                        }
                        if !sentOk {
                            udpAcked = false
                            let line = buildJsonPacket(snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax, session: nil, seq: nil)
                            if !sendTcpLine(fd: tcpFd, text: line) {
                                throw NSError(domain: "PC", code: 7, userInfo: [NSLocalizedDescriptionKey: "Socket disconnected"])
                            }
                        }
                        if curMs - lastTcpKeepalive >= 1000 {
                            if !sendTcpLine(fd: tcpFd, text: "{\"type\":\"ping\"}") {
                                throw NSError(domain: "PC", code: 8, userInfo: [NSLocalizedDescriptionKey: "TCP keepalive failed"])
                            }
                            lastTcpKeepalive = curMs
                        }
                    } else {
                        if udpFd >= 0 && !sessionStr.isEmpty && (curMs - connectedAt) < 3000 && (curMs - lastUdpProbe) >= 100 {
                            lastUdpProbe = curMs
                            if let sBytes = sessionBytes {
                                udpSeq &+= 1
                                packBinaryUdp(buffer: &udpBuf, sessionBytes: sBytes, seq: udpSeq, snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax)
                                _ = Darwin.send(udpFd, &udpBuf, udpBuf.count, 0)
                            } else {
                                udpSeq &+= 1
                                let line = buildJsonPacket(snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax, session: sessionStr, seq: udpSeq)
                                let bytes = Array(line.utf8)
                                _ = bytes.withUnsafeBytes { Darwin.send(udpFd, $0.baseAddress, bytes.count, 0) }
                            }
                        }
                        let line = buildJsonPacket(snap: snap, gyroSteer: gSteer, gyroEnabled: gEnabled, wheelMaxDeg: wMax, session: nil, seq: nil)
                        if !sendTcpLine(fd: tcpFd, text: line) {
                            throw NSError(domain: "PC", code: 7, userInfo: [NSLocalizedDescriptionKey: "Socket disconnected"])
                        }
                        lastTcpKeepalive = curMs
                    }

                    let elapsedMs = Int64((DispatchTime.now().uptimeNanoseconds - sentAtNs) / 1_000_000)
                    let targetMs: Int64 = activeInput ? 4 : 20
                    let waitMs = max(Int64(1), targetMs - elapsedMs)

                    sendCondition.lock()
                    if !urgentEdgePending && desiredConnected && tcpAlive && connectionToken == token {
                        if !sendPending {
                            sendCondition.wait(until: Date(timeIntervalSinceNow: Double(waitMs) / 1000.0))
                        } else if elapsedMs < 2 {
                            sendCondition.wait(until: Date(timeIntervalSinceNow: Double(2 - elapsedMs) / 1000.0))
                        }
                    }
                    sendCondition.unlock()
                }
            } catch {
                lastError = error.localizedDescription
            }

            tcpAlive = false
            udpAcked = false
            if activeTcpFd == tcpFd { activeTcpFd = -1 }
            if activeUdpFd == udpFd { activeUdpFd = -1 }
            if tcpFd >= 0 { Darwin.close(tcpFd) }
            if udpFd >= 0 { Darwin.close(udpFd) }

            if !desiredConnected || connectionToken != token {
                break
            }
            if pinRejected {
                desiredConnected = false
                notifyState(.disconnected(lastError))
                return
            }

            let displayName = discoveredName.isEmpty ? host : discoveredName
            notifyState(.reconnecting(host: host, displayName: displayName, reason: lastError))
            let delayMs = backoffsMs[min(attempt, backoffsMs.count - 1)]
            attempt += 1
            usleep(delayMs * 1000)
        }

        if connectionToken == token {
            notifyState(.disconnected("Disconnected"))
        }
    }

    private func runTcpReader(fd: Int32, initialBuffer: Data, token: Int64, host: String, displayName: String) {
        var buffer = initialBuffer
        defer {
            tcpAlive = false
            sendCondition.lock()
            sendCondition.broadcast()
            sendCondition.unlock()
        }
        while desiredConnected && connectionToken == token && activeTcpFd == fd {
            guard let line = readTcpLine(fd: fd, buffer: &buffer, timeoutMs: 4500) else {
                let savedErrno = errno
                if nowMs() - lastServerRxMs > 5500 || activeTcpFd != fd {
                    break
                }
                if savedErrno != EAGAIN && savedErrno != EWOULDBLOCK {
                    break
                }
                continue
            }
            lastServerRxMs = nowMs()
            guard let data = line.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let type = obj["type"] as? String else { continue }
            if type == "ping" {
                _ = sendTcpLine(fd: fd, text: "{\"type\":\"pong\"}")
            } else if type == "udp_ack" && ((obj["ok"] as? Bool) ?? true) {
                if !udpAcked {
                    udpAcked = true
                    notifyState(.connected(host: host, displayName: displayName, badge: "250Hz UDP"))
                }
            }
        }
    }

    private func connectTcpSocket(host: String, port: UInt16, timeoutMs: Int32) throws -> Int32 {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else {
            throw NSError(domain: "PC", code: 10, userInfo: [NSLocalizedDescriptionKey: "Socket creation failed"])
        }
        var yes: Int32 = 1
        Darwin.setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &yes, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))
        var tos: Int32 = 0x10
        Darwin.setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard Darwin.inet_pton(AF_INET, host, &addr.sin_addr) == 1 else {
            Darwin.close(fd)
            throw NSError(domain: "PC", code: 11, userInfo: [NSLocalizedDescriptionKey: "Invalid IPv4 address"])
        }

        let flags = Darwin.fcntl(fd, F_GETFL, 0)
        _ = Darwin.fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if res < 0 && errno != EINPROGRESS {
            Darwin.close(fd)
            throw NSError(domain: "PC", code: 12, userInfo: [NSLocalizedDescriptionKey: "Could not reach PC"])
        }
        if res != 0 {
            var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            let pr = Darwin.poll(&pfd, 1, timeoutMs)
            if pr <= 0 {
                Darwin.close(fd)
                throw NSError(domain: "PC", code: 13, userInfo: [NSLocalizedDescriptionKey: "Connection timed out"])
            }
            var soErr: Int32 = 0
            var soLen = socklen_t(MemoryLayout<Int32>.size)
            Darwin.getsockopt(fd, SOL_SOCKET, SO_ERROR, &soErr, &soLen)
            if soErr != 0 {
                Darwin.close(fd)
                throw NSError(domain: "PC", code: Int(soErr), userInfo: [NSLocalizedDescriptionKey: String(cString: strerror(soErr))])
            }
        }
        _ = Darwin.fcntl(fd, F_SETFL, flags)
        return fd
    }

    private func connectUdpSocket(host: String, port: UInt16) throws -> Int32 {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else {
            throw NSError(domain: "PC", code: 14, userInfo: [NSLocalizedDescriptionKey: "UDP creation failed"])
        }
        var tos: Int32 = 0x10
        Darwin.setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        guard Darwin.inet_pton(AF_INET, host, &addr.sin_addr) == 1 else {
            Darwin.close(fd)
            throw NSError(domain: "PC", code: 15, userInfo: [NSLocalizedDescriptionKey: "Invalid UDP host"])
        }
        let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if res != 0 {
            Darwin.close(fd)
            throw NSError(domain: "PC", code: 16, userInfo: [NSLocalizedDescriptionKey: "UDP connect failed"])
        }
        return fd
    }

    private func sendTcpLine(fd: Int32, text: String) -> Bool {
        guard fd >= 0 else { return false }
        tcpWriteLock.lock()
        defer { tcpWriteLock.unlock() }
        let bytes = Array((text + "\n").utf8)
        var offset = 0
        while offset < bytes.count {
            let sent = bytes.withUnsafeBytes { ptr -> Int in
                guard let base = ptr.baseAddress else { return -1 }
                return Darwin.send(fd, base.advanced(by: offset), bytes.count - offset, 0)
            }
            if sent <= 0 { return false }
            offset += sent
        }
        return true
    }

    private func readTcpLine(fd: Int32, buffer: inout Data, timeoutMs: Int32) -> String? {
        while true {
            if let nl = buffer.firstIndex(of: 0x0A) {
                let lineData = buffer.subdata(in: 0..<nl)
                buffer.removeSubrange(0...nl)
                return String(data: lineData, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let pr = Darwin.poll(&pfd, 1, timeoutMs)
            if pr == 0 {
                errno = EAGAIN
                return nil
            }
            if pr < 0 {
                errno = ECONNRESET
                return nil
            }
            var chunk = [UInt8](repeating: 0, count: 1024)
            let n = Darwin.recv(fd, &chunk, chunk.count, 0)
            if n <= 0 {
                errno = ECONNRESET
                return nil
            }
            buffer.append(chunk, count: n)
            if buffer.count > 8192 {
                errno = EMSGSIZE
                return nil
            }
        }
    }

    private func packBinaryUdp(
        buffer: inout [UInt8],
        sessionBytes: [UInt8],
        seq: UInt32,
        snap: ControllerInputState,
        gyroSteer: Float,
        gyroEnabled: Bool,
        wheelMaxDeg: Float
    ) {
        let wheelControlled = snap.touchingWheel || !gyroEnabled
        let steer = clamp(wheelControlled ? snap.steer : gyroSteer, -1, 1)
        let steerDeg = roundf(steer * wheelMaxDeg * 10) / 10
        let outLx: Float
        if snap.mode == "gamepad" && gyroEnabled && !snap.touchingLeftStick && !snap.touchingWheel {
            outLx = steer
        } else if snap.mode == "gamepad" && snap.touchingWheel {
            outLx = steer
        } else {
            outLx = snap.lx
        }

        let dpadMask: UInt8 = (snap.dpadUp ? 1 : 0)
            | (snap.dpadDown ? 2 : 0)
            | (snap.dpadLeft ? 4 : 0)
            | (snap.dpadRight ? 8 : 0)
        var btnMask: UInt16 = 0
        for i in 0..<min(16, snap.buttons.count) where snap.buttons[i] {
            btnMask |= (1 << i)
        }

        buffer[0] = 0x50 // P
        buffer[1] = 0x43 // C
        buffer[2] = 0x55 // U
        buffer[3] = 0x31 // 1
        for i in 0..<8 { buffer[4 + i] = sessionBytes[i] }
        writeUInt32BE(seq, into: &buffer, offset: 12)
        buffer[16] = snap.mode == "gamepad" ? 1 : 0

        let floats: [Float] = [
            steer, steerDeg,
            clamp(snap.throttle, 0, 1), clamp(snap.brake, 0, 1),
            clamp(snap.clutch, 0, 1), clamp(snap.handbrake, 0, 1),
            clamp(outLx, -1, 1), clamp(snap.ly, -1, 1),
            clamp(snap.rx, -1, 1), clamp(snap.ry, -1, 1),
            clamp(snap.lt, 0, 1), clamp(snap.rt, 0, 1),
        ]
        for (idx, value) in floats.enumerated() {
            writeFloat32BE(value, into: &buffer, offset: 17 + idx * 4)
        }
        buffer[65] = dpadMask
        buffer[66] = UInt8((btnMask >> 8) & 0xFF)
        buffer[67] = UInt8(btnMask & 0xFF)
    }

    private func buildJsonPacket(
        snap: ControllerInputState,
        gyroSteer: Float,
        gyroEnabled: Bool,
        wheelMaxDeg: Float,
        session: String?,
        seq: UInt32?
    ) -> String {
        let wheelControlled = snap.touchingWheel || !gyroEnabled
        let steer = clamp(wheelControlled ? snap.steer : gyroSteer, -1, 1)
        let steerDeg = roundf(steer * wheelMaxDeg * 10) / 10
        let outLx: Float
        if snap.mode == "gamepad" && gyroEnabled && !snap.touchingLeftStick && !snap.touchingWheel {
            outLx = steer
        } else if snap.mode == "gamepad" && snap.touchingWheel {
            outLx = steer
        } else {
            outLx = snap.lx
        }

        var parts = "{\"type\":\"input\""
        if let s = session, let q = seq {
            parts += ",\"s\":\"\(s)\",\"seq\":\(q)"
        }
        parts += ",\"mode\":\"\(snap.mode)\""
        parts += ",\"steer\":\(r4(steer)),\"steer_deg\":\(steerDeg)"
        parts += ",\"throttle\":\(r4(clamp(snap.throttle, 0, 1)))"
        parts += ",\"brake\":\(r4(clamp(snap.brake, 0, 1)))"
        parts += ",\"clutch\":\(r4(clamp(snap.clutch, 0, 1)))"
        parts += ",\"handbrake\":\(r4(clamp(snap.handbrake, 0, 1)))"
        parts += ",\"lx\":\(r4(clamp(outLx, -1, 1))),\"ly\":\(r4(clamp(snap.ly, -1, 1)))"
        parts += ",\"rx\":\(r4(clamp(snap.rx, -1, 1))),\"ry\":\(r4(clamp(snap.ry, -1, 1)))"
        parts += ",\"lt\":\(r4(clamp(snap.lt, 0, 1))),\"rt\":\(r4(clamp(snap.rt, 0, 1)))"
        parts += ",\"dpad\":{\"up\":\(snap.dpadUp),\"down\":\(snap.dpadDown),\"left\":\(snap.dpadLeft),\"right\":\(snap.dpadRight)}"
        parts += ",\"buttons\":["
        for i in 0..<16 {
            if i > 0 { parts += "," }
            parts += (i < snap.buttons.count && snap.buttons[i]) ? "true" : "false"
        }
        parts += "]}"
        return parts
    }

    private func notifyState(_ st: ConnectionState) {
        DispatchQueue.main.async { [weak self] in
            self?.onStateChanged?(st)
        }
    }

    private func parseHex8(_ hex: String) -> [UInt8]? {
        guard hex.count == 16 else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(8)
        var idx = hex.startIndex
        for _ in 0..<8 {
            let next = hex.index(idx, offsetBy: 2)
            guard let byte = UInt8(hex[idx..<next], radix: 16) else { return nil }
            out.append(byte)
            idx = next
        }
        return out
    }

    private func writeUInt32BE(_ val: UInt32, into buf: inout [UInt8], offset: Int) {
        buf[offset] = UInt8((val >> 24) & 0xFF)
        buf[offset + 1] = UInt8((val >> 16) & 0xFF)
        buf[offset + 2] = UInt8((val >> 8) & 0xFF)
        buf[offset + 3] = UInt8(val & 0xFF)
    }

    private func writeFloat32BE(_ val: Float, into buf: inout [UInt8], offset: Int) {
        writeUInt32BE(val.bitPattern, into: &buf, offset: offset)
    }

    private func clamp(_ v: Float, _ low: Float, _ high: Float) -> Float {
        v.isFinite ? max(low, min(high, v)) : 0
    }

    private func r4(_ v: Float) -> Float {
        roundf(v * 10000) / 10000
    }

    private func nowMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000.0)
    }
}
