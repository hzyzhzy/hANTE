package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.EnqueueProp;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.sowcer.model.Model;
import cn.zbx1425.sowcer.model.VertArrays;
import cn.zbx1425.sowcer.vertex.VertAttrMapping;
import cn.zbx1425.sowcer.vertex.VertAttrSrc;
import cn.zbx1425.sowcer.vertex.VertAttrState;
import cn.zbx1425.sowcer.vertex.VertAttrType;
import cn.zbx1425.sowcerext.model.RawModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import cn.zbx1425.sowcerext.reuse.DrawScheduler;

import java.util.ArrayList;
import java.util.Map;

public class MeshBuildingRailChunk extends RailChunkBase {

    private final RawModel railModel;

    private Model uploadedCombinedModel;
    private VertArrays vertArrays;

    private static final VertAttrMapping RAIL_MAPPING = new VertAttrMapping.Builder()
            .set(VertAttrType.POSITION, VertAttrSrc.VERTEX_BUF)
            .set(VertAttrType.COLOR, VertAttrSrc.VERTEX_BUF_OR_GLOBAL)
            .set(VertAttrType.UV_TEXTURE, VertAttrSrc.VERTEX_BUF)
            .set(VertAttrType.UV_OVERLAY, VertAttrSrc.GLOBAL)
            .set(VertAttrType.UV_LIGHTMAP, VertAttrSrc.VERTEX_BUF_OR_GLOBAL)
            .set(VertAttrType.NORMAL, VertAttrSrc.VERTEX_BUF)
            .set(VertAttrType.MATRIX_MODEL, VertAttrSrc.GLOBAL)
            .build();

    protected MeshBuildingRailChunk(Long chunkId, String modelKey) {
        super(chunkId, modelKey);
        this.railModel = RailModelRegistry.getProperty(modelKey).getRawModel();
    }

    @Override
    public void rebuildBuffer(Level world) {
        super.rebuildBuffer(world);
        if (railModel == null) return;

        // 几何以「区块相对坐标」烘焙，取光照时要加回区块原点换回世界坐标
        final boolean precise = ClientConfig.precisionFix;
        Vec3 chunkOrigin = getChunkOrigin();
        float yMin = 256, yMax = -64;
        RawModel combinedModel = new RawModel();
        for (Map.Entry<BakedRail, ArrayList<Matrix4f>> entry : containingRails.entrySet()) {
            ArrayList<Matrix4f> railSpan = entry.getValue();
            for (Matrix4f pieceMat : railSpan) {
                final Vec3 lightPos = precise
                        ? chunkOrigin.add(pieceMat.getTranslationPart().toVec3())
                        : pieceMat.getTranslationPart().toVec3();
                yMin = Math.min(yMin, (float) lightPos.y);
                yMax = Math.max(yMax, (float) lightPos.y);
                final BlockPos lightBlockPos = new BlockPos(Mth.floor(lightPos.x), Mth.floor(lightPos.y + 0.1), Mth.floor(lightPos.z));
                final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, lightBlockPos), world.getBrightness(LightLayer.SKY, lightBlockPos));
                combinedModel.appendTransformed(railModel, pieceMat, entry.getKey().color, light);
            }
        }
        if (vertArrays != null) vertArrays.close();
        if (uploadedCombinedModel != null) uploadedCombinedModel.close();
        uploadedCombinedModel = combinedModel.upload(RAIL_MAPPING);
        vertArrays = VertArrays.createAll(uploadedCombinedModel, RAIL_MAPPING, null);

        if (yMin > yMax) yMin = yMax;
        setBoundingBox(yMin, yMax);
    }

    @Override
    public void enqueue(BatchManager batchManager, ShaderProp shaderProp) {
        if (railModel == null) return;

        if (vertArrays == null) return;
        Matrix4f modelMatrix;
        if (ClientConfig.precisionFix) {
            // 顶点是区块相对坐标，模型矩阵只需补上「本区块相对相机」的位移
            Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            Vec3 chunkOrigin = getChunkOrigin();
            modelMatrix = RailChunkBase.cameraRelativeMatrix(shaderProp.viewMatrix,
                    chunkOrigin.x - cameraPos.x, chunkOrigin.y - cameraPos.y, chunkOrigin.z - cameraPos.z);
        } else {
            modelMatrix = shaderProp.viewMatrix;
        }
        VertAttrState attrState = new VertAttrState().setModelMatrix(modelMatrix).setOverlayUVNoOverlay();
        if (!RailRenderDispatcher.isHoldingRailItem) attrState.setColor(-1);
        batchManager.enqueue(vertArrays, new EnqueueProp(attrState), ShaderProp.DEFAULT);
    }

    @Override
    public void close() {
        if (vertArrays != null) vertArrays.close();
        if (uploadedCombinedModel != null) uploadedCombinedModel.close();
    }
}
