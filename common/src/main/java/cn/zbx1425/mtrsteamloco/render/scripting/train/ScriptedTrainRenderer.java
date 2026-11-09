package cn.zbx1425.mtrsteamloco.render.scripting.train;

import cn.zbx1425.mtrsteamloco.MainClient;
import net.minecraft.client.Minecraft;
import cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase;
import cn.zbx1425.mtrsteamloco.render.RenderSpace;
import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.render.RenderUtil;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptContextManager;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptHolder;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.PoseStackUtil;
import cn.zbx1425.sowcer.math.Vector3f;
import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.render.RenderTrains;
import mtr.render.TrainRendererBase;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import mtr.data.Rail;
import cn.zbx1425.mtrsteamloco.render.scripting.train.TrainWrapper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ScriptedTrainRenderer extends TrainRendererBase {

    public final ScriptHolder typeScripting;
    public final TrainRendererBase baseRenderer;

    private final TrainClient train;
    private final TrainScriptContext trainScripting;

    public ScriptedTrainRenderer(ScriptHolder typeScripting, TrainRendererBase baseRenderer) {
        this.typeScripting = typeScripting;
        this.baseRenderer = baseRenderer;
        this.train = null;
        this.trainScripting = null;
    }

    private ScriptedTrainRenderer(ScriptedTrainRenderer base, TrainClient trainClient) {
        this.typeScripting = base.typeScripting;
        this.baseRenderer = base.baseRenderer == null ? null : base.baseRenderer.createTrainInstance(trainClient);
        this.train = trainClient;
        this.trainScripting = new TrainScriptContext(trainClient);
    }

    @Override
    public TrainRendererBase createTrainInstance(TrainClient trainClient) {
        ScriptedTrainRenderer result = new ScriptedTrainRenderer(this, trainClient);
        return result;
    }

    @Override
    public void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen) {
        assert train != null && trainScripting != null;
        boolean shouldRender = !RenderUtil.shouldSkipRenderTrain(train);

        if (shouldRender && baseRenderer != null) {
            baseRenderer.renderCar(carIndex, x, y, z, yaw, pitch, doorLeftOpen, doorRightOpen);
        }

        if (isTranslucentBatch) return;

        final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);

        final boolean hasPitch = pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending;
        // rawPose 保持宿主原样（原版 R*T(-cam)，已修复的 YMTR 是 R）：
        // 它的平移列在「已修复」宿主上正好是 0，后续叠加小位移时不会出现大数相消。
        Matrix4f rawPose = new Matrix4f(matrices.last().pose()).copy();
        // worldPose 统一成 R*T(-cam)，给脚本绘制与声源定位用（它们按世界坐标工作）。
        Matrix4f worldPose = RenderSpace.normalizeView(rawPose.copy());

        float roll = TrainExtraSupplier.getRollAngleAt(train, carIndex);
        boolean isReversed = train.isReversed();

        Matrix4f carPose = new Matrix4f();

        carPose.translate((float) x, (float) y, (float) z);
        Vec3 offset = train.vehicleRidingClient.getVehicleOffset();
        if (offset != null) {
            carPose.translate((float) offset.x, (float) offset.y, (float) offset.z);
        }
        carPose.rotateY((float) Math.PI + yaw);
        carPose.rotateX(hasPitch ? pitch : 0);
        carPose.translate(0, -1, 0);
        carPose.rotateZ(isReversed? -roll : roll);
        carPose.translate(0, 1, 0);

        Matrix4f copy = carPose.copy();

        TrainWrapper trainExtra = trainScripting.trainExtra;
        trainExtra.doorLeftOpen[carIndex] = doorLeftOpen;
        trainExtra.doorRightOpen[carIndex] = doorRightOpen;
        trainExtra.lastWorldPose[carIndex] = copy.copy();
        trainExtra.lastCarPosition[carIndex] = copy.getTranslationPart();
        trainExtra.lastCarRotation[carIndex] = copy.getEulerAnglesXYZ();
        trainExtra.shouldRender = shouldRender;
        trainExtra.isInDetailDistance = true;// (posAverage != null && posAverage.distSqr(camera.getBlockPosition()) <= RenderTrains.DETAIL_RADIUS_SQUARED);

        if (posAverage == null) {
            if (carIndex == train.trainCars - 1) {
                // So it's outside visible range, but still need to call render function
                trainScripting.extraFinished();
                typeScripting.tryCallRenderFunctionAsync(trainScripting);
            }
            return;
        }


        final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));
        
        if (shouldRender) {
            // basePose 是 rawPose 的**副本**，改写它的平移列不会影响宿主栈。
            // 统一做法：平移列直接置为 R*(车厢坐标 - 相机坐标)，全程 double 计算后一次写回。
            // 这一步既避免了「两个 1e7 相减」的抖动，也避免了坐标被量化成整格导致的卡顿。
            Matrix4f basePose = rawPose.copy();
            if (ClientConfig.precisionFix && train.getViewOffset() == null) {
                Vec3 carCameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                RailChunkBase.setCameraRelativeTranslation(basePose,
                        x - carCameraPos.x, y - carCameraPos.y, z - carCameraPos.z);
            } else {
                // 玩家在车上时坐标已含玩家偏移、栈形态也不同，保持原来的叠加方式
                final Vec3 carShift = RenderSpace.worldShift();
                basePose.translate((float)(x + carShift.x), (float)(y + carShift.y), (float)(z + carShift.z));
            }
            basePose.rotateY((float) Math.PI + yaw);
            basePose.rotateX(hasPitch ? pitch : 0);
            basePose.translate(0, -1, 0);
            basePose.rotateZ(isReversed? -roll : roll);
            basePose.translate(0, 1, 0);
            synchronized (trainScripting) {
                trainScripting.commitCar(carIndex, MainClient.drawScheduler, basePose, worldPose, light);
            }
        }

        matrices.popPose();

        if (carIndex == train.trainCars - 1) {
            trainScripting.extraFinished();
            typeScripting.tryCallRenderFunctionAsync(trainScripting);
        }
    }

    @Override
    public void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
        assert train != null && trainScripting != null;
        if (RenderUtil.shouldSkipRenderTrain(train)) return;

        if (baseRenderer != null) {
            baseRenderer.renderConnection(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, x, y, z, yaw, pitch);
        }

        if (isTranslucentBatch) return;

        final BlockPos posAverage = applyAverageTransform(train.getViewOffset(), x, y, z);
        if (posAverage == null) return;
        Matrix4f worldPose = RenderSpace.normalizeView(new Matrix4f(matrices.last().pose()).copy());
        matrices.pushPose();
        // pose stack 本身没有归一化，这里补上「世界坐标 - 相机坐标」
        final Vec3 connShift = RenderSpace.worldShift();
        matrices.translate(x + connShift.x, y + connShift.y, z + connShift.z);
        PoseStackUtil.rotY(matrices, (float) Math.PI + yaw);
        final boolean hasPitch = pitch < 0 ? train.transportMode.hasPitchAscending : train.transportMode.hasPitchDescending;
        PoseStackUtil.rotX(matrices, hasPitch ? pitch : 0);
        final int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, posAverage), world.getBrightness(LightLayer.SKY, posAverage));
        Matrix4f pose = new Matrix4f(matrices.last().pose());
        synchronized (trainScripting) {
            trainScripting.scriptResult.commitConn(0, MainClient.drawScheduler, pose, worldPose, light);
            matrices.popPose();
            trainScripting.scriptResult.commitConnImmediate(0, matrices, vertexConsumers,
                    prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, light);
        }

        matrices.popPose();
    }

    @Override
    public void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch) {
        assert train != null && trainScripting != null;
        if (RenderUtil.shouldSkipRenderTrain(train)) return;

        if (baseRenderer != null) {
            baseRenderer.renderBarrier(prevPos1, prevPos2, prevPos3, prevPos4, thisPos1, thisPos2, thisPos3, thisPos4, x, y, z, yaw, pitch);
        }
    }
}
