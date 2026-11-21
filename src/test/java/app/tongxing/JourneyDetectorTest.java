package app.tongxing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyDetectorTest {
    @Test
    void waitsForDepartureAndDwellAndDoesNotBridgeMissingData() {
        var first = JourneyDetector.accept(null, fix(34.76, 60));
        var second = JourneyDetector.accept(first.state(), fix(34.7601, 120));
        var baseline = JourneyDetector.accept(second.state(), fix(34.76, 240));
        assertThat(baseline.state().stopped()).isTrue();
        assertThat(baseline.event()).isNull();
        var jitter = JourneyDetector.accept(baseline.state(), fix(34.762, 300));
        assertThat(jitter.event()).isNull();
        var back = JourneyDetector.accept(jitter.state(), fix(34.76, 360));
        assertThat(back.state().away()).isNull();
        var outside = JourneyDetector.accept(back.state(), fix(34.765, 420));
        var leave = JourneyDetector.accept(outside.state(), fix(34.767, 480));
        assertThat(leave.event()).isEqualTo(JourneyDetector.Event.LEAVE);
        var pause = JourneyDetector.accept(leave.state(), fix(34.7671, 540));
        var arrive = JourneyDetector.accept(pause.state(), fix(34.767, 660));
        assertThat(arrive.event()).isEqualTo(JourneyDetector.Event.ARRIVE);
        var restart = JourneyDetector.accept(arrive.state(), fix(34.79, 1020));
        assertThat(restart.event()).isNull();
        assertThat(restart.state().inTrip()).isFalse();
    }

    private static JourneyDetector.Fix fix(double lat, long seconds) {
        return new JourneyDetector.Fix(lat, 113.65, 15, seconds * 1000);
    }
}
