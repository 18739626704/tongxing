import SwiftUI

@main
struct TongxingApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .tint(Palette.accent)
                .preferredColorScheme(.light)
        }
    }
}

enum Palette {
    static let background = Color(red: 245 / 255, green: 248 / 255, blue: 244 / 255)
    static let ink = Color(red: 23 / 255, green: 46 / 255, blue: 38 / 255)
    static let muted = Color(red: 80 / 255, green: 105 / 255, blue: 95 / 255)
    static let accent = Color(red: 23 / 255, green: 99 / 255, blue: 75 / 255)
    static let soft = Color(red: 224 / 255, green: 239 / 255, blue: 231 / 255)
    static let paper = Color(red: 1, green: 253 / 255, blue: 249 / 255)
    static let partner = Color(red: 164 / 255, green: 73 / 255, blue: 92 / 255)
}

enum Tab: String, CaseIterable {
    case map = "地图"
    case route = "足迹"
    case feed = "动态"
    case settings = "设置"

    var symbol: String {
        switch self {
        case .map: return "map"
        case .route: return "point.topleft.down.to.point.bottomright.curvepath"
        case .feed: return "bubble.left.and.bubble.right"
        case .settings: return "gearshape"
        }
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.paper, in: RoundedRectangle(cornerRadius: 22))
    }
}

struct Page<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                content
            }
            .frame(maxWidth: 620)
            .frame(maxWidth: .infinity)
            .padding(18)
        }
    }
}

struct RootView: View {
    @Bindable var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = Tab.map

    var body: some View {
        Group {
            if model.session == nil {
                LoginView(model: model)
            } else {
                VStack(spacing: 0) {
                    if let error = model.connectionError {
                        Text(error).font(.footnote)
                            .frame(maxWidth: .infinity)
                            .padding(8)
                            .background(Palette.soft)
                    }
                    Group {
                        switch tab {
                        case .map: LiveMapView(model: model)
                        case .route: RouteView(model: model)
                        case .feed: FeedView(model: model) { tab = .map }
                        case .settings: SettingsView(model: model)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    bottomNav
                }
            }
        }
        .background(Palette.background.ignoresSafeArea())
        .foregroundStyle(Palette.ink)
        .task(id: PollKey(account: model.session?.scope, active: scenePhase == .active)) {
            if scenePhase == .active { await model.poll() }
        }
        .onChange(of: model.session?.scope) { _, _ in tab = .map }
        .alert("同行", isPresented: Binding(get: { model.notice != nil }, set: {
            if !$0 { model.notice = nil }
        })) {
            Button("确定", role: .cancel) { model.notice = nil }
        } message: {
            Text(model.notice ?? "")
        }
    }

    private var bottomNav: some View {
        HStack(spacing: 5) {
            ForEach(Tab.allCases, id: \.self) { item in
                Button {
                    if item != .map { model.sosPoint = nil }
                    tab = item
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: item.symbol).font(.system(size: 20, weight: .medium))
                        Text(item.rawValue).font(.caption.weight(.medium))
                    }
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .foregroundStyle(tab == item ? Palette.accent : Palette.muted)
                    .background(tab == item ? Palette.soft : .clear, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(tab == item ? .isSelected : [])
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 5)
        .background(Palette.paper.ignoresSafeArea(edges: .bottom))
    }

    private struct PollKey: Equatable {
        let account: String?
        let active: Bool
    }
}

struct LoginView: View {
    let model: AppModel
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var registering = false

    var body: some View {
        Page(title: "同行") {
            Text(registering ? "创建自托管账号" : "登录到自己的服务器")
                .foregroundStyle(Palette.muted)
            Card {
                TextField("服务器域名或 IP 地址", text: $address)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("使用 HTTPS，可省略 https://。").font(.footnote).foregroundStyle(Palette.muted)
                Divider()
                TextField("用户名", text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("3–32 位英文字母、数字或下划线").font(.footnote).foregroundStyle(Palette.muted)
                Divider()
                SecureField("密码", text: $password)
                    .textContentType(registering ? .newPassword : .password)
                if registering {
                    Text("10–64 个字符，建议使用英文字母、数字及符号组合。")
                        .font(.footnote).foregroundStyle(Palette.muted)
                }
                Button(registering ? "注册并登录" : "登录") {
                    Task {
                        if await model.authenticate(address: address, username: username,
                                                    password: password, register: registering) {
                            password = ""
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.busy || address.isEmpty || username.isEmpty || password.isEmpty)
                if model.busy { ProgressView().accessibilityLabel("正在连接服务器") }
            }
            Button(registering ? "返回登录" : "创建账号") {
                registering.toggle()
                password = ""
            }
            .buttonStyle(.bordered)
            .disabled(model.busy)
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { if address.isEmpty { address = model.savedAddress } }
    }
}
