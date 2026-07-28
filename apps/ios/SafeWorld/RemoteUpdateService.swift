import Foundation
import SafeWorldCore

enum RemoteUpdateError: LocalizedError {
    case noURLConfigured
    case invalidURL
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .noURLConfigured: return "No remote update URL configured."
        case .invalidURL: return "That doesn't look like a valid URL."
        case .http(let code): return "HTTP \(code)"
        }
    }
}

/// Fetches a `RemoteUpdatePayload` from the user-configured URL, the iOS
/// analogue of `runRemoteUpdate` in the Chrome extension's background.ts.
enum RemoteUpdateService {
    static func fetch(from urlString: String) async throws -> RemoteUpdatePayload {
        guard !urlString.isEmpty else { throw RemoteUpdateError.noURLConfigured }
        guard let url = URL(string: urlString) else { throw RemoteUpdateError.invalidURL }

        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw RemoteUpdateError.http(http.statusCode)
        }
        return try JSONDecoder().decode(RemoteUpdatePayload.self, from: data)
    }
}
