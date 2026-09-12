package network.azusake.halo.core.runtime;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.ObjMeshLoader;
import network.azusake.halo.core.render.TriangleMesh;
import network.azusake.halo.core.render.VisualResources;

/** Owned by a single loading thread. Create a new loader for each client resource reload. */
public final class VisualAssetLoader {
    public interface Source {
        String model(Identifier id) throws IOException;
        VisualResources.TextureInfo texture(Identifier id) throws IOException;
    }
    public record Problem(Identifier resource, String message) {}
    private final long generation;
    private final Source source;
    private final Consumer<Problem> problems;
    private final Map<Identifier, Optional<TriangleMesh>> models = new HashMap<>();
    private final Map<Identifier, Optional<VisualResources.TextureInfo>> textures = new HashMap<>();

    public VisualAssetLoader(long generation, Source source, Consumer<Problem> problems) {
        this.generation = generation;
        this.source = Objects.requireNonNull(source);
        this.problems = Objects.requireNonNull(problems);
    }

    public VisualResources load(DefinitionSnapshot.AssetDependencies dependencies) {
        var loadedModels = new HashMap<Identifier, TriangleMesh>();
        var loadedTextures = new HashMap<Identifier, VisualResources.TextureInfo>();
        for (Identifier id : dependencies.models()) {
            models.computeIfAbsent(id, key -> attempt(key, () -> ObjMeshLoader.parse(key, source.model(key))))
                .ifPresent(mesh -> loadedModels.put(id, mesh));
        }
        for (Identifier id : dependencies.textures()) {
            textures.computeIfAbsent(id, key -> attempt(key, () -> source.texture(key)))
                .ifPresent(texture -> loadedTextures.put(id, texture));
        }
        return new VisualResources(generation, loadedModels, loadedTextures);
    }

    @FunctionalInterface private interface Read<T> { T get() throws IOException; }
    private <T> Optional<T> attempt(Identifier id, Read<T> read) {
        try { return Optional.of(Objects.requireNonNull(read.get())); }
        catch (IOException | RuntimeException ex) {
            problems.accept(new Problem(id, String.valueOf(ex.getMessage())));
            return Optional.empty();
        }
    }
}
