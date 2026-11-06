package cn.zbx1425.mtrsteamloco.render.integration;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.mtrsteamloco.data.EyeCandyProperties;
import cn.zbx1425.mtrsteamloco.data.EyeCandyRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mtr.client.DynamicTrainModel;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the JSON needed to rebuild train models, and reports model memory
 * statistics at shutdown.
 *
 * Aggressive unloading was removed: releasing uploaded models while MTR still
 * holds references to them made trains render blank, because the reload path
 * cannot reliably restore MTR's part state. The memory is instead saved where
 * it is safe to do so: models are simply never loaded until first rendered
 * (see DynamicTrainModelMixin and the lazy EyeCandy / rail registries).
 */
public class DynamicTrainModelUnloadManager {

    private static final Map<DynamicTrainModel, ModelState> STATES = new WeakHashMap<>();

    private static class ModelState {
        final JsonObject modelJson;
        final JsonObject propertiesSnapshot;
        boolean loaded;

        ModelState(JsonObject modelJson, JsonObject propertiesSnapshot) {
            this.modelJson = modelJson;
            this.propertiesSnapshot = propertiesSnapshot;
            this.loaded = false;
        }
    }

    private static JsonObject deepCopy(JsonObject source) {
        if (source == null) return null;
        return new JsonParser().parse(source.toString()).getAsJsonObject();
    }

    public static synchronized void register(DynamicTrainModel model, JsonObject json, JsonObject properties) {
        STATES.computeIfAbsent(model, ignored -> new ModelState(json, deepCopy(properties)));
    }

    public static synchronized JsonObject getModelJson(DynamicTrainModel model) {
        ModelState state = STATES.get(model);
        return state == null ? null : state.modelJson;
    }

    /**
     * Restores the pristine properties object before a reload, because the
     * loader rewrites part names in place. Only needed when a model is loaded
     * more than once (resource reload, repeated construction).
     */
    public static synchronized void restoreProperties(DynamicTrainModel model) {
        ModelState state = STATES.get(model);
        if (state == null || state.propertiesSnapshot == null) return;
        JsonObject fresh = deepCopy(state.propertiesSnapshot);
        if (fresh == null) return;
        model.properties.entrySet().clear();
        for (Map.Entry<String, JsonElement> entry : fresh.entrySet()) {
            model.properties.add(entry.getKey(), entry.getValue());
        }
    }

    public static synchronized boolean isLoaded(DynamicTrainModel model) {
        ModelState state = STATES.get(model);
        return state != null && state.loaded;
    }

    public static synchronized void markLoaded(DynamicTrainModel model) {
        ModelState state = STATES.get(model);
        if (state != null) state.loaded = true;
    }

    /** No-op kept so the render mixin stays simple. */
    public static void markRendered(DynamicTrainModel model) {
    }

    public static synchronized void tick() {
        // Aggressive unloading intentionally removed.
    }

    public static synchronized String getStatus() {
        int loaded = 0;
        for (ModelState state : STATES.values()) if (state.loaded) loaded++;
        int eyeCandyLoaded = 0;
        for (EyeCandyProperties properties : EyeCandyRegistry.elements.values()) {
            if (properties.model != null) eyeCandyLoaded++;
        }
        return "lazyTrain=" + cn.zbx1425.mtrsteamloco.ClientConfig.experimentalLazyTrainModels
                + ", lazyEyeCandy=" + cn.zbx1425.mtrsteamloco.ClientConfig.experimentalLazyEyeCandyModels
                + ", lazyRail=" + cn.zbx1425.mtrsteamloco.ClientConfig.experimentalLazyRailModels
                + ", trains=" + loaded + "/" + STATES.size()
                + ", eyecandy=" + eyeCandyLoaded + "/" + EyeCandyRegistry.elements.size()
                + ", modelClusters=" + MainClient.modelManager.uploadedVertArrays.size()
                + ", uploadedModels=" + MainClient.modelManager.uploadedModels.size();
    }

    public static synchronized void logSummary() {
        try {
            Main.LOGGER.info("[hANTE] Model memory summary: {}", getStatus());
        } catch (Exception ignored) {
        }
    }
}
