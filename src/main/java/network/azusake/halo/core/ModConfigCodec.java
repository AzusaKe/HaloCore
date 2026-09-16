package network.azusake.halo.core;

import com.google.gson.*;
import network.azusake.halo.config.HaloModConfig;

/** Preserves unknown fields and the existing repair/default rules without accessing a file system. */
public final class ModConfigCodec {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public record Result(HaloModConfig config,String replacement) {}
    public static String encode(HaloModConfig value) { return JSON.toJson(value); }
    public static Result decode(String raw) {
        HaloModConfig defaults=new HaloModConfig();
        if(raw==null || raw.isBlank())return new Result(defaults,encode(defaults));
        try {
            JsonElement root=JsonParser.parseString(raw);
            if(!root.isJsonObject())return invalid();
            JsonObject document=root.getAsJsonObject();
            JsonElement requested=document.get("primitiveRenderBackend");
            boolean backendRepaired=requested==null || !requested.isJsonPrimitive()
                || !requested.getAsJsonPrimitive().isString()
                || !("compatibility".equals(requested.getAsString()) || "cached".equals(requested.getAsString()));
            if(backendRepaired) document.addProperty("primitiveRenderBackend","compatibility");
            HaloModConfig parsed=JSON.fromJson(document,HaloModConfig.class);
            if(parsed==null)return invalid();
            boolean changed=backendRepaired;
            if(missing(document,"playerPreviewHaloPhysicsEnabled")) {
                document.addProperty("playerPreviewHaloPhysicsEnabled",parsed.isPlayerPreviewHaloPhysicsEnabled());changed=true;
            }
            if(missing(document,"playerPreviewHaloEnabled")) {
                document.addProperty("playerPreviewHaloEnabled",parsed.isPlayerPreviewHaloEnabled());changed=true;
            }
            int level=parsed.getCommandPermissionLevel();
            if(level<0 || level>4 || missing(document,"commandPermissionLevel")) {
                parsed.setCommandPermissionLevel(level);
                document.addProperty("commandPermissionLevel",parsed.getCommandPermissionLevel());changed=true;
            }
            if(missing(document,"experimentalYsmAnchorEnabled")) {
                document.addProperty("experimentalYsmAnchorEnabled",parsed.isExperimentalYsmAnchorEnabled());changed=true;
            }
            if(!parsed.validateExperimentalYsmHeadLocalOffset() || missing(document,"experimentalYsmHeadLocalOffset")) {
                document.add("experimentalYsmHeadLocalOffset",JSON.toJsonTree(parsed.getExperimentalYsmHeadLocalOffset()));changed=true;
            }
            return new Result(parsed,changed?JSON.toJson(document):null);
        } catch(RuntimeException ex) { return invalid(); }
    }
    private static boolean missing(JsonObject document,String key) { return !document.has(key)||document.get(key).isJsonNull(); }
    private static Result invalid() {
        Diagnostics.logger("halo").warn("Invalid Halo mod configuration; using defaults");
        return new Result(new HaloModConfig(),null);
    }
}
