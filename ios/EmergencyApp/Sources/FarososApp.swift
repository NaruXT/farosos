import FirebaseCore
import SwiftUI

@main
struct FarososApp: App {
    @State private var isRegistered = KeychainParticipantStore.hasRegisteredProfile()

    init() {
        FirebaseApp.configure()
    }

    var body: some Scene {
        WindowGroup {
            if isRegistered {
                EmergencyView()
            } else {
                RegistrationView(onCompleted: { isRegistered = true })
            }
        }
    }
}
