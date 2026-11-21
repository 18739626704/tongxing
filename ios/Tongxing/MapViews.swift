import MapKit
import SwiftUI

struct LiveMapView: View {
    let model: AppModel
    @State private var camera: MapCameraPosition = .automatic
    @State private var focusedOnce = false
    @State private var showingPermissions = false
    @State private var showingSos = false
    @State private var placePoint: Point?
    @State private var placeName = ""
    @State private var namingPlace = false

    var body: some View {
        ZStack(alignment: .top) {
            Map(position: $camera) {
                if let point = model.sosPoint {
                    Marker("SOS 求助位置", systemImage: "sos", coordinate: point.coordinate)
                        .tint(Palette.partner)
                } else {
                    if let point = model.overview?.selfStatus.lastLocation {
                        Marker("我", systemImage: "person.fill", coordinate: point.coordinate).tint(Palette.accent)
                    }
                    if let partner = model.overview?.partner, let point = partner.lastLocation {
                        Marker(partner.displayName, systemImage: "heart.fill", coordinate: point.coordinate)
                            .tint(Palette.partner)
                        if point.accuracy > 0 {
                            MapCircle(center: point.coordinate, radius: point.accuracy)
                                .foregroundStyle(Palette.partner.opacity(0.14))
                        }
                    }
                }
            }
            VStack {
                HStack {
                    Spacer()
                    Button("权限", systemImage: "location") { showingPermissions = true }
                        .buttonStyle(.borderedProminent)
                }
                Spacer()
                HStack(alignment: .bottom) {
                    statusCard
                    VStack(spacing: 10) {
                        mapButton("刷新地图", symbol: "arrow.clockwise") {
                            Task { await model.refresh(); focus() }
                        }
                        mapButton("查看位置", symbol: "scope", action: focus)
                        mapButton("紧急求助", symbol: "sos") { showingSos = true }
                            .foregroundStyle(Palette.partner)
                    }
                }
            }
            .padding(12)
            // Leave MapKit's legal label visible beneath the floating controls.
            .padding(.bottom, 14)
        }
        .onChange(of: model.overview?.serverTime, initial: true) { _, _ in
            guard !focusedOnce, model.overview?.partner?.lastLocation != nil
                    || model.overview?.selfStatus.lastLocation != nil else { return }
            focusedOnce = true
            focus()
        }
        .onChange(of: model.sosPoint?.recordedAt, initial: true) { _, _ in
            if model.sosPoint != nil { focus() }
        }
        .onChange(of: model.pair) { _, _ in
            focusedOnce = false
            focus()
        }
        .sheet(isPresented: $showingPermissions) { SharingView(model: model) }
        .sheet(isPresented: $showingSos) { EmergencyView(model: model) }
        .alert("保存常去地点", isPresented: $namingPlace) {
            TextField("地点名称", text: $placeName)
            Button("保存") {
                guard let point = placePoint else { return }
                Task { _ = await model.savePlace(placeName, at: point.coordinate) }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将地图上的共享位置保存到常去地点。")
        }
    }

    private var statusCard: some View {
        Card {
            if let point = model.sosPoint {
                Text("SOS 求助位置").font(.headline).foregroundStyle(Palette.partner)
                timeLabel(point)
                Button("返回实时地图") {
                    model.sosPoint = nil
                    focus()
                }
            } else if let overview = model.overview {
                if let partner = overview.partner {
                    Text(partner.displayName).font(.headline)
                    if let point = partner.lastLocation {
                        timeLabel(point)
                        Menu("位置操作") {
                            Button("保存伴侣位置") { askToSave(point) }
                            if let ownPoint = overview.selfStatus.lastLocation {
                                Button("保存我的位置") { askToSave(ownPoint) }
                            }
                        }
                        .font(.footnote)
                    } else {
                        Text(partner.sharing ? "等待伴侣上传位置" :
                            (partner.locationUnavailable == true ? "伴侣定位权限不可用" : "伴侣已暂停位置共享"))
                            .font(.footnote)
                        if let ownPoint = overview.selfStatus.lastLocation {
                            Button("保存我的位置") { askToSave(ownPoint) }.font(.footnote)
                        }
                    }
                    deviceStatus(partner, now: overview.serverTime)
                } else {
                    Text("尚未绑定伴侣").font(.headline)
                    Text("在「设置」中生成或输入邀请码。").font(.footnote)
                }
            } else {
                Text("正在读取位置").font(.headline)
            }
        }
        .shadow(color: .black.opacity(0.08), radius: 8, y: 3)
    }

    private func timeLabel(_ point: Point) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("更新于 \(Date(milliseconds: point.recordedAt).formatted(date: .numeric, time: .shortened)) · ±\(Int(point.accuracy.rounded())) 米")
            if point.battery >= 0 { Text("电量 \(point.battery)%\(point.charging ? " · 充电中" : "")") }
        }
        .font(.footnote).foregroundStyle(Palette.muted)
    }

    @ViewBuilder private func deviceStatus(_ partner: MemberStatus, now: Int64) -> some View {
        if let device = partner.device, now - device.updatedAt < 180_000 {
            let network = ["WIFI": "Wi-Fi", "MOBILE": "移动网络", "ETHERNET": "有线网络"][device.network ?? ""]
            let details = [device.model, network].compactMap { $0 }.joined(separator: " · ")
            if !details.isEmpty { Text(details).font(.caption).foregroundStyle(Palette.muted) }
        }
        if let journey = partner.journey {
            Text(journey.state == "TRAVELLING" ? "行程中" : "停留中").font(.caption).foregroundStyle(Palette.muted)
        }
        if let live = partner.liveScreen, now - live.updatedAt < 180_000 {
            let states = ["OFF": "熄屏", "LOCKED": "锁屏", "UNLOCKED": "使用中"]
            if let status = states[live.state] {
                Text("伴侣手机\(status)").font(.caption).foregroundStyle(Palette.muted)
            }
        }
    }

    private func askToSave(_ point: Point) {
        placePoint = point
        placeName = ""
        namingPlace = true
    }

    private func focus() {
        let points = model.sosPoint.map { [$0] } ??
            [model.overview?.selfStatus.lastLocation, model.overview?.partner?.lastLocation].compactMap { $0 }
        if let position = mapCamera(points.map(\.coordinate)) { camera = position }
        else { camera = .automatic }
    }

    private func mapButton(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 19, weight: .semibold))
                .frame(width: 46, height: 46)
                .background(Palette.paper, in: Circle())
                .shadow(color: .black.opacity(0.12), radius: 5, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

struct RouteView: View {
    let model: AppModel
    @State private var partner = false
    @State private var day = Calendar.current.startOfDay(for: .now)
    @State private var camera: MapCameraPosition = .automatic
    @State private var timeline = false
    @State private var reload = 0
    @State private var result: Track?
    @State private var failure: String?

    private struct Request: Equatable {
        let pair: String?
        let day: Date
        let partner: Bool
        let allowed: Bool
        let reload: Int
    }

    private struct Track {
        let request: Request
        let points: [Point]
        let parts: [RoutePart]
    }

    private var request: Request {
        Request(pair: model.pair, day: day, partner: partner,
                allowed: !partner || model.overview?.partner?.sharing == true, reload: reload)
    }

    private var track: Track? { result?.request == request ? result : nil }
    private var today: Date { Calendar.current.startOfDay(for: .now) }
    private var oldest: Date { Calendar.current.date(byAdding: .day, value: -30, to: today) ?? today }

    var body: some View {
        ZStack(alignment: .top) {
            Map(position: $camera) {
                if let track {
                    ForEach(track.parts.filter { $0.kind == .moving }) { part in
                        MapPolyline(coordinates: track.points[part.first...part.last].map(\.coordinate))
                            .stroke(partner ? Palette.partner : Palette.accent, lineWidth: 5)
                    }
                    if let first = track.points.first {
                        Marker("起点", systemImage: "flag", coordinate: first.coordinate)
                    }
                    if let last = track.points.last, track.points.count > 1 {
                        Marker("最后位置", systemImage: "location.fill", coordinate: last.coordinate)
                            .tint(partner ? Palette.partner : Palette.accent)
                    }
                }
            }
            Card {
                Picker("足迹对象", selection: $partner) {
                    Text("我的").tag(false)
                    Text("伴侣").tag(true)
                }
                .pickerStyle(.segmented)
                HStack {
                    Button("前一天") { changeDay(-1) }.disabled(day <= oldest)
                    Spacer()
                    DatePicker("选择日期", selection: $day, in: oldest...Date(), displayedComponents: .date)
                        .labelsHidden()
                        .environment(\.locale, Locale(identifier: "zh_CN"))
                    Spacer()
                    Button("后一天") { changeDay(1) }.disabled(Calendar.current.isDateInToday(day))
                }
                Text(status).font(.footnote).foregroundStyle(Palette.muted)
                HStack {
                    Button("查看时间轴", systemImage: "clock") { timeline = true }
                        .disabled(track?.parts.isEmpty ?? true)
                    Spacer()
                    Button("刷新足迹", systemImage: "arrow.clockwise") { reload += 1 }
                }
                .font(.subheadline)
            }
            .padding(12)
        }
        .task(id: request) { [selected = request] in
            camera = .automatic
            failure = nil
            guard selected.allowed, selected.pair != nil else { return }
            do {
                let points = try await model.route(partner: selected.partner, day: selected.day, expectedPair: selected.pair)
                try Task.checkCancellation()
                guard request == selected else { return }
                result = Track(request: selected, points: points, parts: RoutePart.build(points))
                if let position = mapCamera(points.map(\.coordinate)) { camera = position }
            } catch {
                guard !Task.isCancelled, request == selected else { return }
                failure = error.localizedDescription
                if (error as? APIError)?.status == 401 { model.report(error) }
            }
        }
        .sheet(isPresented: $timeline) {
            NavigationStack {
                List {
                    if let track {
                        let moving = track.parts.filter { $0.kind == .moving }.reduce(0) { $0 + $1.duration }
                        let resting = track.parts.filter { $0.kind == .stopped }.reduce(0) { $0 + $1.duration }
                        Section {
                            Text("移动 \(duration(moving)) · 停留 \(duration(resting))")
                        }
                        ForEach(track.parts.reversed()) { part in
                            Button {
                                let points = track.points[part.first...part.last].map(\.coordinate)
                                if let position = mapCamera(points) { camera = position }
                                timeline = false
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(part.kind.rawValue).font(.headline)
                                    Text("\(clock(part.from)) – \(clock(part.to)) · \(duration(part.duration))")
                                        .font(.footnote).foregroundStyle(Palette.muted)
                                }
                            }
                        }
                    }
                }
                .navigationTitle("足迹时间轴")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { timeline = false } } }
            }
        }
    }

    private var status: String {
        if model.pair == nil { return "绑定伴侣后可查看足迹。" }
        if !request.allowed { return "伴侣当前未共享位置。" }
        if let failure { return failure }
        guard let track else { return "正在读取轨迹…" }
        if track.points.isEmpty { return "当天没有定位记录。" }
        return "当天共 \(track.points.count) 个定位点。"
    }

    private func changeDay(_ offset: Int) {
        if let next = Calendar.current.date(byAdding: .day, value: offset, to: day) { day = next }
    }
}

