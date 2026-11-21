package app.tongxing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmapPlaceNamesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void prefersNeighborhoodAndNeverUsesHouseAddress() throws Exception {
        assertThat(AmapPlaceNames.areaName(json.readTree("""
                {"regeocode":{"formatted_address":"某路15号","addressComponent":{
                    "neighborhood":{"name":"测试小区"},"township":"测试街道","district":"测试区"}}}
                """))).isEqualTo("测试小区");
        assertThat(AmapPlaceNames.areaName(json.readTree("""
                {"regeocode":{"formatted_address":"某路15号","addressComponent":{
                    "neighborhood":{"name":[]},"township":[],"district":"测试区"}}}
                """))).isEqualTo("测试区");
        assertThat(AmapPlaceNames.areaName(json.readTree("""
                {"regeocode":{"formatted_address":"某路15号","addressComponent":{}}}
                """))).isEmpty();
    }
}
