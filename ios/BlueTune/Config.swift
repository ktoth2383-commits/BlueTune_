import Foundation

enum Config {
    /// KITÖLTENDŐ: ugyanaz a cím, mint Androidon a gradle.properties `bluetune.siteUrl` értéke
    /// (pl. https://felhasznalonev.github.io/BlueTune)
    static let siteURL = "https://example.com/bluetune"

    static var privacyURL: URL { URL(string: siteURL + "/privacy.html")! }
    static var deleteAccountURL: URL { URL(string: siteURL + "/delete-account.html")! }
}
