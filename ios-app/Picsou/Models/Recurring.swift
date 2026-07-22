import Foundation

enum RecurringCadence: String, Codable {
    case weekly = "WEEKLY"
    case biweekly = "BIWEEKLY"
    case monthly = "MONTHLY"
    case quarterly = "QUARTERLY"
    case yearly = "YEARLY"

    var label: String {
        switch self {
        case .weekly: return "Hebdomadaire"
        case .biweekly: return "Toutes les 2 semaines"
        case .monthly: return "Mensuel"
        case .quarterly: return "Trimestriel"
        case .yearly: return "Annuel"
        }
    }
}

enum RecurringStatus: String, Decodable {
    case suggested = "SUGGESTED"
    case confirmed = "CONFIRMED"
    case ignored = "IGNORED"
}

/// Urgency of a series' next due date, computed server-side at read time (never stored).
enum RecurringRuntimeStatus: String, Decodable {
    case stale = "STALE"
    case late = "LATE"
    case dueSoon = "DUE_SOON"
    case scheduled = "SCHEDULED"
}

enum RecurringActivityType: String, Decodable {
    case autoConfirmed = "AUTO_CONFIRMED"
    case priceChange = "PRICE_CHANGE"
}

/// Mirrors backend `RecurringSeriesResponse` (GET /api/recurring).
struct RecurringSeries: Decodable, Identifiable, Equatable {
    let id: Int64
    let label: String
    let counterparty: String?
    let expectedAmount: Decimal
    let cadence: RecurringCadence
    let status: RecurringStatus
    let nextDueDate: String?
    let lastSeenDate: String?
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?
    let confidence: Decimal?
    let variable: Bool
    let amountMin: Decimal?
    let amountMax: Decimal?
    let previousAmount: Decimal?
    let priceChangedAt: String?
    let autoConfirmed: Bool
    let runtimeStatus: RecurringRuntimeStatus

    var priceIncreased: Bool { previousAmount != nil && priceChangedAt != nil }
}

/// Mirrors backend `RecurringActivityResponse` (GET /api/recurring/activity) — the "what changed"
/// feed. No `id` field on the wire; synthesized from series id + occurrence date (unique per feed).
struct RecurringActivity: Decodable, Identifiable, Equatable {
    let seriesId: Int64
    let label: String
    let type: RecurringActivityType
    let occurredOn: String
    let expectedAmount: Decimal
    let previousAmount: Decimal?
    let cadence: RecurringCadence
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?

    var id: String { "\(seriesId)-\(type.rawValue)-\(occurredOn)" }
}

/// Mirrors backend `RecurringOccurrenceResponse` (GET /api/recurring/calendar) — one projected
/// charge. No `id` field on the wire; synthesized from series id + due date.
struct RecurringOccurrence: Decodable, Identifiable, Equatable {
    let seriesId: Int64
    let label: String
    let counterparty: String?
    let expectedAmount: Decimal
    let dueDate: String
    let categoryId: Int64?
    let categoryName: String?
    let categoryColor: String?

    var id: String { "\(seriesId)-\(dueDate)" }
    var day: Date? { DateParsing.localDate.date(from: dueDate) }
}
