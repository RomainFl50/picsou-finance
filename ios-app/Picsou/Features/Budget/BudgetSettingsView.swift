import SwiftUI

/// Minimal budget settings — cycle start day + AI categorization toggle only (YAGNI: no AI-mode/
/// confidence-threshold/logo tuning in v1, see the design doc). Autosave per control (iOS Settings
/// mental model), not a commit/cancel form.
struct BudgetSettingsView: View {
    let dataSource: BudgetDataSource

    @State private var settings: BudgetSettings?
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        Group {
            if isLoading {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let settings {
                Form {
                    Section {
                        Picker("Début du cycle", selection: cycleStartDayBinding) {
                            ForEach(1...28, id: \.self) { day in
                                Text("Le \(day) de chaque mois").tag(day)
                            }
                        }
                    } footer: {
                        Text("Le budget démarre le \(settings.cycleStartDay) de chaque mois.")
                    }
                    Section {
                        Toggle("Suggestions automatiques par IA", isOn: aiEnabledBinding)
                    }
                    if let error {
                        Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                    }
                }
                .tint(Theme.brand)
            }
        }
        .navigationTitle("Réglages du budget")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var cycleStartDayBinding: Binding<Int> {
        Binding(
            get: { settings?.cycleStartDay ?? 1 },
            set: { newValue in
                guard var updated = settings else { return }
                updated.cycleStartDay = newValue
                save(updated)
            }
        )
    }

    private var aiEnabledBinding: Binding<Bool> {
        Binding(
            get: { settings?.aiCategorizationEnabled ?? false },
            set: { newValue in
                guard var updated = settings else { return }
                updated.aiCategorizationEnabled = newValue
                save(updated)
            }
        )
    }

    private func load() async {
        isLoading = true
        settings = try? await dataSource.budgetSettings()
        isLoading = false
    }

    private func save(_ updated: BudgetSettings) {
        let previous = settings
        settings = updated
        error = nil
        Task {
            do {
                settings = try await dataSource.updateBudgetSettings(updated)
            } catch {
                settings = previous
                self.error = "Impossible d'enregistrer le réglage."
            }
        }
    }
}
