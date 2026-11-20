package cn.zbx1425.sowcerext.model;

import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.EnqueueProp;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.model.Model;
import cn.zbx1425.sowcer.model.VertArrays;
import cn.zbx1425.sowcer.util.AttrUtil;
import cn.zbx1425.sowcer.util.DrawContext;
import cn.zbx1425.sowcer.vertex.VertAttrMapping;
import cn.zbx1425.sowcer.vertex.VertAttrState;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcerext.model.integration.BufferSourceProxy;
import cn.zbx1425.sowcerext.reuse.ModelManager;
import net.minecraft.resources.ResourceLocation;

import java.io.Closeable;
import java.util.function.Function;

public class ModelCluster implements Closeable {

    public final VertArrays uploadedOpaqueParts;
    public RawModel opaqueParts;
    public final VertArrays uploadedTranslucentParts;
    public final RawModel translucentParts;

    private boolean opaqueRawFreed = false;

    private final Model uploadedOpaqueModel;
    private final Model uploadedTranslucentModel;

    public ModelCluster(RawModel source, VertAttrMapping mapping, ModelManager modelManager) {
        this.translucentParts = new RawModel();
        this.opaqueParts = new RawModel();
        for (RawMesh mesh : source.meshList.values()) {
            if (mesh.materialProp.translucent) {
                translucentParts.append(mesh);
            } else {
                opaqueParts.append(mesh);
            }
        }
        this.uploadedOpaqueModel = modelManager.uploadModel(opaqueParts);
        this.uploadedOpaqueParts = VertArrays.createAll(
                uploadedOpaqueModel, mapping, null);
        this.uploadedTranslucentModel = modelManager.uploadModel(translucentParts);
        this.uploadedTranslucentParts = VertArrays.createAll(
                uploadedTranslucentModel, mapping, null);
    }

    public ModelCluster(RawModel source, VertAttrMapping mapping) {
        // Untracked variant
        this.translucentParts = new RawModel();
        this.opaqueParts = new RawModel();
        for (RawMesh mesh : source.meshList.values()) {
            if (mesh.materialProp.translucent) {
                translucentParts.append(mesh);
            } else {
                opaqueParts.append(mesh);
            }
        }
        this.uploadedOpaqueModel = null;
        this.uploadedOpaqueParts = VertArrays.createAll(
                opaqueParts.upload(mapping), mapping, null);
        this.uploadedTranslucentModel = null;
        this.uploadedTranslucentParts = VertArrays.createAll(
                translucentParts.upload(mapping), mapping, null);
    }

    private ModelCluster(VertArrays uploadedOpaqueParts, RawModel opaqueParts, VertArrays uploadedTranslucentParts, RawModel translucentParts) {
        this.uploadedOpaqueParts = uploadedOpaqueParts;
        this.opaqueParts = opaqueParts;
        this.uploadedTranslucentParts = uploadedTranslucentParts;
        this.translucentParts = translucentParts;
        this.uploadedOpaqueModel = null;
        this.uploadedTranslucentModel = null;
    }

    public Model[] getUploadedModels() {
        return new Model[] { uploadedOpaqueModel, uploadedTranslucentModel };
    }

    public void enqueueOpaqueGl(BatchManager batchManager, Matrix4f pose, int light, DrawContext drawContext) {
        // KHRDebug.glDebugMessageInsert(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, KHRDebug.GL_DEBUG_TYPE_MARKER,
        //        0, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, "RenderOptimized " + (source.sourceLocation == null ? "unknown" : source.sourceLocation.toString()));
        int shaderLightmapUV = AttrUtil.exchangeLightmapUVBits(light);
        batchManager.enqueue(uploadedOpaqueParts, new EnqueueProp(
                new VertAttrState()
                        .setColor(255, 255, 255, 255).setOverlayUVNoOverlay()
                        .setLightmapUV(shaderLightmapUV).setModelMatrix(pose)
        ), ShaderProp.DEFAULT);
    }

    public void enqueueOpaqueBlaze(BufferSourceProxy vertexConsumers, Matrix4f pose, int light, DrawContext drawContext) {
        // CPU 副本已释放时无法走顶点缓冲通道，直接跳过（此时本应处于快速渲染通道）
        if (opaqueRawFreed) return;
        opaqueParts.writeBlazeBuffer(vertexConsumers, pose, light, drawContext);
    }

    public void enqueueTranslucentGl(BatchManager batchManager, Matrix4f matrix4f, int light, DrawContext drawContext) {
        int shaderLightmapUV = AttrUtil.exchangeLightmapUVBits(light);
        batchManager.enqueue(uploadedTranslucentParts, new EnqueueProp(
                new VertAttrState()
                        .setColor(255, 255, 255, 255).setOverlayUVNoOverlay()
                        .setLightmapUV(shaderLightmapUV).setModelMatrix(matrix4f)
        ), ShaderProp.DEFAULT);
    }

    public void enqueueTranslucentBlaze(BufferSourceProxy vertexConsumers, Matrix4f pose, int light, DrawContext drawContext) {
        translucentParts.writeBlazeBuffer(vertexConsumers, pose, light, drawContext);
    }

    public void setMatixProcess(Function<Matrix4f, Matrix4f> matrixProcess) {
        opaqueParts.setMatixProcess(matrixProcess);
        translucentParts.setMatixProcess(matrixProcess);
        uploadedOpaqueParts.setMatixProcess(matrixProcess);
        uploadedTranslucentParts.setMatixProcess(matrixProcess);
    }

    /** 该模型簇的不透明 CPU 副本是否已被释放。 */
    public boolean isOpaqueRawFreed() {
        return opaqueRawFreed;
    }

    /**
     * 释放不透明部分的 CPU 侧几何数据（顶点、面），只保留 GPU 缓冲。
     * <p>
     * 释放后快速渲染通道照常工作；顶点缓冲通道（光影兼容模式）与重贴图会失效，
     * 因此只在 {@code useRenderOptimization()} 为真时调用。
     */
    public void freeOpaqueRawParts() {
        if (opaqueRawFreed) return;
        opaqueRawFreed = true;
        opaqueParts = new RawModel();
    }

    @Override
    public void close() {
        uploadedOpaqueParts.close();
        uploadedTranslucentParts.close();
    }


    public void replaceTexture(String oldTexture, ResourceLocation newTexture) {
        uploadedOpaqueParts.replaceTexture(oldTexture, newTexture);
        if (!opaqueRawFreed) opaqueParts.replaceTexture(oldTexture, newTexture);
        uploadedTranslucentParts.replaceTexture(oldTexture, newTexture);
        translucentParts.replaceTexture(oldTexture, newTexture);
    }

    public void replaceAllTexture(ResourceLocation newTexture) {
        uploadedOpaqueParts.replaceAllTexture(newTexture);
        if (!opaqueRawFreed) opaqueParts.replaceAllTexture(newTexture);
        uploadedTranslucentParts.replaceAllTexture(newTexture);
        translucentParts.replaceAllTexture(newTexture);
    }

    public ModelCluster copyForMaterialChanges() {
        return new ModelCluster(
                uploadedOpaqueParts.copyForMaterialChanges(),
                opaqueRawFreed ? new RawModel() : opaqueParts.copyForMaterialChanges(),
                uploadedTranslucentParts.copyForMaterialChanges(),
                translucentParts.copyForMaterialChanges()
        );
    }
}
