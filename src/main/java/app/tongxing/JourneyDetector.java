package app.tongxing;

final class JourneyDetector {
    enum Event { ARRIVE, LEAVE }
    record Fix(double latitude, double longitude, double accuracy, long at) {}
    record State(Fix anchor, long lastAt, int count, boolean stopped, Fix away, boolean inTrip) {}
    record Update(State state, Event event) {}
    private static final long GAP = 240_000;
    private static final long DWELL = 180_000;

    private JourneyDetector() {}

    static Update accept(State state, Fix fix) {
        if (fix.accuracy() > 55 || (state != null && fix.at() <= state.lastAt())) return new Update(state, null);
        if (state == null || fix.at() - state.lastAt() > GAP) {
            return new Update(new State(fix, fix.at(), 1, false, null, false), null);
        }
        double distance = distance(state.anchor(), fix);
        if (state.stopped()) {
            if (distance <= 110 + Math.max(state.anchor().accuracy(), fix.accuracy())) {
                return new Update(new State(state.anchor(), fix.at(), state.count(), true, null, false), null);
            }
            Fix away = state.away();
            if (away == null || distance(away, fix) > Math.max(120, distance)) {
                return new Update(new State(state.anchor(), fix.at(), state.count(), true, fix, false), null);
            }
            if (fix.at() - away.at() < 45_000) {
                return new Update(new State(state.anchor(), fix.at(), state.count(), true, away, false), null);
            }
            return new Update(new State(fix, fix.at(), 1, false, null, true), Event.LEAVE);
        }
        if (distance > 65 + Math.min(25, Math.max(state.anchor().accuracy(), fix.accuracy()))) {
            return new Update(new State(fix, fix.at(), 1, false, null, state.inTrip()), null);
        }
        int count = state.count() + 1;
        boolean stopped = count >= 3 && fix.at() - state.anchor().at() >= DWELL;
        Event event = stopped && state.inTrip() ? Event.ARRIVE : null;
        return new Update(new State(state.anchor(), fix.at(), count, stopped, null, !stopped && state.inTrip()), event);
    }

    private static double distance(Fix first, Fix second) {
        double latitude = Math.sin(Math.toRadians(second.latitude() - first.latitude()) / 2);
        double longitude = Math.sin(Math.toRadians(second.longitude() - first.longitude()) / 2);
        double a = latitude * latitude + Math.cos(Math.toRadians(first.latitude()))
                * Math.cos(Math.toRadians(second.latitude())) * longitude * longitude;
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
}
