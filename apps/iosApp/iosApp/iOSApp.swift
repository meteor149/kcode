import SwiftUI
import KcodeShared

@main
struct KcodeIOSApp: App {
    var body: some Scene {
        WindowGroup {
            KcodeRootView()
                .ignoresSafeArea(.keyboard)
        }
    }
}
