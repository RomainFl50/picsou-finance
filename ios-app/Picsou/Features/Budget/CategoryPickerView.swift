import SwiftUI

/// A category suggestion pinned at the top of `CategoryPickerView`, above the alphabetical list.
struct CategorySuggestion {
    let categoryId: Int64
    let categoryName: String
    let categoryColor: String
    let confidence: Int?
}

/// Reusable single-select category picker, pushed (never presented as a nested sheet — see
/// docs/briefs/2026-07-22-budget-ios-redesign-design.md §0.3) from the categorization inbox, the
/// transaction detail sheet, the spending drill-down, and the envelope form. Read-only against
/// `GET /api/categories` — no category-tree management (create/rename/archive) in this app.
struct CategoryPickerView: View {
    let categories: [Category]
    let current: Int64?
    var suggestion: CategorySuggestion? = nil
    let onPick: (Category) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var pickable: [Category] {
        categories.filter { $0.pickable }.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    private var filtered: [Category] {
        guard !query.isEmpty else { return pickable }
        return pickable.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        List {
            if let suggestion, query.isEmpty {
                Section {
                    Button {
                        pick(id: suggestion.categoryId, name: suggestion.categoryName)
                    } label: {
                        HStack {
                            AISuggestionChip(categoryColor: suggestion.categoryColor,
                                              categoryName: suggestion.categoryName,
                                              confidence: suggestion.confidence)
                            Spacer()
                            Text("Utiliser").font(Theme.font(13, .semibold)).foregroundStyle(Theme.brand)
                        }
                    }
                } header: {
                    SectionLabel("Suggestion IA")
                }
            }
            Section {
                ForEach(filtered) { category in
                    Button {
                        pick(id: category.id, name: category.name)
                    } label: {
                        HStack {
                            Circle().fill(Color.account(category.color)).frame(width: 10, height: 10)
                            Text(category.name).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                            Spacer()
                            if current == category.id {
                                Image(systemName: "checkmark").foregroundStyle(Theme.brand).fontWeight(.semibold)
                            }
                        }
                        .frame(minHeight: 44)
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Rechercher une catégorie")
        .navigationTitle("Catégorie")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func pick(id: Int64, name: String) {
        guard let category = categories.first(where: { $0.id == id }) else { return }
        onPick(category)
        dismiss()
    }
}
