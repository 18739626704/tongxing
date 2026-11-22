'use strict';

const adminRouteMap = (() => {
    const card = document.getElementById('route-map-card');
    const element = document.getElementById('route-map');
    const status = document.getElementById('basemap-status');
    let map;
    let layers;
    let bounds;
    let positions = [];
    let selection;

    function fit() {
        if (!bounds || card.hidden || !element.clientWidth) return;
        map.invalidateSize({pan: false});
        if (selection) { layers.removeLayer(selection); selection = null; }
        map.fitBounds(bounds, {padding: [30, 30], maxZoom: 17, animate: false});
    }

    function reset() {
        layers?.clearLayers();
        bounds = null;
        positions = [];
        selection = null;
        card.hidden = true;
    }

    function show(points, parts, formatTime) {
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
        selection = null;
        positions = [];
        for (const point of points) {
            // Keep adjacent samples on the same side of the map when crossing 180°.
            const previous = positions.at(-1)?.[1] ?? point.longitude;
            const longitude = point.longitude + 360 * Math.round((previous - point.longitude) / 360);
            positions.push([point.latitude, longitude]);
        }
        for (const part of parts) {
            if (part.kind === 'moving') {
                L.polyline(positions.slice(part.first, part.last + 1),
                    {color: '#176448', weight: 4, opacity: .94, interactive: false}).addTo(layers);
            } else if (part.kind === 'stopped') {
                L.circleMarker(positions[part.first], {radius: 7, color: '#176448', fillOpacity: .35})
                    .bindTooltip('停留 ' + formatTime(part.from) + '–' + formatTime(part.to)).addTo(layers);
            }
        }
        function marker(index, label, color) {
            L.circleMarker(positions[index], {radius: 6, color: 'white', weight: 2, fillColor: color, fillOpacity: 1})
                .bindTooltip(label + ' ' + formatTime(points[index].recordedAt), {permanent: true, direction: index ? 'bottom' : 'top'})
                .addTo(layers);
        }
        if (positions.length > 1 && positions[0].some((value, index) => value !== positions.at(-1)[index])) marker(positions.length - 1, '末点', '#ac4d35');
        marker(0, positions.length === 1 ? '记录点' : '起点', '#176448');
        bounds = L.latLngBounds(positions);
        fit();
        return true;
    }

    function focus(part) {
        if (!map || !bounds) return;
        if (selection) layers.removeLayer(selection);
        const path = positions.slice(part.first, part.last + 1);
        selection = part.kind === 'moving'
            ? L.polyline(path, {color: '#bb5828', weight: 6})
            : L.circleMarker(path[0], {radius: 12, color: '#bb5828', fillOpacity: .2});
        selection.addTo(layers);
        map.fitBounds(L.latLngBounds(part.kind === 'moving' ? path : [path[0]]),
            {padding: [30, 30], maxZoom: 17, animate: false});
        element.scrollIntoView({block: 'nearest'});
    }

    document.getElementById('fit-route').addEventListener('click', fit);
    return {show, reset, fit, focus};
})();
