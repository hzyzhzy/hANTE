package cn.zbx1425.mtrsteamloco.data;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.model.Model;
import cn.zbx1425.sowcer.util.GlStateTracker;
import cn.zbx1425.sowcer.vertex.VertAttrType;
import cn.zbx1425.sowcerext.model.RawMesh;
import cn.zbx1425.sowcerext.model.RawModel;
import cn.zbx1425.sowcerext.model.Vertex;
import net.minecraft.network.chat.Component;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptHolder;

import java.util.function.Supplier;

public class RailModelProperties {

    public Component name;

    public ScriptHolder script;

    public RawModel rawModel;
    public Model uploadedModel;

    public Long boundingBox;
    public float repeatInterval;

    public float yOffset;

    private Supplier<RawModel> lazyRawModelSupplier;
    private boolean loaded;

    public RailModelProperties(Component name, RawModel rawModel, float repeatInterval, float yOffset, ScriptHolder script) {
        this.name = name;
        this.yOffset = yOffset;
        this.script = script;
        this.repeatInterval = repeatInterval;
        init(rawModel);
    }

    private RailModelProperties(Component name, float repeatInterval, float yOffset, ScriptHolder script) {
        this.name = name;
        this.yOffset = yOffset;
        this.script = script;
        this.repeatInterval = repeatInterval;
        this.loaded = false;
    }

    public static RailModelProperties lazy(Component name, Supplier<RawModel> rawModelSupplier, float repeatInterval, float yOffset, ScriptHolder script) {
        RailModelProperties properties = new RailModelProperties(name, repeatInterval, yOffset, script);
        properties.lazyRawModelSupplier = rawModelSupplier;
        return properties;
    }

    private void init(RawModel rawModel) {
        this.loaded = true;
        if (rawModel == null) {
            this.rawModel = null;
            this.uploadedModel = null;
            this.boundingBox = 0L;
            return;
        }

        rawModel.clearAttrState(VertAttrType.COLOR);
        rawModel.applyRotation(new Vector3f(0.577f, 0.577f, 0.577f), (float) Math.toRadians(1));
        this.rawModel = rawModel;
        uploadedModel = MainClient.modelManager.uploadModel(rawModel);

        float yMin = 0f, yMax = 0f;
        for (RawMesh mesh : rawModel.meshList.values()) {
            for (Vertex vertex : mesh.vertices) {
                yMin = Math.min(yMin, vertex.position.y() + yOffset);
                yMax = Math.max(yMax, vertex.position.y() + yOffset);
            }
        }
        boundingBox = ((long) Float.floatToIntBits(yMin) << 32) | (long) Float.floatToIntBits(yMax);
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Supplier<RawModel> supplier = lazyRawModelSupplier;
        lazyRawModelSupplier = null;
        if (supplier == null) {
            init(null);
            return;
        }
        try {
            boolean needProtection = !GlStateTracker.isStateProtected;
            if (needProtection) GlStateTracker.capture();
            try {
                init(supplier.get());
            } finally {
                if (needProtection) GlStateTracker.restore();
            }
        } catch (Exception ex) {
            Main.LOGGER.error("Failed loading lazy rail model: " + name.getString(), ex);
            init(null);
        }
    }

    public synchronized RawModel getRawModel() {
        ensureLoaded();
        return rawModel;
    }

    public synchronized Model getUploadedModel() {
        ensureLoaded();
        return uploadedModel;
    }

    public synchronized long getBoundingBox() {
        ensureLoaded();
        return boundingBox == null ? 0L : boundingBox;
    }
}
