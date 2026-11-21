import Foundation

struct RoutePart: Identifiable {
    enum Kind: String {
        case moving = "移动"
        case stopped = "停留"
        case gap = "定位中断"
        case uncertain = "定位待确认"
    }

    var id: Int { first }
    let kind: Kind
    let from: Int64
    let to: Int64
    let first: Int
    let last: Int
    let meters: Double

    var duration: Int64 { to - from }

    static func build(_ fixes: [Point]) -> [RoutePart] {
        guard var anchor = fixes.first else { return [] }
        var parts: [RoutePart] = []
        for index in fixes.indices.dropFirst() {
            let first = fixes[index - 1]
            let second = fixes[index]
            let elapsed = second.recordedAt - first.recordedAt
            guard elapsed > 0 else { continue }
            let quiet = anchor.accuracy <= 35 && first.accuracy <= 35 && second.accuracy <= 35
                && anchor.location.distance(from: second.location) <= 35
            let kind: Kind
            var meters = 0.0
            if elapsed > 210_000 || (elapsed > 180_000 && !quiet) {
                kind = .gap
                anchor = second
            } else if first.accuracy > 100 || second.accuracy > 100 {
                kind = .uncertain
                anchor = second
            } else {
                if anchor.accuracy > 100 { anchor = first }
                let margin = max(90, anchor.accuracy + second.accuracy)
                if anchor.location.distance(from: second.location) > margin {
                    kind = .moving
                    anchor = second
                    meters = first.location.distance(from: second.location)
                } else {
                    kind = .stopped
                }
            }
            append(RoutePart(kind: kind, from: first.recordedAt, to: second.recordedAt,
                             first: index - 1, last: index, meters: meters), to: &parts)
        }
        var result: [RoutePart] = []
        for part in parts {
            let kind: Kind = part.kind == .stopped && part.duration < 300_000 ? .uncertain : part.kind
            append(RoutePart(kind: kind, from: part.from, to: part.to, first: part.first,
                             last: part.last, meters: part.meters), to: &result)
        }
        return result
    }

    private static func append(_ part: RoutePart, to parts: inout [RoutePart]) {
        guard let previous = parts.last, previous.kind == part.kind,
              previous.last == part.first, previous.to == part.from else {
            parts.append(part)
            return
        }
        parts[parts.count - 1] = RoutePart(kind: part.kind, from: previous.from, to: part.to,
                                          first: previous.first, last: part.last, meters: previous.meters + part.meters)
    }
}
