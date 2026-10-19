import SwiftUI

struct AccountView: View {
    @EnvironmentObject var cloud: CloudRepo

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    if !cloud.available {
                        Card(title: "A fiókfunkciók nincsenek beállítva") {
                            Text("Ez a build Firebase nélkül készült. A lejátszó és az EQ teljesen működik; a fiók, "
                                 + "a felhős mentés és a közösségi presetek a Firebase beállítása után érhetők el "
                                 + "(UTMUTATO.md, iOS rész).")
                                .font(.footnote)
                                .foregroundStyle(Theme.muted)
                        }
                    } else if let acc = cloud.account {
                        AccountCard(account: acc)
                        BackupCard()
                        CommunityCard(account: acc)
                    } else {
                        AuthCard()
                    }
                    HStack {
                        Link("Adatvédelem", destination: Config.privacyURL)
                        Spacer()
                        Link("Fióktörlés weben", destination: Config.deleteAccountURL)
                    }
                    .font(.footnote)
                    .padding(.horizontal, 8)
                }
                .padding(16)
            }
            .background(Theme.bg)
            .navigationTitle("Fiók")
        }
    }
}

// ================================================================ belépés / regisztráció

private struct AuthCard: View {
    @EnvironmentObject var cloud: CloudRepo
    @State private var register = false
    @State private var email = ""
    @State private var password = ""
    @State private var password2 = ""
    @State private var newsletter = false // GDPR: alapból nincs bepipálva
    @State private var acceptPrivacy = false
    @State private var busy = false
    @State private var message: String?

    private var emailOk: Bool {
        let e = email.trimmingCharacters(in: .whitespaces)
        return e.contains("@") && e.contains(".") && e.count >= 5
    }

    private var canSubmit: Bool {
        !busy && emailOk && !password.isEmpty &&
            (!register || (password.count >= CloudRepo.minPassword && password == password2 && acceptPrivacy))
    }

    var body: some View {
        Card(title: register ? "Regisztráció" : "Bejelentkezés") {
            Picker("", selection: $register) {
                Text("Bejelentkezés").tag(false)
                Text("Regisztráció").tag(true)
            }
            .pickerStyle(.segmented)

            Text("Fiókkal mentheted a beállításaidat (telefonok között is), presetet oszthatsz meg, és értesítést "
                 + "kaphatsz az új verziókról. A lejátszó és az EQ fiók nélkül is működik.")
                .font(.caption)
                .foregroundStyle(Theme.muted)

            TextField("Email cím", text: $email)
                .textContentType(.username)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 10))
            SecureField("Jelszó" + (register ? " (min. \(CloudRepo.minPassword) karakter)" : ""), text: $password)
                .textContentType(register ? .newPassword : .password)
                .padding(12)
                .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 10))
            if register {
                SecureField("Jelszó még egyszer", text: $password2)
                    .textContentType(.newPassword)
                    .padding(12)
                    .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 10))
                Toggle(isOn: $acceptPrivacy) {
                    HStack(spacing: 4) {
                        Text("Elfogadom az").foregroundStyle(Theme.text)
                        Link("adatvédelmi tájékoztatót", destination: Config.privacyURL)
                        Text("(kötelező)").foregroundStyle(Theme.muted)
                    }
                    .font(.footnote)
                }
                .tint(Theme.accent)
                Toggle(isOn: $newsletter) {
                    Text("Kérek emailt az új verziókról és funkciókról (bármikor leiratkozhatok)")
                        .font(.footnote)
                        .foregroundStyle(Theme.text)
                }
                .tint(Theme.accent)
            }

            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let message { StatusText(text: message) }

            Button {
                run {
                    if register {
                        try await cloud.register(email: email, password: password, newsletter: newsletter)
                    } else {
                        try await cloud.login(email: email, password: password)
                    }
                }
            } label: {
                Text(register ? "Fiók létrehozása" : "Bejelentkezés").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .disabled(!canSubmit)

            if !register {
                Button("Elfelejtett jelszó") {
                    run {
                        try await cloud.sendPasswordReset(email: email)
                        message = "Ha van fiók ezzel a címmel, elküldtük a jelszó-visszaállító linket."
                    }
                }
                .font(.footnote)
                .disabled(busy)
            }
        }
    }

    private func run(_ block: @escaping () async throws -> Void) {
        busy = true
        message = nil
        Task { @MainActor in
            do { try await block() } catch { message = "Hiba: " + error.localizedDescription }
            busy = false
        }
    }
}

// ================================================================ fiók

private struct AccountCard: View {
    @EnvironmentObject var cloud: CloudRepo
    let account: CloudRepo.Account
    @State private var subscribed: Bool?
    @State private var busy = false
    @State private var message: String?
    @State private var showDelete = false
    @State private var deletePassword = ""
    @State private var showPassword = false
    @State private var currentPw = ""
    @State private var newPw = ""

