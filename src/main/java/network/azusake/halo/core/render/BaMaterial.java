package network.azusake.halo.core.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import network.azusake.halo.core.Identifier;

/** Versioned artistic data, independent of texture units, shaders and Minecraft. */
public record BaMaterial(Type type, Identifier mask, Identifier spec, Parameters parameters) {
    public static final int VERSION = 1;
    public enum Type { BODY, ARMS, FACE, HAIR, WEAPON, HALO, UNLIT, OUTLINE }
    public record Key(Identifier model, Identifier texture) {}
    public record Name(String character, Type type) {}
    public BaMaterial { Objects.requireNonNull(type); Objects.requireNonNull(parameters); }
    public boolean castsShadow() { return type != Type.UNLIT && type != Type.HALO && type != Type.OUTLINE; }

    /** Longest suffix first; underscores in character identifiers are preserved. */
    public static Optional<Name> identify(String path) {
        String original = path.substring(path.lastIndexOf('/') + 1);
        String file = original.toLowerCase(Locale.ROOT);
        if (!file.endsWith(".png") && !file.endsWith(".obj")) return Optional.empty();
        file = file.substring(0, file.length() - 4);
        String[] suffixes = {"body_arms", "body_outline", "face_outline", "hair_outline", "weapon_outline",
            "weapon01", "eyebrow", "eyemouth", "outline", "weapon", "mouth", "hair", "body", "face", "halo", "arms", "eye", "alpha"};
        for (String suffix : suffixes) {
            String ending = "_" + suffix;
            if (!file.endsWith(ending) || file.length() == ending.length()) continue;
            Type type = suffix.contains("outline") ? Type.OUTLINE : switch (suffix) {
                case "body_arms", "arms" -> Type.ARMS;
                case "hair", "alpha" -> Type.HAIR;
                case "body" -> Type.BODY;
                case "face" -> Type.FACE;
                case "halo" -> Type.HALO;
                case "weapon", "weapon01" -> Type.WEAPON;
                default -> Type.UNLIT;
            };
            return Optional.of(new Name(original.substring(0, file.length() - ending.length()), type));
        }
        return Optional.empty();
    }
    public static Identifier companion(Identifier base, String suffix) {
        if (!suffix.equals("mask") && !suffix.equals("spac")) throw new IllegalArgumentException("Unknown BA companion");
        String path = base.getPath();
        if (!path.endsWith(".png")) throw new IllegalArgumentException("BA base must be PNG");
        return new Identifier(base.getNamespace(), path.substring(0, path.length() - 4) + "_" + suffix + ".png");
    }

    /** Exact Blender socket names and linear values. Every list is immutable. */
    public record Parameters(Map<String, List<Float>> values) {
        public Parameters {
            var copy = new LinkedHashMap<String, List<Float>>();
            values.forEach((key, value) -> {
                if (value.isEmpty() || value.size() > 4 || value.stream().anyMatch(v -> v == null || !Float.isFinite(v)))
                    throw new IllegalArgumentException("Invalid BA parameter " + key);
                copy.put(key, List.copyOf(value));
            });
            values = Map.copyOf(copy);
        }
        public float scalar(String name, float fallback) {
            List<Float> v = values.get(name); return v == null ? fallback : v.get(0);
        }
        public List<Float> vector(String name, List<Float> fallback) { return values.getOrDefault(name, fallback); }
        public Parameters with(String name, float... components) {
            var copy = new LinkedHashMap<>(values);
            var v = new ArrayList<Float>(); for (float component : components) v.add(component);
            copy.put(name, v); return new Parameters(copy);
        }
    }
    private static final Map<String, Parameters> REFERENCE = loadReference();
    private static Map<String, Parameters> loadReference() {
        try (var input = BaMaterial.class.getResourceAsStream("/network/azusake/halo/ba/arisu-v1.json")) {
            if (input == null) throw new IllegalStateException("Missing BA reference parameters");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != VERSION) throw new IllegalStateException("Unknown BA parameter version");
            var result = new HashMap<String, Parameters>();
            root.getAsJsonObject("profiles").entrySet().forEach(profile -> {
                var values = new LinkedHashMap<String, List<Float>>();
                profile.getValue().getAsJsonObject().entrySet().forEach(socket -> {
                    var components = new ArrayList<Float>();
                    if (socket.getValue().isJsonArray()) socket.getValue().getAsJsonArray().forEach(v -> components.add(v.getAsFloat()));
                    else components.add(socket.getValue().getAsFloat());
                    values.put(socket.getKey(), components);
                });
                result.put(profile.getKey(), new Parameters(values));
            });
            return Map.copyOf(result);
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    /** Unknown characters use these explicit v1 defaults, not inferred texture statistics. */
    public static Parameters defaults(Type type) {
        String key = type == Type.OUTLINE ? "unlit" : type.name().toLowerCase(Locale.ROOT);
        return REFERENCE.get(key);
    }
    public static BaMaterial resolve(Key key, Identifier mask, Identifier spec) {
        Name texture = identify(key.texture().getPath()).orElse(null);
        if (texture == null) return null;
        Type type = identify(key.model().getPath()).map(Name::type).orElse(texture.type());
        return new BaMaterial(type, mask, spec, defaults(type));
    }
}
