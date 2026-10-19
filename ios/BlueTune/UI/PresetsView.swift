import SwiftUI
import UniformTypeIdentifiers

struct PresetsView: View {
    @EnvironmentObject var audio: AudioController
    @EnvironmentObject var store: Store
    @EnvironmentObject var cloud: CloudRepo
    @State private var showImport = false
    @State private var importText = ""
    @State private var message: String?
    @State private var publishing: Preset?

    var body: some View {
        NavigationStack {
            List {
                if let message {
                    Section { StatusText(text: message) }
                }
                Section {
                    if store.custom.isEmpty {
                        Text("Még nincs saját preset. A Hangzás fülön állítsd be, majd „Mentés presetként”.")
                            .font(.footnote)
                            .foregroundStyle(Theme.muted)
                    }
                    ForEach(store.custom) { p in
                        row(p)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { store.deletePreset(p.name) } label: {
                                    Label("Törlés", systemImage: "trash")
                                }
                                if cloud.account?.verified == true {
                                    Button { publishing = p } label: { Label("Megosztás", systemImage: "globe") }
                                        .tint(Theme.violet)
                                }
                            }
                    }
                } header: {
                    Text("Saját presetek (\(store.custom.count))")
                } footer: {
                    Text("Balra húzva: törlés / közzététel a közösségnek.")
                }

                Section("Beépített presetek") {
                    ForEach(BuiltInPresets.all) { p in row(p) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
            .navigationTitle("Presetek")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        ShareLink(item: store.exportPresetsJSON(), preview: SharePreview("BlueTune presetek")) {
                            Label("Exportálás (megosztás)", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            UIPasteboard.general.string = store.exportPresetsJSON()
                            message = "Kimásolva a vágólapra."
                        } label: { Label("Másolás vágólapra", systemImage: "doc.on.doc") }
                        Button {
                            importText = UIPasteboard.general.string ?? ""
                            showImport = true
                        } label: { Label("Importálás", systemImage: "square.and.arrow.down") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .sheet(isPresented: $showImport) { importSheet }
            .sheet(item: $publishing) { p in
                PublishSheet(initialName: p.name, settings: p.settings) { result in
                    message = result
                    publishing = nil
                }
                .environmentObject(cloud)
            }
        }
    }

    private func row(_ p: Preset) -> some View {
        Button {
            audio.settings = audio.settings.withSound(of: p.settings, name: p.name)
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text((audio.settings.presetName == p.name ? "✓ " : "") + p.name)
                        .foregroundStyle(audio.settings.presetName == p.name ? Theme.accent : Theme.text)
                    Text(p.settings.summary()).font(.caption).foregroundStyle(Theme.muted)
                }
                Spacer()
                EqCurveView(settings: p.settings, showGrid: false).frame(width: 84, height: 34)
            }
        }
        .listRowBackground(Theme.card)
    }

    private var importSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Illeszd be a telefonos / Windows-os BlueTune-ból exportált JSON-t:")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                TextEditor(text: $importText)
                    .font(.system(.footnote, design: .monospaced))
                    .scrollContentBackground(.hidden)
                    .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 12))
                    .frame(minHeight: 220)
            }
            .padding(16)
            .background(Theme.bg)
            .navigationTitle("Importálás")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Mégse") { showImport = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Importálás") {
                        let n = store.importPresets(json: importText)
                        message = n > 0 ? "\(n) preset importálva." : "Nem találtam érvényes presetet."
                        showImport = false
                    }
                    .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
