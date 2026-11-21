import CoreLocation
import Observation
import UIKit

@MainActor
@Observable
final class AppModel {
    private(set) var session: Session?
    private(set) var overview: Overview?
    private(set) var events: [Event] = []
    private(set) var savedPlaces: [SavedPlace] = []
    private(set) var authorization: CLAuthorizationStatus = .notDetermined
    private(set) var sharingOnThisPhone = false
    private(set) var lastFix: CLLocation?
    private(set) var trackingError: String?
    private(set) var connectionError: String?
    private(set) var busy = false
    var notice: String?
    var sosPoint: Point?
    var emergencyContact = ""

    @ObservationIgnored private var api: API?
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private let location = LocationSource()
    @ObservationIgnored private var shareKey: String?
    @ObservationIgnored private var lastUpload: CLLocation?
    @ObservationIgnored private var retryUploadAfter: Date?
    @ObservationIgnored private var upload: Task<Void, Never>?
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var refreshWaiters: [CheckedContinuation<Void, Never>] = []
    @ObservationIgnored private var pendingSos: (id: Int64, expires: Date)?
    @ObservationIgnored private var sosRequest: (id: String, pair: String, attach: Bool)?
    @ObservationIgnored private var restoringKey = false
    @ObservationIgnored private var revocation: Task<Void, Never>?

    var savedAddress: String { defaults.string(forKey: "server-address") ?? "" }
    var pair: String? { overview?.pairId }
    var locationAllowed: Bool { authorization == .authorizedAlways || authorization == .authorizedWhenInUse }

    init() {
        location.onAuthorization = { [weak self] in self?.authorizationChanged() }
        location.onFix = { [weak self] fix in self?.receive(fix) }
        authorization = location.authorization
        do {
            if let session = try SessionStore.load() {
                use(session, API(base: session.base, cookie: session.cookie))
            }
        } catch { notice = error.localizedDescription }
    }

    func authenticate(address: String, username: String, password: String, register: Bool) async -> Bool {
        guard !busy else { return false }
        busy = true
        defer { busy = false }
        do {
            let base = try API.address(address)
            let user = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard user.range(of: "^[a-z0-9_]{3,32}$", options: .regularExpression) != nil else {
                throw APIError.input("用户名须为 3–32 位英文字母、数字或下划线。")
            }
            guard !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, password.utf16.count <= 64 else {
                throw APIError.input("请输入密码，最多 64 个字符。")
            }
            if register && (password.utf16.count < 10 || password.utf8.count > 72) {
                throw APIError.input("密码须为 10–64 个字符，且 UTF-8 编码最多 72 字节。")
            }
            await revocation?.value
            let client = API(base: base)
            let session = try await client.authenticate(username: user, password: password, register: register)
            try SessionStore.save(session)
            defaults.set(base.absoluteString, forKey: "server-address")
            use(session, client)
            return true
        } catch {
            report(error)
            return false
        }
    }

    private func use(_ session: Session, _ client: API) {
        self.session = session
        api = client
        emergencyContact = defaults.string(forKey: key("emergency", session)) ?? ""
        connectionError = nil
    }

    func poll() async {
        while !Task.isCancelled && session != nil {
            await refresh()
            do { try await Task.sleep(for: .seconds(15)) }
            catch { break }
        }
    }

    func refresh() async {
        while refreshing && !Task.isCancelled {
            await withCheckedContinuation { refreshWaiters.append($0) }
        }
        guard !Task.isCancelled, let client = api, let session else { return }
        refreshing = true
        defer {
            refreshing = false
            let waiters = refreshWaiters
            refreshWaiters.removeAll()
            for waiter in waiters { waiter.resume() }
        }
        do {
            try await flushPause(client, session)
            var snapshot: Overview = try await client.get("overview")
            guard api === client else { return }
            if let currentPair = snapshot.pairId, snapshot.selfStatus.sharing,
               defaults.string(forKey: key("consent", session)) == currentPair,
               authorization == .denied || authorization == .restricted {
                queuePause(session, pair: currentPair, unavailable: true)
                try await flushPause(client, session)
                snapshot = try await client.get("overview")
                guard api === client else { return }
            }
            if snapshot.pairId != pair {
                stopTracking()
                lastFix = nil
                sosPoint = nil
                sosRequest = nil
                events = []
                savedPlaces = []
            }
            overview = snapshot
            connectionError = nil
            await resumeTracking(client, session)
            try await refreshFeed(client)
        } catch {
            guard api === client, !Task.isCancelled else { return }
            if (error as? APIError)?.status == 401 { expireSession() }
            else { connectionError = error.localizedDescription }
        }
    }

