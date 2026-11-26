import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var mainController: MainViewController?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let win = UIWindow(frame: UIScreen.main.bounds)
        let vc = MainViewController()
        mainController = vc
        win.rootViewController = vc
        win.backgroundColor = UIColor(red: 7 / 255, green: 5 / 255, blue: 11 / 255, alpha: 1)
        win.makeKeyAndVisible()
        window = win
        application.isIdleTimerDisabled = true
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        mainController?.handleEnterBackground()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        mainController?.handleEnterForeground()
    }

    func applicationWillTerminate(_ application: UIApplication) {
        mainController?.handleTerminate()
    }
}