private func clock(_ milliseconds: Int64) -> String {
    Date(milliseconds: milliseconds).formatted(date: .omitted, time: .shortened)
}

func duration(_ milliseconds: Int64) -> String {
    let minutes = milliseconds / 60_000
    if minutes == 0 { return milliseconds > 0 ? "不足 1 分钟" : "0 分钟" }
    return minutes >= 60 ? "\(minutes / 60) 小时 \(minutes % 60) 分钟" : "\(minutes) 分钟"
}

private func mapCamera(_ locations: [CLLocationCoordinate2D]) -> MapCameraPosition? {
    let points = locations.filter { CLLocationCoordinate2DIsValid($0) }.map { MKMapPoint($0) }
    guard let first = points.first else { return nil }
    // Wrap around the antimeridian relative to the first point.
    let width = MKMapRect.world.size.width
    let longitudes = points.map { point in
        let delta = point.x - first.x
        if delta > width / 2 { return point.x - width }
        if delta < -width / 2 { return point.x + width }
        return point.x
    }
    let minX = longitudes.min() ?? first.x
    let maxX = longitudes.max() ?? first.x
    let minY = points.map(\.y).min() ?? first.y
    let maxY = points.map(\.y).max() ?? first.y
    let rect = MKMapRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    return .rect(rect.insetBy(dx: -max(1_200, rect.size.width * 0.35),
                              dy: -max(1_200, rect.size.height * 0.65)))
}
