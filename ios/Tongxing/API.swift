import Foundation
import Security

struct Session: Codable {
    let base: URL
    let username: String
    let cookie: String

    var scope: String { base.absoluteString + "#" + username }
}

enum APIError: LocalizedError {
    case input(String)
    case response(Int, String)
    case invalidResponse
    case storage(OSStatus)

    var errorDescription: String? {
        switch self {
        case let .input(message), let .response(_, message): return message
        case .invalidResponse: return "服务器返回了无法识别的数据，请检查服务地址和版本。"
        case .storage: return "无法访问系统钥匙串，请解锁设备后重试。"
        }
    }

    var status: Int? {
        if case let .response(code, _) = self { return code }
        return nil
    }
}

enum SessionStore {
    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Bundle.main.bundleIdentifier ?? "app.tongxing.ios",
            kSecAttrAccount as String: "server-session"
        ]
    }

    static func load() throws -> Session? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query.merging([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]) { _, value in value } as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw APIError.storage(status) }
        return try JSONDecoder().decode(Session.self, from: data)
    }

    static func save(_ session: Session) throws {
        let data = try JSONEncoder().encode(session)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw APIError.storage(status) }
    }

    static func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw APIError.storage(status) }
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class API {
    private struct CSRF: Decodable {
        let header: String
        let token: String
    }

    private let base: URL
    private var cookie: String
    private let transport: URLSession

    init(base: URL, cookie: String = "") {
        self.base = base
        self.cookie = cookie
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 15
        transport = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }

    deinit {
        transport.invalidateAndCancel()
    }

    static func address(_ input: String) throws -> URL {
        let input = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let withScheme = input.contains("://") ? input : "https://" + input
        guard var components = URLComponents(string: withScheme),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty, !host.contains(where: \.isWhitespace),
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true else {
            throw APIError.input("请输入 HTTPS 服务器地址，例如 share.example.com。")
        }
        components.scheme = "https"
        components.host = host.lowercased()
        if components.port == 443 { components.port = nil }
        while components.percentEncodedPath.hasSuffix("/") { components.percentEncodedPath.removeLast() }
        guard let url = components.url else { throw APIError.invalidResponse }
        return url
    }

    func authenticate(username: String, password: String, register: Bool) async throws -> Session {
        if register {
            try await act("POST", "register", ["username": username, "password": password])
        }
        let csrf: CSRF = try await get("csrf")
        let form = "username=\(Self.formEncode(username))&password=\(Self.formEncode(password))"
        _ = try await send("POST", "login", body: Data(form.utf8),
                           contentType: "application/x-www-form-urlencoded", csrf: csrf)
        guard !cookie.isEmpty else { throw APIError.invalidResponse }
        return Session(base: base, username: username, cookie: cookie)
    }

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        try decode(await send("GET", path, query: query))
    }

    func mutate<T: Decodable>(_ method: String, _ path: String, _ payload: [String: Any]? = nil) async throws -> T {
        try decode(await mutation(method, path, payload))
    }

    func act(_ method: String, _ path: String, _ payload: [String: Any]? = nil) async throws {
        _ = try await mutation(method, path, payload)
    }

    private func mutation(_ method: String, _ path: String, _ payload: [String: Any]?) async throws -> Data {
        let csrf: CSRF = try await get("csrf")
        let body = try payload.map { try JSONSerialization.data(withJSONObject: $0) }
        return try await send(method, path, body: body, contentType: "application/json", csrf: csrf)
    }

    private func send(_ method: String, _ path: String, query: [URLQueryItem] = [], body: Data? = nil,
                      contentType: String? = nil, csrf: CSRF? = nil) async throws -> Data {
        try Task.checkCancellation()
        guard var components = URLComponents(url: base.appendingPathComponent("api").appendingPathComponent(path),
                                             resolvingAgainstBaseURL: false) else { throw APIError.invalidResponse }
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw APIError.invalidResponse }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !cookie.isEmpty { request.setValue("JSESSIONID=" + cookie, forHTTPHeaderField: "Cookie") }
        if let contentType, body != nil { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let csrf { request.setValue(csrf.token, forHTTPHeaderField: csrf.header) }
        let (data, response) = try await transport.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            struct ErrorBody: Decodable { let message: String }
            let fallback: String
            switch http.statusCode {
            case 401: fallback = "账号或密码有误，或登录已失效。"
            case 403: fallback = "服务器拒绝了此操作，请检查权限。"
            case 300..<400: fallback = "服务器返回跳转，请直接填写最终的 HTTPS 地址。"
            default: fallback = "请求失败（HTTP \(http.statusCode)）。"
            }
            let message = (try? JSONDecoder().decode(ErrorBody.self, from: data))?.message ?? fallback
            throw APIError.response(http.statusCode, message)
        }
        if let header = http.value(forHTTPHeaderField: "Set-Cookie"),
           let sessionCookie = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": header], for: url)
            .first(where: { $0.name == "JSESSIONID" }) {
            cookie = sessionCookie.value
        }
        return data
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw APIError.invalidResponse }
    }

    private static func formEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}
