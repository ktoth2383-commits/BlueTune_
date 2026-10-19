import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import UIKit

/// Firebase réteg iOS-en – ugyanaz a fiók, adatbázis és szabályok, mint Androidon.
/// Csak email + jelszó. Ha nincs GoogleService-Info.plist, `available` hamis és az app Firebase nélkül fut.
final class CloudRepo: ObservableObject {
    static let shared = CloudRepo()
    static let minPassword = 8

    struct Account: Equatable {
        let uid: String
        let email: String
        let verified: Bool
    }

    struct CommunityPreset: Identifiable {
        let id: String
        let name: String
        let author: String
        let description: String
        let settings: EqSettings
        var likes: Int
        let ownerUid: String
    }

    struct CloudError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    @Published private(set) var account: Account?
    let available: Bool

    private var authHandle: AuthStateDidChangeListenerHandle?

    private init() {
        if FirebaseApp.app() == nil,
           Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        }
        available = FirebaseApp.app() != nil
        guard available else { return }
        Auth.auth().languageCode = "hu"
        authHandle = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            DispatchQueue.main.async { self?.publish(user) }
        }
        publish(Auth.auth().currentUser)
    }

    private var db: Firestore { Firestore.firestore() }

    private func publish(_ user: User?) {
        if let u = user, !u.isAnonymous {
            account = Account(uid: u.uid, email: u.email ?? "", verified: u.isEmailVerified)
        } else {
            account = nil
        }
    }

    private func requireUser() throws -> User {
        guard available else { throw CloudError(message: "A Firebase nincs beállítva.") }
        guard let u = Auth.auth().currentUser, !u.isAnonymous else {
            throw CloudError(message: "Ehhez be kell jelentkezned.")
        }
        return u
    }

    // ------------------------------------------------------------ fiók

    func register(email: String, password: String, newsletter: Bool) async throws {
        try await guarded {
            guard password.count >= CloudRepo.minPassword else {
                throw CloudError(message: "A jelszó legalább \(CloudRepo.minPassword) karakter legyen.")
            }
            let clean = email.trimmingCharacters(in: .whitespaces)
            let result = try await Auth.auth().createUser(withEmail: clean, password: password)
            let user = result.user
            try? await user.sendEmailVerification()
            try await self.db.collection("users").document(user.uid).setData([
                "email": clean,
                "createdAt": FieldValue.serverTimestamp(),
                "privacyAcceptedAt": FieldValue.serverTimestamp(),
                "appVersion": "ios " + CloudRepo.appVersion,
            ])
            if newsletter { try await self.writeSubscription(user, on: true) }
            await MainActor.run { self.publish(user) }
        }
    }

    func login(email: String, password: String) async throws {
        try await guarded {
            let r = try await Auth.auth().signIn(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
            await MainActor.run { self.publish(r.user) }
        }
    }

    func sendPasswordReset(email: String) async throws {
        try await guarded {
            let clean = email.trimmingCharacters(in: .whitespaces)
            guard !clean.isEmpty else { throw CloudError(message: "Írd be az email címed.") }
            try await Auth.auth().sendPasswordReset(withEmail: clean)
        }
    }

    func resendVerification() async throws {
        try await guarded { try await self.requireUser().sendEmailVerification() }
    }

    /// Igaz, ha az email már megerősített.
    @discardableResult
    func reloadUser() async throws -> Bool {
        try await guarded {
            guard let u = Auth.auth().currentUser else { return false }
            try await u.reload()
            let fresh = Auth.auth().currentUser
            if fresh?.isEmailVerified == true {
                _ = try await fresh?.getIDTokenResult(forcingRefresh: true)
            }
            await MainActor.run { self.publish(fresh) }
            return fresh?.isEmailVerified == true
        }
    }

    func changePassword(current: String, new: String) async throws {
        try await guarded {
            guard new.count >= CloudRepo.minPassword else {
                throw CloudError(message: "Az új jelszó legalább \(CloudRepo.minPassword) karakter legyen.")
            }
            let u = try self.requireUser()
            let cred = EmailAuthProvider.credential(withEmail: u.email ?? "", password: current)
            _ = try await u.reauthenticate(with: cred)
            try await u.updatePassword(to: new)
        }
    }

    func signOut() {
        if available { try? Auth.auth().signOut() }
        account = nil
    }

    /// Fiók végleges törlése minden adattal (Google Play / App Store követelmény).
    func deleteAccount(password: String) async throws {
        try await guarded {
            let u = try self.requireUser()
            let cred = EmailAuthProvider.credential(withEmail: u.email ?? "", password: password)
            _ = try await u.reauthenticate(with: cred)
            let own = try await self.db.collection("community_presets").whereField("ownerUid", isEqualTo: u.uid).getDocuments()
            for d in own.documents { try await d.reference.delete() }
            try await self.db.collection("subscribers").document(u.uid).delete()
            let userRef = self.db.collection("users").document(u.uid)
            for doc in ["latest", "ios"] { try await userRef.collection("backup").document(doc).delete() }
            try await userRef.delete()
            try await u.delete()
            await MainActor.run { self.account = nil }
        }
    }

    // ------------------------------------------------------------ hírlevél

    func isSubscribed() async throws -> Bool {
        try await guarded {
            let u = try self.requireUser()
            return try await self.db.collection("subscribers").document(u.uid).getDocument().exists
        }
    }

    func setNewsletter(_ on: Bool) async throws {
        try await guarded { try await self.writeSubscription(try self.requireUser(), on: on) }
    }

    private func writeSubscription(_ u: User, on: Bool) async throws {
        let ref = db.collection("subscribers").document(u.uid)
        if on {
            try await ref.setData([
                "email": u.email ?? "",
                "consent": true,
                "consentAt": FieldValue.serverTimestamp(),
                "source": "ios " + CloudRepo.appVersion,
            ])
        } else {
            try await ref.delete()
        }
    }

    // ------------------------------------------------------------ mentés

    /// iOS mentés: users/{uid}/backup/ios (az androidos mentést nem írja felül)
    func backup() async throws {
        try await guarded {
            let u = try self.requireUser()
            let current = try Store.encoder.encode(AudioController.shared.settings)
            try await self.db.collection("users").document(u.uid).collection("backup").document("ios").setData([
                "presets": Store.shared.exportPresetsJSON(),
                "profiles": Store.shared.exportDevicesJSON(),
                "current": String(data: current, encoding: .utf8) ?? "{}",
                "device": UIDevice.current.model + " " + UIDevice.current.systemVersion,
                "updatedAt": FieldValue.serverTimestamp(),
            ])
        }
    }

    /// Visszaállítás: előbb az iOS-es mentés, ha nincs, az androidos (presetek + hangzás).
    func restore() async throws -> String {
        try await guarded {
            let u = try self.requireUser()
            let col = self.db.collection("users").document(u.uid).collection("backup")
            var doc = try await col.document("ios").getDocument()
            var fromAndroid = false
            if !doc.exists {
                doc = try await col.document("latest").getDocument()
                fromAndroid = true
            }
            guard doc.exists else { return "Még nincs felhős mentés." }
            let presets = (doc.get("presets") as? String).map { Store.shared.importPresets(json: $0) } ?? 0
            let profiles = fromAndroid ? 0 : ((doc.get("profiles") as? String).map { Store.shared.importDevices(json: $0) } ?? 0)
            if let cur = doc.get("current") as? String, let data = cur.data(using: .utf8),
               let s = try? Store.decoder.decode(EqSettings.self, from: data) {
                await MainActor.run {
                    let audio = AudioController.shared
                    audio.settings = audio.settings.withSound(of: s, name: s.presetName)
                }
            }
            return "Visszaállítva\(fromAndroid ? " (Android mentésből)" : ""): \(presets) preset, \(profiles) eszközprofil."
        }
    }

    func lastBackupInfo() async throws -> String? {
        try await guarded {
            let u = try self.requireUser()
            let doc = try await self.db.collection("users").document(u.uid).collection("backup").document("ios").getDocument()
            guard let ts = doc.get("updatedAt") as? Timestamp else { return nil }
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .short
            return f.string(from: ts.dateValue())
        }
    }

    // ------------------------------------------------------------ közösség

    func community() async throws -> [CommunityPreset] {
        try await guarded {
            _ = try self.requireUser()
            let snap = try await self.db.collection("community_presets")
                .order(by: "likes", descending: true).limit(to: 100).getDocuments()
            return snap.documents.compactMap { d -> CommunityPreset? in
                guard let json = d.get("settings") as? String, let data = json.data(using: .utf8),
                      let s = try? Store.decoder.decode(EqSettings.self, from: data) else { return nil }
                let author = (d.get("author") as? String) ?? ""
                return CommunityPreset(
                    id: d.documentID,
                    name: (d.get("name") as? String) ?? "Névtelen",
                    author: author.isEmpty ? "Névtelen" : author,
                    description: (d.get("description") as? String) ?? "",
                    settings: s,
                    likes: (d.get("likes") as? NSNumber)?.intValue ?? 0,
                    ownerUid: (d.get("ownerUid") as? String) ?? ""
                )
            }
        }
    }

    func publish(name: String, author: String, description: String, settings: EqSettings) async throws {
        try await guarded {
            let u = try self.requireUser()
            guard u.isEmailVerified else { throw CloudError(message: "Közzététel előtt erősítsd meg az email címed.") }
            let clean = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
            guard !clean.isEmpty else { throw CloudError(message: "Adj nevet a presetnek.") }
            var s = settings
            s.enabled = true
            s.presetName = clean
            let json = String(data: try Store.encoder.encode(s), encoding: .utf8) ?? "{}"
            _ = try await self.db.collection("community_presets").addDocument(data: [
                "name": clean,
                "author": String(author.trimmingCharacters(in: .whitespaces).prefix(30)),
                "description": String(description.trimmingCharacters(in: .whitespaces).prefix(200)),
                "settings": json,
                "likes": 0,
                "ownerUid": u.uid,
                "createdAt": FieldValue.serverTimestamp(),
            ])
        }
    }

    func like(_ id: String) async throws {
        try await guarded {
            _ = try self.requireUser()
            var liked = Set(UserDefaults.standard.stringArray(forKey: "likedCommunity") ?? [])
            guard !liked.contains(id) else { return }
            try await self.db.collection("community_presets").document(id).updateData(["likes": FieldValue.increment(Int64(1))])
            liked.insert(id)
            UserDefaults.standard.set(Array(liked), forKey: "likedCommunity")
        }
    }

    func isLiked(_ id: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: "likedCommunity") ?? []).contains(id)
    }

    func deleteCommunity(_ id: String) async throws {
        try await guarded {
            _ = try self.requireUser()
            try await self.db.collection("community_presets").document(id).delete()
        }
    }

    // ------------------------------------------------------------ hibák magyarul

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    private func guarded<T>(_ block: () async throws -> T) async throws -> T {
        do {
            return try await block()
        } catch let e as CloudError {
            throw e
        } catch {
            throw CloudError(message: CloudRepo.message(for: error as NSError))
        }
    }

    static func message(for e: NSError) -> String {
        if e.domain == "FIRAuthErrorDomain" {
            switch e.code {
            case 17008: return "Érvénytelen email cím."
            case 17004, 17009, 17011: return "Hibás email cím vagy jelszó."
            case 17007: return "Ezzel az email címmel már van fiók. Jelentkezz be, vagy kérj új jelszót."
            case 17026: return "Túl gyenge jelszó (legalább \(minPassword) karakter)."
            case 17005: return "Ez a fiók le van tiltva."
            case 17014: return "Biztonsági okból jelentkezz be újra."
            case 17010: return "Túl sok próbálkozás. Várj pár percet."
            case 17020: return "Nincs internetkapcsolat."
            case 17006: return "Az email+jelszó belépés nincs bekapcsolva a Firebase konzolban."
            default: return "Hitelesítési hiba (\(e.code))."
            }
        }
        if e.domain == "FIRFirestoreErrorDomain" {
            switch e.code {
            case 7: return "Ehhez nincs jogosultságod (lehet, hogy előbb meg kell erősítened az email címed)."
            case 14: return "A szerver most nem érhető el. Próbáld újra."
            default: return "Adatbázis hiba (\(e.code))."
            }
        }
        return e.localizedDescription
    }
}
