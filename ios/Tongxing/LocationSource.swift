import CoreLocation
import Network
import UIKit

@MainActor
final class LocationSource: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let monitor = NWPathMonitor()
    private(set) var network = "OTHER"
    var onAuthorization: (() -> Void)?
    var onFix: ((CLLocation) -> Void)?

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }
    var allowed: Bool {
        authorization == .authorizedAlways || authorization == .authorizedWhenInUse
    }

    override init() {
        super.init()
        UIDevice.current.isBatteryMonitoringEnabled = true
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = true
        monitor.pathUpdateHandler = { [weak self] path in
            let network: String
            if path.status != .satisfied { network = "OFFLINE" }
            else if path.usesInterfaceType(.wifi) { network = "WIFI" }
            else if path.usesInterfaceType(.cellular) { network = "MOBILE" }
            else if path.usesInterfaceType(.wiredEthernet) { network = "ETHERNET" }
            else { network = "OTHER" }
            Task { @MainActor in self?.network = network }
        }
        monitor.start(queue: DispatchQueue(label: "app.tongxing.ios.network"))
    }

    deinit { monitor.cancel() }

    func requestForeground() { manager.requestWhenInUseAuthorization() }
    func requestBackground() { manager.requestAlwaysAuthorization() }

    func updateTracking(enabled: Bool) {
        if enabled && allowed {
            let canRunInBackground = authorization == .authorizedAlways
            manager.allowsBackgroundLocationUpdates = canRunInBackground
            manager.showsBackgroundLocationIndicator = canRunInBackground
            manager.startUpdatingLocation()
        } else {
            manager.stopUpdatingLocation()
            manager.allowsBackgroundLocationUpdates = false
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor [weak self] in self?.onAuthorization?() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        Task { @MainActor [weak self] in self?.onFix?(fix) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient positioning errors are followed by later updates from Core Location.
    }
}