    func signOut() {
        guard let client = api, let session else { return }
        let pausePair = pair ?? defaults.string(forKey: key("consent", session))
        if let pausePair { queuePause(session, pair: pausePair) }
        do { try SessionStore.clear() }
        catch { notice = error.localizedDescription; return }
        reset()
        revocation = Task {
            do { try await flushPause(client, session) }
            catch {
                // Keep the pair-scoped revocation for the next authenticated session.
            }
            // Invalidate the server session even when the pause could not be delivered.
            try? await client.act("POST", "logout")
        }
    }

    func perform<T>(_ action: (API) async throws -> T) async -> T? {
        guard !busy, let client = api else { return nil }
        busy = true
        defer { busy = false }
        do {
            let result = try await action(client)
            guard api === client else { return nil }
            return result
        } catch {
            if api === client { report(error) }
            return nil
        }
    }

    func report(_ error: Error) {
        if Task.isCancelled || (error as? URLError)?.code == .cancelled { return }
        if api != nil && (error as? APIError)?.status == 401 { expireSession() }
        else { notice = error.localizedDescription }
    }

    func invite() async -> Invite? {
        await perform { try await $0.mutate("POST", "invite") }
    }

    func acceptInvite(_ raw: String) async -> Bool {
        let code = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard code.range(of: "^[0-9a-fA-F]{16}$", options: .regularExpression) != nil else {
            notice = "请输入 16 位邀请码。"
            return false
        }
        guard await perform({ try await $0.act("POST", "pair", ["code": code]) }) != nil else { return false }
        await refresh()
        return true
    }

    func send(_ path: String, _ payload: [String: Any]? = nil) async -> Bool {
        guard await perform({ try await $0.act("POST", path, payload) }) != nil else { return false }
        await refresh()
        return true
    }

    func sendSos(attach: Bool) async {
        guard let pair else { return }
        let requestId: String
        if let previous = sosRequest, previous.attach == attach, previous.pair == pair {
            requestId = previous.id
        } else {
            requestId = UUID().uuidString.lowercased()
            sosRequest = (requestId, pair, attach)
        }
        let result: SosCreated? = await perform {
            try await $0.mutate("POST", "sos/requests", ["requestId": requestId, "includeLocation": attach])
        }
        guard let result, self.pair == pair else { return }
        sosRequest = nil
        if attach {
            pendingSos = (result.id, Date().addingTimeInterval(110))
            if let lastFix { receive(lastFix) }
        }
        notice = "已发送 SOS 求助。"
        await refresh()
    }

    func openSos(_ event: Event) async -> Bool {
        let point: Point? = await perform { client in
            let point: Point = try await client.get("sos/\(event.id)")
            if event.incoming {
                try await client.act("PUT", "sos/\(event.id)/receipt", ["viewed": true])
            }
            return point
        }
        sosPoint = point
        return point != nil
    }

    func markSosViewed(_ event: Event) async {
        _ = await perform { try await $0.act("PUT", "sos/\(event.id)/receipt", ["viewed": true]) }
        await refresh()
    }

    func olderEvents() async {
        guard let first = events.last?.id, let pair else { return }
        let feed: Feed? = await perform {
            try await $0.get("feed", query: [URLQueryItem(name: "before", value: String(first))])
        }
        guard let feed, feed.pairId == pair, self.pair == pair else { return }
        let ids = Set(events.map(\.id))
        let older = feed.events.filter { !ids.contains($0.id) }
        events.append(contentsOf: older)
        if older.isEmpty { notice = "没有更早的动态了。" }
    }

    private func refreshFeed(_ client: API) async throws {
        let feed: Feed = try await client.get("feed")
        guard api === client, feed.pairId == pair else { return }
        if let oldest = feed.events.last?.id {
            events = feed.events + events.filter { $0.id < oldest }
        } else {
            events = []
        }
        let cutoff = (overview?.serverTime ?? Date().milliseconds) - 86_400_000
        for event in feed.events where event.incoming && event.kind == "SOS"
            && event.receivedAt == nil && event.createdAt >= cutoff {
            guard api === client, feed.pairId == pair else { return }
            do { try await client.act("PUT", "sos/\(event.id)/receipt", ["viewed": false]) }
            catch let error as APIError where error.status == 404 { continue }
        }
    }

