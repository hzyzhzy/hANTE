package cn.zbx1425.mtrsteamloco.data;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptHolder;
import cn.zbx1425.sowcerext.model.ModelCluster;
import net.minecraft.network.chat.Component;
import mtr.mappings.Text;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Supplier;

public class EyeCandyProperties implements Closeable {

    public static final EyeCandyProperties DEFAULT = new EyeCandyProperties(Text.literal(""), null, null, "0, 0, 0, 16, 16, 16", "0, 0, 0, 0, 0, 0", true, 0, false, false, false);

    public Component name;

    public ModelCluster model;
    /**
     * Kept for the whole lifetime so an aggressively unloaded model can be
     * rebuilt when it becomes visible again.
     */
    private Supplier<ModelCluster> modelSupplier;
    public ScriptHolder script;
    public String shape;
    public String collisionShape;
    public boolean fixedMatrix;
    public int lightLevel;
    public boolean isTicketBarrier;
    public boolean isEntrance;
    public boolean asPlatform;

    /** Last time any block of this prefab was actually rendered. */
    private volatile long lastUsedMs = System.currentTimeMillis();

    public EyeCandyProperties(Component name, ModelCluster model, ScriptHolder script, String shape, String collisionShape, boolean fixedMatrix, int lightLevel, boolean isTicketBarrier, boolean isEntrance, boolean asPlatform) {
        this.name = name;
        this.model = model;
        this.script = script;
        this.shape = shape;
        this.collisionShape = collisionShape;
        this.fixedMatrix = fixedMatrix;
        this.lightLevel = lightLevel;
        this.isTicketBarrier = isTicketBarrier;
        this.isEntrance = isEntrance;
        this.asPlatform = asPlatform;
    }

    public static EyeCandyProperties lazy(Component name, Supplier<ModelCluster> modelSupplier, ScriptHolder script, String shape, String collisionShape, boolean fixedMatrix, int lightLevel, boolean isTicketBarrier, boolean isEntrance, boolean asPlatform) {
        EyeCandyProperties properties = new EyeCandyProperties(name, null, script, shape, collisionShape, fixedMatrix, lightLevel, isTicketBarrier, isEntrance, asPlatform);
        properties.modelSupplier = modelSupplier;
        return properties;
    }

    /** True when this prefab owns a reloadable model supplier. */
    public boolean isReloadable() {
        return modelSupplier != null;
    }

    public void touch() {
        lastUsedMs = System.currentTimeMillis();
    }

    public long getLastUsedMs() {
        return lastUsedMs;
    }

    public synchronized ModelCluster getModel() {
        if (model == null && modelSupplier != null) {
            try {
                model = modelSupplier.get();
            } catch (Exception ex) {
                Main.LOGGER.error("Failed loading eye-candy model: " + name.getString(), ex);
            }
        }
        return model;
    }

    /**
     * Releases the uploaded GPU model but keeps the supplier so it can be
     * rebuilt later. Called from the render thread.
     */
    public synchronized boolean unloadModel() {
        if (model == null || modelSupplier == null) return false;
        ModelCluster old = model;
        model = null;
        try {
            cn.zbx1425.mtrsteamloco.MainClient.modelManager.unloadCluster(old);
            old.close();
        } catch (Exception ex) {
            Main.LOGGER.warn("Failed unloading eye-candy model: " + name.getString(), ex);
            return false;
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        if (model != null) model.close();
    }
}
