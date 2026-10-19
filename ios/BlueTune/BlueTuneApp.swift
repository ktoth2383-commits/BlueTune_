import SwiftUI

@main
struct BlueTuneApp: App {
    @StateObject private var audio = AudioController.shared
    @StateObject private var library = Library.shared
    @StateObject private var store = Store.shared
    @StateObject private var cloud = CloudRepo.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        UITabBar.appearance().unselectedItemTintColor = UIColor(white: 0.6, alpha: 1)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(audio)
                .environmentObject(library)
                .environmentObject(store)
                .environmentObject(cloud)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                audio.refreshRoute()
                library.scan()
                if let acc = cloud.account, !acc.verified {
                    Task { _ = try? await cloud.reloadUser() }
                }
            }
        }
    }
}

struct ContentView: View {
    var body: some View {
        TabView {
            PlayerView().tabItem { Label("Lejátszó", systemImage: "play.circle") }
            SoundView().tabItem { Label("Hangzás", systemImage: "slider.vertical.3") }
            PresetsView().tabItem { Label("Presetek", systemImage: "star") }
            DeviceView().tabItem { Label("Eszköz", systemImage: "headphones") }
            AccountView().tabItem { Label("Fiók", systemImage: "person.crop.circle") }
        }
    }
}