    func route(partner: Bool, day: Date, expectedPair: String?) async throws -> [Point] {
        guard let client = api, expectedPair == pair, expectedPair != nil,
              let interval = Calendar.current.dateInterval(of: .day, for: day) else { return [] }
        let points: [Point] = try await client.get("positions", query: [
            URLQueryItem(name: "partner", value: partner ? "true" : "false"),
            URLQueryItem(name: "from", value: String(interval.start.milliseconds)),
            URLQueryItem(name: "to", value: String(interval.end.milliseconds))
        ])
        guard client === api, expectedPair == pair, !partner || overview?.partner?.sharing == true else { return [] }
        return points
    }

    func refreshPlaces() async {
        let current = pair
        let places: [SavedPlace]? = await perform { try await $0.get("saved-places") }
        if let places, current == pair { savedPlaces = places }
    }

    func savePlace(_ name: String, at point: CLLocationCoordinate2D) async -> Bool {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...40).contains(name.utf16.count) else {
            notice = "地点名称须为 1–40 个字符。"
            return false
        }
        let coordinates = ["latitude": point.latitude, "longitude": point.longitude]
        let place: SavedPlace? = await perform { try await $0.mutate("POST", "saved-places", [
            "name": name, "point": coordinates
        ]) }
        guard place != nil else { return false }
        await refreshPlaces()
        return true
    }

    func modifyPlace(_ place: SavedPlace, watching: Bool?) async {
        let path = "saved-places/\(place.id.uuidString.lowercased())"
        guard await perform({ client in
            if let watching { try await client.act("PUT", path + "/watch", ["enabled": watching]) }
            else { try await client.act("DELETE", path) }
        }) != nil else { return }
        await refreshPlaces()
    }

    func saveEmergencyContact(_ input: String) {
        guard let session else { return }
        emergencyContact = input.filter { "0123456789+".contains($0) }
        defaults.set(emergencyContact, forKey: key("emergency", session))
    }

    func requestPermission() {
        switch authorization {
        case .notDetermined: location.requestForeground()
        case .authorizedWhenInUse: location.requestBackground()
        default:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    func setSharing(_ enabled: Bool) async {
        guard let session, let pair else { notice = "请先完成伴侣绑定。"; return }
        if !enabled {
            queuePause(session, pair: pair)
            await refresh()
            if defaults.string(forKey: key("pause", session)) == pair {
                notice = "本机已停止上传。连接恢复后会同步暂停状态。"
            }
            return
        }
        guard locationAllowed else { requestPermission(); return }
        let state: ShareState? = await perform { client in
            try await flushPause(client, session)
            return try await client.mutate("PUT", "sharing", ["enabled": true, "pairId": pair])
        }
        guard let token = state?.shareKey, self.pair == pair, self.session?.scope == session.scope else { return }
        defaults.set(pair, forKey: key("consent", session))
        startTracking(token)
        await refresh()
    }

    func setInsight(_ endpoint: String, enabled: Bool) async {
        guard let pair else { return }
        guard await perform({ try await $0.act("PUT", endpoint, ["enabled": enabled, "pairId": pair]) }) != nil else { return }
        await refresh()
    }

    func delete(_ path: String, password: String? = nil) async {
        let payload = password.map { ["password": $0] }
        guard await perform({ try await $0.act("DELETE", path, payload) }) != nil else { return }
        if path == "account" {
            expireSession(message: nil)
        } else {
            if let session { defaults.removeObject(forKey: key("consent", session)) }
            stopTracking()
            lastFix = nil
            await refresh()
        }
    }

    private func resumeTracking(_ client: API, _ session: Session) async {
        guard let pair, let overview, overview.selfStatus.sharing,
              defaults.string(forKey: key("consent", session)) == pair,
              defaults.string(forKey: key("pause", session)) == nil, locationAllowed else {
            if shareKey != nil { stopTracking() }
            return
        }
        guard shareKey == nil, !restoringKey else { return }
        restoringKey = true
        defer { restoringKey = false }
        do {
            let state: ShareState = try await client.get("sharing")
            guard let token = state.shareKey, client === api, self.pair == pair,
                  defaults.string(forKey: key("consent", session)) == pair,
                  defaults.string(forKey: key("pause", session)) == nil else { return }
            startTracking(token)
        } catch {
            guard client === api, !Task.isCancelled else { return }
            trackingError = error.localizedDescription
        }
    }

    private func startTracking(_ token: String) {
        shareKey = token
        sharingOnThisPhone = true
        trackingError = nil
        location.updateTracking(enabled: true)
    }

    private func stopTracking() {
        shareKey = nil
        sharingOnThisPhone = false
        lastUpload = nil
        retryUploadAfter = nil
        pendingSos = nil
        trackingError = nil
        upload?.cancel()
        upload = nil
        location.updateTracking(enabled: false)
    }

    private func authorizationChanged() {
        authorization = location.authorization
        if locationAllowed {
            location.updateTracking(enabled: sharingOnThisPhone)
        } else if let session, let pair, defaults.string(forKey: key("consent", session)) == pair {
            if authorization == .denied || authorization == .restricted {
                queuePause(session, pair: pair, unavailable: true)
                Task { await refresh() }
            } else {
                stopTracking()
            }
        }
    }

    private func receive(_ fix: CLLocation) {
        guard let client = api, let token = shareKey, locationAllowed,
              CLLocationCoordinate2DIsValid(fix.coordinate), (0...1_000).contains(fix.horizontalAccuracy),
              abs(fix.timestamp.timeIntervalSinceNow) <= 30 else { return }
        lastFix = fix
        guard upload == nil, retryUploadAfter.map({ $0 <= Date() }) ?? true else { return }
        let sos = pendingSos.flatMap { $0.expires > Date() ? $0.id : nil }
        let due = lastUpload.map { previous in
            let moving = fix.distance(from: previous) > max(30, fix.horizontalAccuracy + previous.horizontalAccuracy)
            return fix.timestamp.timeIntervalSince(previous.timestamp) >= (moving ? 25 : 150)
        } ?? true
        guard due || sos != nil else { return }
        let device = UIDevice.current
        var payload: [String: Any] = [
            "id": UUID().uuidString.lowercased(), "shareKey": token,
            "latitude": fix.coordinate.latitude, "longitude": fix.coordinate.longitude,
            "accuracy": fix.horizontalAccuracy, "recordedAt": fix.timestamp.milliseconds,
            "battery": device.batteryLevel < 0 ? -1 : Int((device.batteryLevel * 100).rounded()),
            "charging": device.batteryState == .charging || device.batteryState == .full
        ]
        if overview?.selfStatus.deviceStatusEnabled == true {
            payload["deviceModel"] = device.model
            payload["networkType"] = location.network
        }
        upload = Task {
            defer {
                if client === api && shareKey == token { upload = nil }
            }
            do {
                if let sos {
                    try await client.act("PUT", "sos/\(sos)/fix", [
                        "point": ["latitude": fix.coordinate.latitude, "longitude": fix.coordinate.longitude],
                        "accuracy": fix.horizontalAccuracy, "recordedAt": fix.timestamp.milliseconds
                    ])
                    if pendingSos?.id == sos { pendingSos = nil }
                }
                if due {
                    try await client.act("POST", "positions", payload)
                    guard client === api, shareKey == token else { return }
                    lastUpload = fix
                }
                retryUploadAfter = nil
                trackingError = nil
            } catch {
                guard client === api, shareKey == token, !Task.isCancelled else { return }
                if (error as? APIError)?.status == 401 { expireSession() }
                else if sos != nil && (error as? APIError)?.status == 404 {
                    if pendingSos?.id == sos { pendingSos = nil }
                } else if (error as? APIError)?.status == 409 {
                    stopTracking()
                    Task { await refresh() }
                } else {
                    retryUploadAfter = Date().addingTimeInterval(15)
                    trackingError = error.localizedDescription
                }
            }
        }
    }

    private func queuePause(_ session: Session, pair: String, unavailable: Bool = false) {
        stopTracking()
        lastFix = nil
        defaults.removeObject(forKey: key("consent", session))
        defaults.set(pair, forKey: key("pause", session))
        defaults.set(unavailable, forKey: key("unavailable", session))
    }

    private func flushPause(_ client: API, _ session: Session) async throws {
        let pending = key("pause", session)
        guard let pair = defaults.string(forKey: pending) else { return }
        do {
            try await client.act("PUT", "sharing", [
                "enabled": false, "pairId": pair,
                "locationUnavailable": defaults.bool(forKey: key("unavailable", session))
            ])
        } catch let error as APIError where error.status == 409 {
            // The captured pair has already ended, so it has no active sharing.
        }
        if defaults.string(forKey: pending) == pair {
            defaults.removeObject(forKey: pending)
            defaults.removeObject(forKey: key("unavailable", session))
        }
    }

    private func key(_ name: String, _ session: Session) -> String { name + ":" + session.scope }

    private func expireSession(message: String? = "登录已失效，请重新登录。") {
        do { try SessionStore.clear() }
        catch { reset(); notice = error.localizedDescription; return }
        reset()
        notice = message
    }

    private func reset() {
        stopTracking()
        api = nil
        session = nil
        overview = nil
        events = []
        savedPlaces = []
        lastFix = nil
        sosPoint = nil
        sosRequest = nil
        connectionError = nil
        emergencyContact = ""
    }
}
