package network.azusake.halo.config;

import com.google.gson.JsonParser;
import network.azusake.halo.core.ModConfigCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveBackendConfigTest {
    @Test void missingNullOrUnknownBackendsDefaultToCompatibilityAndPreserveOtherFields() {
        for (String field : new String[]{"", ",\"primitiveRenderBackend\":null", ",\"primitiveRenderBackend\":\"future\"",
                ",\"primitiveRenderBackend\":{}", ",\"primitiveRenderBackend\":3", ",\"primitiveRenderBackend\":[]"}) {
            var decoded=ModConfigCodec.decode("{\"unknown\":42,\"playerPreviewHaloEnabled\":false"+field+"}");
            assertEquals("compatibility",decoded.config().getPrimitiveRenderBackend());
            assertFalse(decoded.config().isPlayerPreviewHaloEnabled());
            assertEquals(42,JsonParser.parseString(decoded.replacement()).getAsJsonObject().get("unknown").getAsInt());
            assertNull(ModConfigCodec.decode(decoded.replacement()).replacement());
        }
    }
    @Test void explicitCachedSelectionRoundTrips() {
        var config=new HaloModConfig(); config.setPrimitiveRenderBackend("cached");
        var decoded=ModConfigCodec.decode(ModConfigCodec.encode(config));
        assertEquals("cached",decoded.config().getPrimitiveRenderBackend()); assertNull(decoded.replacement());
    }
}
