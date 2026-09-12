package network.azusake.halo.json;
import network.azusake.halo.data.*;
import network.azusake.halo.core.*;
import com.google.gson.*;
import java.util.*;
public final class EntityAnchorParser {
    private static final Gson GSON=new GsonBuilder().registerTypeAdapter(Vec3d.class,new Vec3dAdapter()).create();
    public static EntityAnchorProfile parse(String json) { return deserialize(JsonParser.parseString(json)); }
    public static EntityAnchorProfile deserialize(JsonElement json) {
        var root = json.getAsJsonObject();

        Identifier entity = Identifier.tryParse(root.get("entity").getAsString());
        String defaultPose = root.get("default_pose").getAsString();

        Map<String, PoseAnchor> poses = new LinkedHashMap<>();
        var posesObj = root.getAsJsonObject("poses");
        for (var poseEntry : posesObj.entrySet()) {
            String poseKey = poseEntry.getKey();
            var poseObj = poseEntry.getValue().getAsJsonObject();

            Vec3d pivot = GSON.fromJson(poseObj.get("pivot"), Vec3d.class);
            Vec3d headCenterVec = GSON.fromJson(poseObj.get("head_center_vector"), Vec3d.class);

            poses.put(poseKey, new PoseAnchor(pivot, headCenterVec));
        }

        return new EntityAnchorProfile(entity, defaultPose, Collections.unmodifiableMap(poses));
    }

    static class Vec3dAdapter implements com.google.gson.JsonDeserializer<Vec3d> {
        @Override
        public Vec3d deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                  com.google.gson.JsonDeserializationContext context) {
            var arr = json.getAsJsonArray();
            return new Vec3d(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
        }
    }

    static class IdentifierAdapter implements com.google.gson.JsonDeserializer<Identifier> {
        @Override
        public Identifier deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                       com.google.gson.JsonDeserializationContext context) {
            return Identifier.tryParse(json.getAsString());
        }
    }
}
