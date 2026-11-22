'use strict';

const adminRouteTimeline = (() => {
    // Keep the same thresholds as Android RouteStabilizer and RouteTimeline.
    const radius = 90;
    const movingGap = 180_000;
    const quietGap = 210_000;
    const minimumStay = 300_000;
    const speedLimit = 100;

    function distance(a, b) {
        const radians = Math.PI / 180;
        const lat = (b.latitude - a.latitude) * radians;
        const lon = (b.longitude - a.longitude) * radians;
        const value = Math.sin(lat / 2) ** 2 + Math.cos(a.latitude * radians)
            * Math.cos(b.latitude * radians) * Math.sin(lon / 2) ** 2;
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, value)));
    }

    function outside(a, b) {
        return distance(a, b) > Math.max(radius, a.accuracy + b.accuracy);
    }

    function stabilize(raw) {
        const observed = raw.filter(fix => fix.recordedAt > 0 && fix.accuracy >= 0 && fix.accuracy <= 100
            && Math.abs(fix.latitude) <= 90 && Math.abs(fix.longitude) <= 180);
        const fixes = [];
        let anchor;
        let last = -Infinity;
        for (const [index, fix] of observed.entries()) {
            const at = fix.recordedAt;
            if (at <= last) continue;
            if (!anchor || at - last > movingGap) {
                anchor = fix;
            } else if (outside(anchor, fix) && distance(anchor, fix) <= speedLimit * (at - last) / 1000) {
                let nextIndex = index + 1;
                while (nextIndex < observed.length && observed[nextIndex].recordedAt <= at) nextIndex++;
                const next = observed[nextIndex];
                if (next && next.recordedAt - at <= movingGap && outside(anchor, next)) {
                    const step = distance(fix, next);
                    if (step <= distance(anchor, next) && step <= speedLimit * (next.recordedAt - at) / 1000) {
                        anchor = fix;
                    }
                }
            }
            fixes.push({...anchor, recordedAt: at, accuracy: Math.max(anchor.accuracy, fix.accuracy)});
            last = at;
        }
        return fixes;
    }

    function append(parts, part) {
        const previous = parts.at(-1);
        if (previous?.kind === part.kind && previous.last === part.first && previous.to === part.from) {
            previous.last = part.last;
            previous.to = part.to;
            previous.meters += part.meters;
        } else parts.push(part);
    }

    function process(raw) {
        const fixes = stabilize(raw);
        const parts = [];
        let anchor = fixes[0];
        for (let index = 1; index < fixes.length; index++) {
            const first = fixes[index - 1];
            const second = fixes[index];
            const elapsed = second.recordedAt - first.recordedAt;
            const quiet = elapsed <= quietGap && anchor.accuracy <= 35 && first.accuracy <= 35
                && second.accuracy <= 35 && distance(anchor, second) <= 35;
            let kind = 'stopped';
            let meters = 0;
            if (elapsed > movingGap && !quiet) {
                kind = 'gap';
                anchor = second;
            } else if (outside(anchor, second)) {
                kind = 'moving';
                meters = distance(first, second);
                anchor = second;
            }
            append(parts, {kind, first: index - 1, last: index, from: first.recordedAt, to: second.recordedAt, meters});
        }
        const classified = [];
        for (const part of parts) {
            append(classified, {...part, kind: part.kind === 'stopped' && part.to - part.from < minimumStay
                ? 'uncertain' : part.kind});
        }
        return {fixes, parts: classified};
    }

    return {process};
})();
