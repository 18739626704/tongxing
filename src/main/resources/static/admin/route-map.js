'use strict';

const adminRouteMap = (() => {
    const card = document.getElementById('route-map-card');
    const element = document.getElementById('route-map');
    const status = document.getElementById('basemap-status');
    let map;
    let layers;
    let bounds;

    function fit() {
        if (!bounds || card.hidden || !element.clientWidth) return;
        map.invalidateSize({pan: false});
        map.fitBounds(bounds, {padding: [30, 30], maxZoom: 17, animate: false});
    }

    function reset() {
        layers?.clearLayers();
        bounds = null;
        card.hidden = true;
    }

    function show(points, formatTime) {
        if (!window.L) return false;
        card.hidden = false;
        if (!map) {
            map = L.map(element, {scrollWheelZoom: false, attributionControl: false});
            L.control.attribution({prefix: false}).addTo(map);
            const base = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxNativeZoom: 19, maxZoom: 20,
                attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'
            }).addTo(map);
            base.on('tileerror', () => { status.textContent = '底图加载不完整，足迹仍可查看。'; });
            base.on('loading', () => { status.textContent = ''; });
            layers = L.layerGroup().addTo(map);
        }
        layers.clearLayers();
        let segment = [];
        const positions = [];
        function drawSegment() {
            if (segment.length === 1) {
                L.circleMarker(segment[0], {radius: 3, color: '#176448', fillOpacity: 1, interactive: false}).addTo(layers);
            } else {
                L.polyline(segment, {color: '#176448', weight: 4, opacity: .94, interactive: false}).addTo(layers);
            }
        }
        for (const [index, point] of points.entries()) {
            // Keep adjacent samples on the same side of the map when crossing 180°.
            const previous = positions.at(-1)?.[1] ?? point.longitude;
            const longitude = point.longitude + 360 * Math.round((previous - point.longitude) / 360);
            if (index && point.recordedAt - points[index - 1].recordedAt > 15 * 60_000) {
                drawSegment();
                segment = [];
            }
            const position = [point.latitude, longitude];
            segment.push(position);
            positions.push(position);
        }
        drawSegment();
        function marker(index, label, color) {
            L.circleMarker(positions[index], {radius: 6, color: 'white', weight: 2, fillColor: color, fillOpacity: 1})
                .bindTooltip(label + ' ' + formatTime(points[index].recordedAt), {permanent: true, direction: index ? 'bottom' : 'top'})
                .addTo(layers);
        }
        if (positions.length > 1) marker(positions.length - 1, '末点', '#ac4d35');
        marker(0, positions.length === 1 ? '记录点' : '起点', '#176448');
        bounds = L.latLngBounds(positions);
        fit();
        return true;
    }

    document.getElementById('fit-route').addEventListener('click', fit);
    return {show, reset, fit};
})();
