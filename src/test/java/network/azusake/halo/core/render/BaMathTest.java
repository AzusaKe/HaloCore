package network.azusake.halo.core.render;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaMathTest {
    @Test void strictShadowBoundariesAndDefaults() {
        assertEquals(0,BaMath.shadowBand(.01f,.01f,.25f,.5f));
        assertEquals(1,BaMath.shadowBand(.25f,.01f,.25f,.5f));
        assertEquals(2,BaMath.shadowBand(Math.nextUp(.25f),.01f,.25f,.5f));
        assertEquals(.5f,BaMaterial.defaults(BaMaterial.Type.FACE).scalar("mask_alpha",-1));
        assertEquals(-.5f,BaMaterial.defaults(BaMaterial.Type.HALO).scalar("Emission_Strength",0),1e-6);
        assertEquals(1f,BaMaterial.defaults(BaMaterial.Type.HALO).scalar("Emission_Strength1",0));
        assertEquals(0,BaMath.map(1,0,0,0,1,true));
        assertEquals(2,BaMath.map(2,0,1,0,1,false));
    }
    @Test void hardSpecIsStrictAndAlphaCutoffMatchesConnectedNodes() {
        var p=BaMaterial.defaults(BaMaterial.Type.HAIR).with("SpecTopMultiplier",1).with("SpecTopLeveler",0)
            .with("SpecClamp",.5f).with("Hardspec",1);
        assertEquals(0,BaMath.hairSpec(.5f,0,1,p));
        assertEquals(1,BaMath.hairSpec(.49f,0,1,p));
        assertEquals(0,BaMath.hairSpec(.49f,0,.069f,p));
        assertEquals(0,BaMath.hairSpec(-1,0,1,p));
        assertEquals(1,BaMath.haloSelection(0,BaMaterial.defaults(BaMaterial.Type.HALO)));
        assertEquals(0,BaMath.haloSelection(.001f,BaMaterial.defaults(BaMaterial.Type.HALO)));
        assertEquals(.04f,BaMath.fresnel(1,1.5f),1e-6); assertEquals(1,BaMath.fresnel(0,1.5f),1e-6);
    }
    @Test void rightSuffixPreservesCharacterAndDistinguishesSharedTextureParts() {
        assertEquals(new BaMaterial.Name("Long_Character_Name",BaMaterial.Type.ARMS),BaMaterial.identify("textures/Long_Character_Name_Body_Arms.png").orElseThrow());
        assertEquals(BaMaterial.Type.HAIR,BaMaterial.identify("CH0334_Hair.png").orElseThrow().type());
        assertTrue(BaMaterial.identify("ch0334_hair_spac.png").isEmpty());
        var source=new ArrayList<>(List.of(1f)); var map=new HashMap<String,List<Float>>(); map.put("Strength",source);
        var immutable=new BaMaterial.Parameters(map); source.set(0,2f); map.clear();
        assertEquals(1,immutable.scalar("Strength",0));
        assertEquals(2,immutable.with("Strength",2).scalar("Strength",0));
        assertEquals(1,immutable.scalar("Strength",0));
    }
}
