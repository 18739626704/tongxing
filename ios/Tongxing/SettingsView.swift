import CoreLocation
import SwiftUI

struct SettingsView: View {
    let model: AppModel
    @State private var sheet: Sheet?
    @State private var invite: Invite?
    @State private var code = ""
    @State private var contact = ""
    @State private var password = ""
    @State private var prompt: Prompt?

    private enum Sheet: String, Identifiable {
        case sharing, places, usage
        var id: Self { self }
    }

    private enum Prompt: String, Identifiable {
        case code = "输入邀请码"
        case contact = "紧急联系人"
        case unpair = "解除绑定"
        case positions = "清空我的位置记录"
        case delete = "注销账号"
        var id: Self { self }

        var message: String {
            switch self {
            case .code: return "填写伴侣生成的 16 位邀请码。"
            case .contact: return "电话号码仅保存在本机，在紧急求助页面可以拨打。"
            case .unpair: return "双方将停止互相访问，本次绑定的轨迹、动态和地点将被清除。"
            case .positions: return "清空服务器上的个人轨迹，并暂停位置共享。"
            case .delete: return "填写账号密码以永久删除账号及其共享数据。"
            }
        }
    }

    var body: some View {
        Page(title: "设置") {
            if let session = model.session {
                Card {
                    Text(model.overview?.selfStatus.displayName ?? session.username).font(.title2.bold())
                    Text("@\(session.username)").font(.subheadline).foregroundStyle(Palette.muted)
                    Text(session.base.absoluteString).font(.footnote).foregroundStyle(Palette.muted)
                        .textSelection(.enabled)
                }
            }
            Card {
                Text("伴侣绑定").font(.headline)
                if let partner = model.overview?.partner {
                    Text("已绑定 · \(partner.displayName)")
                    Button("解除绑定", role: .destructive) { prompt = .unpair }
                } else if model.overview != nil {
                    Text("尚未绑定").foregroundStyle(Palette.muted)
                    HStack {
                        Button("生成邀请码") { Task { invite = await model.invite() } }
                            .buttonStyle(.borderedProminent)
                        Button("输入邀请码") { code = ""; prompt = .code }
                            .buttonStyle(.bordered)
                    }
                    if let invite {
                        Text(invite.code).font(.title3.monospaced()).textSelection(.enabled)
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            if Date(milliseconds: invite.expiresAt) > context.date {
                                Text("有效至 \(Date(milliseconds: invite.expiresAt).formatted(date: .abbreviated, time: .shortened))")
                                    .font(.footnote).foregroundStyle(Palette.muted)
                                ShareLink(item: invite.code) { Label("分享邀请码", systemImage: "square.and.arrow.up") }
                            } else {
                                Text("邀请码已过期，请重新生成。").font(.footnote).foregroundStyle(Palette.muted)
                            }
                        }
                    }
                } else {
                    Text("正在读取绑定状态").foregroundStyle(Palette.muted)
                }
            }
            Card {
                Text("权限与共享").font(.headline)
                Button("权限与共享") { sheet = .sharing }
                Button("紧急联系人") { contact = model.emergencyContact; prompt = .contact }
                Button("常去地点与到达提醒") { sheet = .places }
                    .disabled(model.pair == nil)
                if model.overview?.partner?.appUsageEnabled == true {
                    Button("查看伴侣应用使用时长") { sheet = .usage }
                }
            }
            Card {
                Text("账号与数据").font(.headline)
                Button("退出登录") { model.signOut() }
                Button("清空我的位置记录", role: .destructive) { prompt = .positions }
                Button("注销账号", role: .destructive) { password = ""; prompt = .delete }
            }
        }
        .refreshable { await model.refresh() }
        .onChange(of: model.pair) { _, _ in invite = nil }
        .sheet(item: $sheet) { item in
            switch item {
            case .sharing: SharingView(model: model)
            case .places: PlacesView(model: model)
            case .usage: PartnerUsageView(model: model)
            }
        }
        .alert(prompt?.rawValue ?? "", isPresented: Binding(get: { prompt != nil }, set: {
            if !$0 { prompt = nil }
        }), presenting: prompt) { item in
            switch item {
            case .code:
                TextField("邀请码", text: $code).autocorrectionDisabled()
                Button("绑定") { Task { _ = await model.acceptInvite(code) } }
            case .contact:
                TextField("电话号码", text: $contact).keyboardType(.phonePad)
                Button("保存") { model.saveEmergencyContact(contact) }
            case .unpair:
                Button("解除绑定", role: .destructive) { Task { await model.delete("pair") } }
            case .positions:
                Button("清空记录", role: .destructive) { Task { await model.delete("positions") } }
            case .delete:
                SecureField("账号密码", text: $password)
                Button("确认注销", role: .destructive) {
                    let submitted = password
                    password = ""
                    Task { await model.delete("account", password: submitted) }
                }
            }
            Button("取消", role: .cancel) { password = "" }
        } message: { item in
            Text(item.message)
        }
    }
}

