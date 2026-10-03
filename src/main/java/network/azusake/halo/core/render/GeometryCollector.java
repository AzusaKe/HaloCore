package network.azusake.halo.core.render;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import network.azusake.halo.core.Identifier;

/** Internal CPU geometry writer. Captures render state instead of calling a graphics API. */
public final class GeometryCollector {
    private final FrameScene.TextureLookup textures;
    private final java.util.Map<Identifier, Boolean> textureResults = new java.util.HashMap<>();
    private final List<PrimitiveDraw> primitives = new ArrayList<>();
    private final PrimitiveRenderMode mode;
    private java.util.function.Consumer<PrimitiveDraw> sink;
    public void sink(java.util.function.Consumer<PrimitiveDraw> value) { sink=value; }
    private final List<DrawBatch> batches = new ArrayList<>();
    private final Builder builder = new Builder();
    private Identifier texture;
    private boolean textured, cull=true, blend, depthTest=true, depthWrite=true;
    private float red=1,green=1,blue=1,alpha=1;
    private LightSample light = LightSample.UNAVAILABLE;
    private boolean directionalLighting;
    public GeometryCollector(FrameScene.TextureLookup textures) { this(textures, PrimitiveRenderMode.COMPATIBILITY); }
    public GeometryCollector(FrameScene.TextureLookup textures, PrimitiveRenderMode mode) { this.textures=textures; this.mode=mode; }
    public List<PrimitiveDraw> primitiveDraws() { return List.copyOf(primitives); }
    public void geometry(PrimitiveGeometry geometry, Matrix4f transform, float brightness) {
        geometry(geometry, transform, brightness, null);
    }
    public void geometry(PrimitiveGeometry geometry, Matrix4f transform, float brightness, org.joml.Matrix3f normal) {
        DrawBatch state = new DrawBatch(geometry.topology(), List.of(), texture, textured, cull, blend,
            depthTest, depthWrite, red, green, blue, alpha, MaterialState.LEGACY, light, directionalLighting);
        if (sink != null && normal == null) normal=RetainedNormalMatrix.from(transform);
        PrimitiveDraw command = normal == null ? new PrimitiveDraw(geometry, transform.get(new float[16]), state, brightness)
            : new PrimitiveDraw(geometry, transform.get(new float[16]), state, brightness, normal.get(new float[9]));
        if (sink != null) sink.accept(command);
        else if (mode == PrimitiveRenderMode.CACHED) primitives.add(command);
        else batches.add(command.expand());
    }
    public List<DrawBatch> batches() { return List.copyOf(batches); }
    public Builder getBuffer() { return builder; }
    public boolean bindTexture(Identifier id) { texture=id; return id!=null && textureResults.computeIfAbsent(id, textures::exists); }
    public void textured(boolean value) { textured=value; }
    public void enableCull() { cull=true; }
    public void disableCull() { cull=false; }
    public void enableBlend() { blend=true; }
    public void disableBlend() { blend=false; }
    public void defaultBlendFunc() { /* default alpha blend is part of the batch contract */ }
    public void enableDepthTest() { depthTest=true; }
    public void disableDepthTest() { depthTest=false; }
    public void depthMask(boolean value) { depthWrite=value; }
    public void setShaderColor(float r,float g,float b,float a) { red=r;green=g;blue=b;alpha=a; }
    public void setLight(LightSample value) { light=java.util.Objects.requireNonNull(value); }
    public void setDirectionalLighting(boolean value) { directionalLighting=value; }
    public void draw() {
        batches.add(new DrawBatch(builder.topology,builder.vertices,texture,textured,cull,blend,
            depthTest,depthWrite,red,green,blue,alpha,MaterialState.LEGACY,light,directionalLighting));
        builder.vertices.clear();
    }
    public final class Builder {
        private final List<DrawBatch.Vertex> vertices = new ArrayList<>();
        private DrawBatch.Topology topology;
        private final Vector3f position = new Vector3f();
        private float x,y,z,u,v,r=1,g=1,b=1,a=1,nx=0,ny=-1,nz=0;
        public void begin(DrawBatch.Topology topology,boolean withTexture) {
            this.topology=topology; textured=withTexture; vertices.clear();
        }
        public Builder vertex(Matrix4f transform,float x,float y,float z) {
            Vector3f p=transform.transformPosition(x,y,z,position);
            this.x=p.x;this.y=p.y;this.z=p.z;u=0;v=0;return this;
        }
        public Builder texture(float u,float v) { this.u=u;this.v=v;return this; }
        public Builder color(float r,float g,float b,float a) { this.r=r;this.g=g;this.b=b;this.a=a;return this; }
        public Builder normal(float x,float y,float z) {
            float length=(float)Math.sqrt(x*x+y*y+z*z);
            if (Float.isFinite(length) && length>1.0e-8f) { nx=x/length;ny=y/length;nz=z/length; }
            else { nx=0;ny=-1;nz=0; }
            return this;
        }
        public void next() { vertices.add(new DrawBatch.Vertex(x,y,z,u,v,r,g,b,a,nx,ny,nz)); }
    }
}
