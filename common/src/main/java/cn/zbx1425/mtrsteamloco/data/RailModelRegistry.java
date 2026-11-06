package cn.zbx1425.mtrsteamloco.data;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.render.integration.MtrModelRegistryUtil;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.model.Model;
import cn.zbx1425.sowcerext.model.ModelCluster;
import cn.zbx1425.sowcerext.model.RawModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import mtr.mappings.Text;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import mtr.mappings.Utilities;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptHolder;
import net.minecraft.server.packs.resources.ResourceManager;
import cn.zbx1425.sowcerext.util.ResourceUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public class RailModelRegistry {

    public static Map<String, RailModelProperties> elements = new HashMap<>();

    public static ModelCluster railNodeModel;

    public static void register(String key, RailModelProperties properties) {
        elements.put(key, properties);
    }

    public static void reload(ResourceManager resourceManager) {
        elements.clear();

        //
        register("", new RailModelProperties(Text.translatable("rail.mtrsteamloco.default"), null, 1f, 0f, null));
        // This is pulled from registry and shouldn't be shown
        register("null", new RailModelProperties(Text.translatable("rail.mtrsteamloco.hidden"), null, Float.MAX_VALUE, 0f, null));

        try {
            RawModel railNodeRawModel = MainClient.modelManager.loadRawModel(resourceManager,
                    new ResourceLocation("mtrsteamloco:models/rail_node.csv"), MainClient.atlasManager);
            railNodeModel = MainClient.modelManager.uploadVertArrays(railNodeRawModel);
        } catch (Exception ex) {
            Main.LOGGER.error("Failed loading rail node model", ex);
            MtrModelRegistryUtil.recordLoadingError("Failed loading Rail Node", ex);
        }

        List<Pair<ResourceLocation, Resource>> resources =
                MtrModelRegistryUtil.listResources(resourceManager, "mtrsteamloco", "rails", ".json");
        for (Pair<ResourceLocation, Resource> pair : resources) {
            try {
                try (InputStream is = Utilities.getInputStream(pair.getSecond())) {
                    JsonObject rootObj = (new JsonParser()).parse(IOUtils.toString(is, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (rootObj.has("model")) {
                        String key = FilenameUtils.getBaseName(pair.getFirst().getPath());
                        register(key, loadFromJson(resourceManager, key, rootObj));
                    } else {
                        for (Map.Entry<String, JsonElement> entry : rootObj.entrySet()) {
                            JsonObject obj = entry.getValue().getAsJsonObject();
                            String key = entry.getKey().toLowerCase(Locale.ROOT);
                            register(key, loadFromJson(resourceManager, key, obj));
                        }
                    }
                }
            } catch (Exception ex) {
                Main.LOGGER.error("Failed loading rail: " + pair.getFirst().toString(), ex);
                MtrModelRegistryUtil.recordLoadingError("Failed loading Rail " + pair.getFirst().toString(), ex);
            }
        }

        MainClient.railRenderDispatcher.clearRail();
    }

    private static final RailModelProperties EMPTY_PROPERTY = new RailModelProperties(
            Text.literal(""), null, 1f, 0, null
    );

    public static RailModelProperties getProperty(String key) {
        return elements.getOrDefault(key, EMPTY_PROPERTY);
    }

    private static RailModelProperties loadFromJson(ResourceManager resourceManager, String key, JsonObject obj) throws Exception {
        if (obj.has("atlasIndex")) {
            MainClient.atlasManager.load(
                    MtrModelRegistryUtil.resourceManager,  new ResourceLocation(obj.get("atlasIndex").getAsString())
            );
        }
        
        RawModel rawModel = null;
        Supplier<RawModel> lazyRawModelSupplier = null;

        if (obj.has("model")) {
            if (ClientConfig.experimentalLazyRailModels) {
                lazyRawModelSupplier = () -> {
                    try {
                        return loadRawModel(resourceManager, key, obj);
                    } catch (Exception ex) {
                        Main.LOGGER.error("Failed loading lazy rail model: " + key, ex);
                        MtrModelRegistryUtil.recordLoadingError("Failed loading Rail " + key, ex);
                        return null;
                    }
                };
            } else {
                rawModel = loadRawModel(resourceManager, key, obj);
            }
        }
        
        float repeatInterval = obj.has("repeatInterval") ? obj.get("repeatInterval").getAsFloat() : 0.5f;
        float yOffset = obj.has("yOffset") ? obj.get("yOffset").getAsFloat() : 0f;


        ScriptHolder script = null;
        if (obj.has("scriptFiles")) {
            script = new ScriptHolder();
            Map<ResourceLocation, String> scripts = new Object2ObjectArrayMap<>();
            if (obj.has("scriptTexts")) {
                JsonArray scriptTexts = obj.get("scriptTexts").getAsJsonArray();
                for (int i = 0; i < scriptTexts.size(); i++) {
                    scripts.put(new ResourceLocation("mtrsteamloco", "script_texts/" + key + "/" + i),
                            scriptTexts.get(i).getAsString());
                }
            }
            JsonArray scriptFiles = obj.get("scriptFiles").getAsJsonArray();
            for (int i = 0; i < scriptFiles.size(); i++) {
                ResourceLocation scriptLocation = new ResourceLocation(scriptFiles.get(i).getAsString());
                scripts.put(scriptLocation, ResourceUtil.readResource(resourceManager, scriptLocation));
            }
            script.load("Rail " + key, "Rail", resourceManager, scripts, obj, key);
        }

        if (lazyRawModelSupplier != null) {
            return RailModelProperties.lazy(Text.translatable(obj.get("name").getAsString()), lazyRawModelSupplier, repeatInterval, yOffset, script);
        }
        return new RailModelProperties(Text.translatable(obj.get("name").getAsString()), rawModel, repeatInterval, yOffset, script);
    }

    private static RawModel loadRawModel(ResourceManager resourceManager, String key, JsonObject obj) throws Exception {
        RawModel rawModel = MainClient.modelManager.loadRawModel(resourceManager,
                new ResourceLocation(obj.get("model").getAsString()), MainClient.atlasManager).copy();

        if (obj.has("textureId")) {
            rawModel.replaceTexture("default.png", new ResourceLocation(obj.get("textureId").getAsString()));
        }
        if (obj.has("flipV") && obj.get("flipV").getAsBoolean()) {
            rawModel.applyUVMirror(false, true);
        }

        rawModel.sourceLocation = new ResourceLocation(rawModel.sourceLocation.toString() + "/" + key);
        return rawModel;
    }
}