struct SharingView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("定位权限", value: permission)
                    Button(permissionAction) { model.requestPermission() }
                } footer: {
                    Text("允许始终定位后，可在切换应用或锁屏时继续共享。iOS 会根据定位活动调度回调。")
                }

                Section {
                    LabeledContent("本机位置上传", value: model.sharingOnThisPhone ? "已开启" : "已停止")
                    if model.sharingOnThisPhone || model.overview?.selfStatus.sharing == true {
                        Button("暂停位置共享", role: .destructive) { Task { await model.setSharing(false) } }
                    }
                    if !model.sharingOnThisPhone {
                        Button("同意与伴侣共享位置") { Task { await model.setSharing(true) } }
                            .disabled(!model.locationAllowed)
                    }
                    if let error = model.trackingError { Text(error).foregroundStyle(.red) }
                } footer: {
                    Text(model.pair == nil ? "请先绑定伴侣。" : "开启后，你的位置和足迹会上传到你填写的服务器，并对已绑定的伴侣可见。")
                }
                .disabled(model.pair == nil || model.busy)

                Section {
                    insight("行程到离提醒", endpoint: "journey-sharing",
                            enabled: model.overview?.selfStatus.journeyEnabled == true)
                    insight("设备状态共享", endpoint: "device-status-sharing",
                            enabled: model.overview?.selfStatus.deviceStatusEnabled == true)
                } footer: {
                    Text("本机可共享电量、充电状态、设备类型及网络类型。")
                }
                .disabled(!model.sharingOnThisPhone || model.busy)
            }
            .navigationTitle("权限与共享")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }

    private var permission: String {
        switch model.authorization {
        case .authorizedAlways: return "始终"
        case .authorizedWhenInUse: return "使用应用时"
        case .notDetermined: return "未授权"
        case .restricted: return "受系统限制"
        default: return "未允许"
        }
    }

    private var permissionAction: String {
        switch model.authorization {
        case .notDetermined: return "请求定位权限"
        case .authorizedWhenInUse: return "允许后台定位"
        default: return "打开系统设置"
        }
    }

    private func insight(_ title: String, endpoint: String, enabled: Bool) -> some View {
        Toggle(title, isOn: Binding(get: { enabled }, set: { value in
            Task { await model.setInsight(endpoint, enabled: value) }
        }))
    }
}

private struct PlacesView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.savedPlaces.isEmpty {
                    Text("从地图的「位置操作」保存常去地点。").foregroundStyle(Palette.muted)
                }
                ForEach(model.savedPlaces) { place in
                    VStack(alignment: .leading, spacing: 8) {
                        Toggle(place.name, isOn: Binding(get: { place.watching }, set: { watching in
                            Task { await model.modifyPlace(place, watching: watching) }
                        }))
                        Text(place.watching ? (place.armed ? "等待伴侣到达" : "等待伴侣离开后再次到达") : "到达提醒已关闭")
                            .font(.footnote).foregroundStyle(Palette.muted)
                    }
                    .swipeActions {
                        Button("删除", role: .destructive) { Task { await model.modifyPlace(place, watching: nil) } }
                    }
                }
                if !model.savedPlaces.isEmpty {
                    Text("到达提醒触发一次后会自动关闭。").font(.footnote).foregroundStyle(Palette.muted)
                }
            }
            .disabled(model.busy)
            .refreshable { await model.refreshPlaces() }
            .task(id: model.pair) { await model.refreshPlaces() }
            .navigationTitle("常去地点")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}

private struct PartnerUsageView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var usage: AppUsage?

    var body: some View {
        NavigationStack {
            List {
                if let usage, let date = usage.date, model.overview?.partner?.appUsageEnabled == true {
                    Section(date) {
                        ForEach(usage.apps.indices, id: \.self) { index in
                            let app = usage.apps[index]
                            LabeledContent(app.name, value: duration(Int64(app.seconds) * 1_000))
                        }
                    }
                } else {
                    Text("暂无伴侣共享的应用使用时长。")
                }
            }
            .task(id: model.pair) { usage = await model.perform { try await $0.get("app-usage") } }
            .navigationTitle("应用使用时长")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}
