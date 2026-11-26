import CoreMotion
import Foundation

enum ResponseProfile: Int, CaseIterable {
    case f1Crisp = 0
    case balanced = 1
    case smooth = 2

    var title: String {
        switch self {
        case .f1Crisp: return "F1 Crisp"
        case .balanced: return "Balanced"
        case .smooth: return "Smooth"
        }
    }

    var params: (speedFilterHz: Float, minCutoffHz: Float, slope: Float, maxExtraHz: Float, deadDeg: Float, notifyStep: Float) {
        switch self {
        case .f1Crisp:
            return (24, 22, 0.55, 63, 0.35, 0.0004)
        case .balanced:
            return (16, 11, 0.28, 37, 0.80, 0.0010)
        case .smooth:
            return (12, 6, 0.16, 24, 1.30, 0.0015)
        }
    }
}

final class SteeringSensor {
    private let motionManager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "com.pocketcontroller.motion"
        q.maxConcurrentOperationCount = 1
        q.qualityOfService = .userInteractive
        return q
    }()

    private var hasLastQuat = false
    private var hasNeutral = false
    private var currentQuat = (w: Float(1), x: Float(0), y: Float(0), z: Float(0))
    private var neutralQuat = (w: Float(1), x: Float(0), y: Float(0), z: Float(0))
    private var unwrappedWheelRad: Float = 0
    private var smoothedWheelDeg: Float = 0
    private var prevTargetDeg: Float = 0
    private var angularSpeedDegSec: Float = 0
    private var lastTimestamp: TimeInterval = 0
    private var lastNotifiedSteer: Float = 0

    var wheelMaxDeg: Float = 90
    var profile: ResponseProfile = .f1Crisp
    var onSteerChanged: ((Float, Bool) -> Void)?

    var isAvailable: Bool {
        motionManager.isDeviceMotionAvailable
    }

    func start() {
        guard motionManager.isDeviceMotionAvailable else { return }
        hasNeutral = false
        hasLastQuat = false
        lastTimestamp = 0
        motionManager.deviceMotionUpdateInterval = 1.0 / 200.0
        motionManager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: queue) { [weak self] motion, _ in
            guard let self = self, let motion = motion else { return }
            self.process(motion)
        }
    }

    func stop() {
        motionManager.stopDeviceMotionUpdates()
    }

    func calibrate() {
        if hasLastQuat {
            neutralQuat = currentQuat
            hasNeutral = true
        } else {
            hasNeutral = false
        }
        unwrappedWheelRad = 0
        smoothedWheelDeg = 0
        prevTargetDeg = 0
        angularSpeedDegSec = 0
        lastTimestamp = 0
        lastNotifiedSteer = 0
    }

    private func process(_ motion: CMDeviceMotion) {
        let q = motion.attitude.quaternion
        var raw = (w: Float(q.w), x: Float(q.x), y: Float(q.y), z: Float(q.z))
        let norm = sqrtf(raw.w * raw.w + raw.x * raw.x + raw.y * raw.y + raw.z * raw.z)
        guard norm > 1e-6 else { return }
        raw.w /= norm
        raw.x /= norm
        raw.y /= norm
        raw.z /= norm

        if hasLastQuat {
            let dot = raw.w * currentQuat.w + raw.x * currentQuat.x + raw.y * currentQuat.y + raw.z * currentQuat.z
            if dot < 0 {
                raw = (-raw.w, -raw.x, -raw.y, -raw.z)
            }
        }
        currentQuat = raw
        hasLastQuat = true

        if !hasNeutral {
            neutralQuat = currentQuat
            hasNeutral = true
            unwrappedWheelRad = 0
            smoothedWheelDeg = 0
            prevTargetDeg = 0
            angularSpeedDegSec = 0
            lastTimestamp = motion.timestamp
            lastNotifiedSteer = 0
            onSteerChanged?(0, true)
            return
        }

        // Swing-twist around screen normal (+Z): clockwise phone rotation -> positive right steer.
        let rw = neutralQuat.w * currentQuat.w + neutralQuat.x * currentQuat.x
            + neutralQuat.y * currentQuat.y + neutralQuat.z * currentQuat.z
        let rz = neutralQuat.w * currentQuat.z - neutralQuat.x * currentQuat.y
            + neutralQuat.y * currentQuat.x - neutralQuat.z * currentQuat.w

        let twoPi = Float.pi * 2
        let rawTwist = wrapPi(-2 * atan2f(rz, rw))
        let turns = roundf((unwrappedWheelRad - rawTwist) / twoPi)
        unwrappedWheelRad = rawTwist + turns * twoPi

        let maxTrack = max(wheelMaxDeg * 1.5, 270)
        let targetDeg = max(-maxTrack, min(maxTrack, unwrappedWheelRad * 180 / Float.pi))

        let dt: Float
        if lastTimestamp == 0 {
            dt = 0.008
        } else {
            dt = max(0.001, min(0.05, Float(motion.timestamp - lastTimestamp)))
        }
        lastTimestamp = motion.timestamp

        let cfg = profile.params
        let instSpeed = abs(targetDeg - prevTargetDeg) / dt
        prevTargetDeg = targetDeg
        let speedAlpha = 1 - expf(-twoPi * cfg.speedFilterHz * dt)
        angularSpeedDegSec += (instSpeed - angularSpeedDegSec) * speedAlpha

        let cutoffHz = cfg.minCutoffHz + min(cfg.maxExtraHz, angularSpeedDegSec * cfg.slope)
        let smoothAlpha = 1 - expf(-twoPi * cutoffHz * dt)
        smoothedWheelDeg += (targetDeg - smoothedWheelDeg) * smoothAlpha

        let absDeg = abs(smoothedWheelDeg)
        let effectiveDeg: Float
        if absDeg <= cfg.deadDeg {
            effectiveDeg = 0
        } else {
            let sign: Float = smoothedWheelDeg >= 0 ? 1 : -1
            effectiveDeg = sign * min(wheelMaxDeg, (absDeg - cfg.deadDeg) * (wheelMaxDeg / max(1, wheelMaxDeg - cfg.deadDeg)))
        }

        let steer = max(-1, min(1, effectiveDeg / max(1, wheelMaxDeg)))
        let notify = abs(steer - lastNotifiedSteer) >= cfg.notifyStep || (steer == 0 && lastNotifiedSteer != 0)
        if notify {
            lastNotifiedSteer = steer
        }
        onSteerChanged?(steer, notify)
    }

    private func wrapPi(_ rad: Float) -> Float {
        var r = rad
        let twoPi = Float.pi * 2
        while r > Float.pi { r -= twoPi }
        while r <= -Float.pi { r += twoPi }
        return r
    }
}