    var body: some View {
        Card(title: "Fiók") {
            Text(account.email).font(.headline).foregroundStyle(Theme.text)
            if account.verified {
                Label("Email cím megerősítve", systemImage: "checkmark.seal.fill")
                    .font(.footnote).foregroundStyle(Theme.accent)
            } else {
                Text("⚠ Az email címed még nincs megerősítve. Nézd meg a postafiókod (a spam mappát is), és kattints a linkre.")
                    .font(.footnote).foregroundStyle(Theme.warn)
                HStack {
                    Button("Újraküldés") {
                        run { try await cloud.resendVerification(); message = "Megerősítő email elküldve." }
                    }
                    .buttonStyle(.bordered)
                    Button("Megerősítettem") {
                        run { message = try await cloud.reloadUser() ? "Megerősítve ✓" : "Még nincs megerősítve." }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
                }
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let message { StatusText(text: message) }

            Toggle(isOn: Binding(
                get: { subscribed ?? false },
                set: { on in
                    run {
                        try await cloud.setNewsletter(on)
                        subscribed = on
                        message = on ? "Feliratkoztál ✓" : "Leiratkoztál."
                    }
                }
            )) {
                VStack(alignment: .leading) {
                    Text("Frissítés-értesítők emailben").foregroundStyle(Theme.text)
                    Text(account.verified ? "Új verziók és funkciók – ritkán" : "Megerősítés után kezdünk küldeni")
                        .font(.caption).foregroundStyle(Theme.muted)
                }
            }
            .tint(Theme.accent)
            .disabled(busy || subscribed == nil)

            HStack {
                Button("Jelszócsere") { showPassword = true }.buttonStyle(.bordered)
                Spacer()
                Button("Kijelentkezés") { cloud.signOut() }.buttonStyle(.bordered)
            }
            Button("Fiók végleges törlése", role: .destructive) { showDelete = true }
                .font(.footnote)
        }
        .task(id: account.uid) {
            subscribed = try? await cloud.isSubscribed()
            if !account.verified { _ = try? await cloud.reloadUser() }
        }
        .alert("Fiók törlése", isPresented: $showDelete) {
            SecureField("Jelszó", text: $deletePassword)
            Button("Végleges törlés", role: .destructive) {
                let pw = deletePassword
                deletePassword = ""
                run { try await cloud.deleteAccount(password: pw) }
            }
            Button("Mégse", role: .cancel) { deletePassword = "" }
        } message: {
            Text("Törlődik a fiókod, a felhős mentésed, a közzétett presetjeid és a hírlevél-feliratkozásod. "
                 + "A telefonon lévő zenék és beállítások megmaradnak. Nem vonható vissza.")
        }
        .alert("Jelszócsere", isPresented: $showPassword) {
            SecureField("Jelenlegi jelszó", text: $currentPw)
            SecureField("Új jelszó (min. \(CloudRepo.minPassword))", text: $newPw)
            Button("Mentés") {
                let c = currentPw, n = newPw
                currentPw = ""; newPw = ""
                run { try await cloud.changePassword(current: c, new: n); message = "Jelszó megváltoztatva ✓" }
            }
            Button("Mégse", role: .cancel) { currentPw = ""; newPw = "" }
        }
    }

    private func run(_ block: @escaping () async throws -> Void) {
        busy = true
        message = nil
        Task { @MainActor in
            do { try await block() } catch { message = "Hiba: " + error.localizedDescription }
            busy = false
        }
    }
}

// ================================================================ mentés

private struct BackupCard: View {
    @EnvironmentObject var cloud: CloudRepo
    @EnvironmentObject var store: Store
    @State private var busy = false
    @State private var message: String?
    @State private var last: String?

    var body: some View {
        Card(title: "Felhős mentés") {
            Text("Mentésre kerül: \(store.custom.count) saját preset, az eszközprofilok és az aktuális hangzás. "
                 + "Visszaállításkor az androidos BlueTune mentéséből is átveszi a presetjeidet.")
                .font(.footnote).foregroundStyle(Theme.text)
            Text("Utolsó iOS mentés: \(last ?? "nincs")").font(.caption).foregroundStyle(Theme.muted)
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let message { StatusText(text: message) }
            HStack {
                Button("Mentés") {
                    run {
                        try await cloud.backup()
                        last = try await cloud.lastBackupInfo()
                        message = "Mentve a felhőbe ✓"
                    }
                }
                .buttonStyle(.borderedProminent).tint(Theme.accent)
                Button("Visszaállítás") { run { message = try await cloud.restore() } }
                    .buttonStyle(.bordered)
            }
            .disabled(busy)
        }
        .task { last = try? await cloud.lastBackupInfo() }
    }

    private func run(_ block: @escaping () async throws -> Void) {
        busy = true
        message = nil
        Task { @MainActor in
            do { try await block() } catch { message = "Hiba: " + error.localizedDescription }
            busy = false
        }
    }
}

// ================================================================ közösség

private struct CommunityCard: View {
    @EnvironmentObject var cloud: CloudRepo
    @EnvironmentObject var store: Store
    @EnvironmentObject var audio: AudioController
    let account: CloudRepo.Account
    @State private var items: [CloudRepo.CommunityPreset] = []
    @State private var busy = false
    @State private var message: String?
    @State private var showPublish = false

    var body: some View {
        Card(title: "Közösségi presetek", trailing: AnyView(
            Button("Frissítés") { load() }.font(.subheadline).disabled(busy)
        )) {
            Button {
                showPublish = true
            } label: {
                Label("Jelenlegi hangzásom közzététele", systemImage: "globe").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.violet)
            .disabled(!account.verified)
            if !account.verified {
                Text("Közzétételhez erősítsd meg az email címed.").font(.caption).foregroundStyle(Theme.muted)
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
            if let message { StatusText(text: message) }
            if items.isEmpty && !busy {
                Text("Még nincs közzétett preset – légy te az első!").font(.footnote).foregroundStyle(Theme.muted)
            }
            ForEach(items) { cp in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(cp.name).font(.subheadline.bold()).foregroundStyle(Theme.text)
                            Text("\(cp.author) · ♥ \(cp.likes)").font(.caption).foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        EqCurveView(settings: cp.settings, showGrid: false).frame(width: 90, height: 36)
                    }
                    if !cp.description.isEmpty {
                        Text(cp.description).font(.caption).foregroundStyle(Theme.text)
                    }
                    HStack(spacing: 14) {
                        Button("Alkalmaz") {
                            audio.settings = audio.settings.withSound(of: cp.settings, name: cp.name)
                            message = "Alkalmazva: \(cp.name)"
                        }
                        Button("Mentés") {
                            store.savePreset(name: cp.name, settings: cp.settings)
                            message = "Elmentve: \(cp.name)"
                        }
                        Button(cloud.isLiked(cp.id) ? "♥ Kedvelve" : "♡ Tetszik") {
                            run {
                                try await cloud.like(cp.id)
                                if let i = items.firstIndex(where: { $0.id == cp.id }) { items[i].likes += 1 }
                            }
                        }
                        .disabled(cloud.isLiked(cp.id))
                        if cp.ownerUid == account.uid {
                            Button("Törlés", role: .destructive) {
                                run {
                                    try await cloud.deleteCommunity(cp.id)
                                    items.removeAll { $0.id == cp.id }
                                }
                            }
                        }
                    }
                    .font(.footnote)
                }
                .padding(12)
                .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .task(id: account.uid) { load() }
        .sheet(isPresented: $showPublish) {
            PublishSheet(initialName: audio.settings.presetName ?? "", settings: audio.settings) { result in
                message = result
                showPublish = false
                load()
            }
            .environmentObject(cloud)
        }
    }

    private func load() {
        run { items = try await cloud.community() }
    }

    private func run(_ block: @escaping () async throws -> Void) {
        busy = true
        message = nil
        Task { @MainActor in
            do { try await block() } catch { message = "Hiba: " + error.localizedDescription }
            busy = false
        }
    }
}

/// Közzététel a közösségnek (a Presetek fülről is).
struct PublishSheet: View {
    @EnvironmentObject var cloud: CloudRepo
    let initialName: String
    let settings: EqSettings
    let onDone: (String) -> Void

    @State private var name = ""
    @State private var author = UserDefaults.standard.string(forKey: "authorName") ?? ""
    @State private var desc = ""
    @State private var busy = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    EqCurveView(settings: settings).frame(height: 80)
                }
                Section {
                    TextField("Preset neve", text: $name)
                    TextField("Szerző (becenév)", text: $author)
                    TextField("Leírás (pl. milyen fejhallgatóhoz)", text: $desc, axis: .vertical)
                } footer: {
                    Text("A becenév és a preset mindenki számára látható lesz. Az email címed nem.")
                }
                if let error { Section { Text(error).foregroundStyle(Theme.danger) } }
            }
            .navigationTitle("Közzététel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Mégse") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Közzététel") {
                        busy = true
                        Task { @MainActor in
                            do {
                                UserDefaults.standard.set(author, forKey: "authorName")
                                try await cloud.publish(name: String(name.prefix(40)), author: String(author.prefix(30)),
                                                        description: String(desc.prefix(200)), settings: settings)
                                onDone("Közzétéve: \(name) ✓")
                            } catch {
                                self.error = "Hiba: " + error.localizedDescription
                            }
                            busy = false
                        }
                    }
                    .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { if name.isEmpty { name = initialName } }
        }
    }
}
