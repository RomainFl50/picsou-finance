import Foundation

/// Mirrors backend `AccountResponse` (GET /api/accounts/{id}). Money as `Decimal`. The Java
/// `isManual` record field serializes as the JSON key `isManual` -- Jackson does NOT strip the
/// `is` prefix from a record component's own name (only from classic `getX()`/`isX()` accessor
/// *method* names on a plain bean), so this is the literal key on the wire. Verified live against
/// a running backend (POST/GET /api/accounts all return `"isManual"`, never `"manual"`) after this
/// exact drift broke every real account decode -- the old `manual` mapping only ever "worked"
/// because `PicsouTests` mocked its own (wrong) assumption instead of the real backend shape.
struct Account: Decodable, Identifiable, Equatable {
    let id: Int64
    let name: String
    let accountType: String
    let provider: String?
    let currency: String?
    let currentBalance: Decimal?
    let currentBalanceEur: Decimal
    let lastSyncedAt: String?
    let manual: Bool
    let color: String?
    let ticker: String?
    let parentAccountId: Int64?
    let debt: DebtInfo?
    let hidden: Bool

    enum CodingKeys: String, CodingKey {
        case id, name
        case accountType = "type"
        case provider, currency, currentBalance, currentBalanceEur, lastSyncedAt
        case manual = "isManual"
        case color, ticker, parentAccountId, debt, hidden
    }

    var type: AccountType { AccountType(raw: accountType) }
    var isInvestment: Bool { [.pea, .compteTitres, .crypto].contains(type) }
    var lastSyncedDate: Date? { lastSyncedAt.flatMap { ISO8601DateFormatter().date(from: $0) } }
}

struct DebtInfo: Decodable, Equatable {
    let borrowedAmount: Decimal?
    let interestRate: Decimal?
    let monthlyPayment: Decimal?
    let lenderName: String?
    let startDate: String?
    let endDate: String?
}

/// Mirrors backend `HoldingResponse` (GET /api/accounts/{id}/holdings).
struct Holding: Decodable, Identifiable, Equatable {
    let ticker: String
    let name: String?
    let quantity: Decimal?
    let currentValueEur: Decimal?
    let pnlEur: Decimal?
    let pnlPercent: Decimal?
    let priceUpdatedAt: String?

    var id: String { ticker }
}
