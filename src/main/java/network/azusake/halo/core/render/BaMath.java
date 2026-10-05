package network.azusake.halo.core.render;

/** CPU reference functions for active node paths; input lighting is supplied by the host. */
public final class BaMath {
    private BaMath() {}
    public static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    public static float map(float v, float fromMin, float fromMax, float toMin, float toMax, boolean clamped) {
        float f = fromMax == fromMin ? 0 : (v - fromMin) / (fromMax - fromMin);
        return toMin + (toMax - toMin) * (clamped ? clamp(f) : f);
    }
    public static int shadowBand(float alpha, float... thresholds) {
        int band = 0; for (int i = 0; i < thresholds.length; i++) if (alpha > thresholds[i]) band = i + 1;
        return band;
    }
    public static float hairSpec(float directionDot, float directionOffset, float specAlpha, BaMaterial.Parameters p) {
        if (specAlpha < .07f) return 0;
        float d = directionDot + directionOffset;
        float s = d > 0 ? (1 - d) * p.scalar("SpecTopMultiplier", 8.8f) - p.scalar("SpecTopLeveler", 7.8f)
            : square(Math.max(d, -p.scalar("SpecBotArea", .17f)) + p.scalar("SpecBotArea", .17f)) * p.scalar("SpecBotMultiplier", 34.6021f);
        s -= 1 - specAlpha;
        float hard = s > p.scalar("SpecClamp", 0) ? 1 : 0;
        return (s + (hard - s) * clamp(p.scalar("Hardspec", 1))) * p.scalar("SpecStrength", 1);
    }
    public static float hairShadow(float light, float red, float green, BaMaterial.Parameters p) {
        float sensitivity = p.scalar("MaskGSensitivity", 1.4f);
        return map(light, p.scalar("From Min", 0), p.scalar("From Max", .4f), -sensitivity, sensitivity, false)
            - (2 * green - 1 - p.scalar("AdjustiveHairShadow", 0) * red) * sensitivity;
    }
    public static float haloSelection(float squaredDistance, BaMaterial.Parameters p) {
        return clamp((1 - clamp(squaredDistance * p.scalar("replace_clamp", 1000))) * p.scalar("replace_multiply", 1));
    }
    /** Exact dielectric Fresnel, matching Blender's IOR 1.5 node rather than Schlick. */
    public static float fresnel(float cosTheta, float ior) {
        float c = Math.abs(cosTheta), g2 = ior * ior - 1 + c * c;
        if (g2 <= 0) return 1;
        float g = (float)Math.sqrt(g2), a = (g - c) / (g + c), b = (c * (g + c) - 1) / (c * (g - c) + 1);
        return .5f * a * a * (1 + b * b);
    }
    private static float square(float v) { return v * v; }
}
