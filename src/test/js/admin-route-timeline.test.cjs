const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(require.resolve('../../main/resources/static/admin/route-timeline.js'), 'utf8');
const {process} = vm.runInNewContext(source + '\nadminRouteTimeline;');
const fix = (minutes, latitude = 34.75, accuracy = 8) => ({
    latitude, longitude: 113.64, accuracy, recordedAt: 1_700_000_000_000 + minutes * 60_000
});

test('small drift and an isolated jump remain at the same stop', () => {
    const route = process([fix(0), fix(1, 34.7502), fix(2, 34.76), fix(3), fix(4), fix(5)]);
    assert.equal(route.parts.length, 1);
    assert.equal(route.parts[0].kind, 'stopped');
    assert.equal(route.fixes.every(point => point.latitude === 34.75), true);
});

test('confirmed movement remains visible and a missing interval is separate', () => {
    const route = process([fix(0), fix(1, 34.752), fix(2, 34.754), fix(7, 34.76)]);
    assert.equal(route.parts[0].kind, 'moving');
    assert.ok(route.parts[0].meters > 200);
    assert.equal(route.parts[1].kind, 'uncertain');
    assert.equal(route.parts[2].kind, 'gap');
});

test('low accuracy and duplicate timestamps never become movement', () => {
    const route = process([fix(0), fix(1, 34.76, 200), fix(0, 34.76), fix(2)]);
    assert.equal(route.fixes.length, 2);
    assert.equal(route.parts[0].kind, 'uncertain');
});
