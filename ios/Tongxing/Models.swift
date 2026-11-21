import Foundation
import CoreLocation

struct Overview: Decodable {
    let selfStatus: MemberStatus
    let partner: MemberStatus?
    let pairId: String?
    let serverTime: Int64

    enum CodingKeys: String, CodingKey {
        case selfStatus = "self"
        case partner, pairId, serverTime
    }
}

struct MemberStatus: Decodable {
    let displayName: String
    let sharing: Bool
    let lastLocation: Point?
    let locationUnavailable: Bool?
    let journeyEnabled: Bool?
    let deviceStatusEnabled: Bool?
    let device: DeviceSnapshot?
    let screenMinutes: Int?
    let screenDate: String?
    let appUsageEnabled: Bool?
    let liveScreen: ScreenSnapshot?
    let journey: JourneySnapshot?
}

struct DeviceSnapshot: Decodable {
    let model: String?
    let network: String?
    let motion: String?
    let updatedAt: Int64
    let unlockedAt: Int64?
}

struct ScreenSnapshot: Decodable {
    let state: String
    let updatedAt: Int64
}

struct JourneySnapshot: Decodable {
    let state: String
    let since: Int64
}

struct AppUsage: Decodable {
    struct Entry: Decodable {
        let name: String
        let seconds: Int
    }
    let date: String?
    let apps: [Entry]
}

struct Point: Decodable {
    let latitude: Double
    let longitude: Double
    let accuracy: Double
    let battery: Int
    let charging: Bool
    let recordedAt: Int64

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var location: CLLocation {
        CLLocation(latitude: latitude, longitude: longitude)
    }
}

struct Feed: Decodable {
    let pairId: String?
    let events: [Event]
}

struct Event: Identifiable, Decodable {
    let id: Int64
    let kind: String
    let text: String
    let sender: String
    let incoming: Bool
    let createdAt: Int64
    let hasLocation: Bool
    let receivedAt: Int64?
    let viewedAt: Int64?

    var title: String {
        switch kind {
        case "MESSAGE": return "消息"
        case "CHECK_IN": return "快捷报备"
        case "SOS": return "SOS 紧急求助"
        case "REMINDER": return "休息提醒"
        case "ROUTINE": return "作息"
        case "PLACE_ARRIVAL", "ARRIVE", "AUTO_ARRIVE": return "到达提醒"
        case "LEAVE", "AUTO_LEAVE": return "离开提醒"
        case "CHARGING", "LOW_BATTERY", "NETWORK", "MOTION": return "设备动态"
        default: return "动态"
        }
    }
}

struct Invite: Decodable {
    let code: String
    let expiresAt: Int64
}

struct ShareState: Decodable {
    let shareKey: String?
}

struct SavedPlace: Identifiable, Decodable {
    let id: UUID
    let name: String
    let latitude: Double
    let longitude: Double
    let watching: Bool
    let armed: Bool

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

struct SosCreated: Decodable {
    let id: Int64
}

enum CheckIn: String, CaseIterable {
    case morning = "GOOD_MORNING"
    case night = "GOOD_NIGHT"
    case arrived = "ARRIVED"
    case leaving = "HEADING_OUT"

    var title: String {
        switch self {
        case .morning: return "早上好"
        case .night: return "晚安"
        case .arrived: return "我到了"
        case .leaving: return "我出发了"
        }
    }
}

extension Date {
    var milliseconds: Int64 { Int64(timeIntervalSince1970 * 1_000) }

    init(milliseconds: Int64) {
        self.init(timeIntervalSince1970: Double(milliseconds) / 1_000)
    }
}
