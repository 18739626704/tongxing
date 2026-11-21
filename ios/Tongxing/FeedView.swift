import SwiftUI

struct FeedView: View {
    let model: AppModel
    let showMap: () -> Void
    @State private var message = ""

    var body: some View {
        Page(title: "动态") {
            Card {
                Text("发给伴侣").font(.headline)
                if model.pair == nil {
                    Text("绑定伴侣后可发送消息和报备。").foregroundStyle(Palette.muted)
                } else {
                    TextField("给伴侣发条消息", text: $message, axis: .vertical)
                        .lineLimit(1...4)
                    HStack {
                        Button("发送消息") {
                            Task {
                                if await model.send("messages", ["text": trimmedMessage]) { message = "" }
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.busy || trimmedMessage.isEmpty || trimmedMessage.utf16.count > 500)
                        Menu("快捷报备") {
                            ForEach(CheckIn.allCases, id: \.self) { kind in
                                Button(kind.title) { Task { _ = await model.send("check-ins", ["kind": kind.rawValue]) } }
                            }
                            Divider()
                            Button("提醒休息") { Task { _ = await model.send("reminders") } }
                            Button("开始休息") { Task { _ = await model.send("routine", ["resting": true]) } }
                            Button("已起床") { Task { _ = await model.send("routine", ["resting": false]) } }
                        }
                        .buttonStyle(.bordered)
                        .disabled(model.busy)
                    }
                }
            }
            if model.pair != nil {
                if model.events.isEmpty { Text("暂无动态").foregroundStyle(Palette.muted) }
                LazyVStack(spacing: 12) {
                    ForEach(model.events) { event in
                        eventCard(event)
                    }
                }
                if model.events.count >= 80 {
                    Button("查看更早的动态") { Task { await model.olderEvents() } }
                        .disabled(model.busy)
                }
            }
        }
        .refreshable { await model.refresh() }
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: model.pair) { _, _ in message = "" }
    }

    private var trimmedMessage: String { message.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func eventCard(_ event: Event) -> some View {
        Card {
            HStack {
                Text(event.title).font(.headline)
                    .foregroundStyle(event.kind == "SOS" ? Palette.partner : Palette.ink)
                Spacer()
                Text(Date(milliseconds: event.createdAt), format: .dateTime.month().day().hour().minute())
                    .font(.caption).foregroundStyle(Palette.muted)
            }
            Text((event.incoming ? event.sender : "我") + " · " + event.text)
                .font(.subheadline)
            if event.kind == "SOS" {
                if event.hasLocation {
                    Button("查看求助位置") {
                        Task {
                            if await model.openSos(event) { showMap() }
                        }
                    }
                    .disabled(model.busy)
                } else if event.incoming && event.viewedAt == nil
                    && (model.overview?.serverTime ?? Date().milliseconds) - event.createdAt < 86_400_000 {
                    Button("确认已查看") { Task { await model.markSosViewed(event) } }
                        .disabled(model.busy)
                }
                if !event.incoming {
                    Text(event.viewedAt != nil ? "伴侣已查看" : (event.receivedAt != nil ? "伴侣已收到" : "等待伴侣接收"))
                        .font(.caption).foregroundStyle(Palette.muted)
                }
            }
        }
    }
}

struct EmergencyView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var attach = false
    @State private var confirm = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("附上共享位置", isOn: $attach)
                        .disabled(!model.sharingOnThisPhone)
                    Button("发送 SOS", role: .destructive) { confirm = true }
                        .disabled(model.pair == nil || model.busy)
                } footer: {
                    Text(model.pair == nil ? "绑定伴侣后可发送 SOS。" : "SOS 会出现在伴侣的「动态」中。需要即时帮助时，请直接拨打电话。")
                }
                if !model.emergencyContact.isEmpty, let url = URL(string: "tel:" + model.emergencyContact) {
                    Section("紧急联系人") { Link("拨打 \(model.emergencyContact)", destination: url) }
                }
            }
            .navigationTitle("紧急求助")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .confirmationDialog("向伴侣发送 SOS？", isPresented: $confirm, titleVisibility: .visible) {
                Button("发送求助", role: .destructive) {
                    let include = attach && model.sharingOnThisPhone
                    dismiss()
                    Task { await model.sendSos(attach: include) }
                }
            }
            .onAppear { attach = model.sharingOnThisPhone }
        }
    }
}
