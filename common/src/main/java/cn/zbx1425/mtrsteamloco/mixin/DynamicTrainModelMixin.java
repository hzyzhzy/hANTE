package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.render.integration.DynamicTrainModelLoader;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.DoorAnimationType;
import mtr.client.DynamicTrainModel;
import mtr.model.ModelTrainBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 可选的列车模型懒加载：开关关闭时在构造期加载，开启时延到第一次渲染。
 * <p>
 * 原先这里还会把模型 JSON 与 properties 的深拷贝交给一个「卸载管理器」保存，
 * 用于重建被卸载的模型；但主动卸载功能已经移除，那两份副本变成纯开销
 * （每列车型一份，且模型对象被 MTR 长期持有，永远不会被回收），已一并删除。
 */
@Mixin(DynamicTrainModel.class)
public class DynamicTrainModelMixin {

    @Unique
    private JsonObject ante$modelJson;
    @Unique
    private boolean ante$loaded = false;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void ctor(JsonObject model, JsonObject properties, DoorAnimationType doorAnimationType, CallbackInfo ci) {
        DynamicTrainModel self = (DynamicTrainModel) (Object) this;
        if (!ClientConfig.experimentalLazyTrainModels) {
            DynamicTrainModelLoader.loadInto(model, self);
            ante$loaded = true;
        } else {
            // 只保存引用，不做深拷贝
            ante$modelJson = model;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void render(PoseStack matrices, VertexConsumer vertices, ModelTrainBase.RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails, CallbackInfo ci) {
        if (ante$loaded) return;
        ante$loaded = true;
        if (ante$modelJson != null) {
            DynamicTrainModelLoader.loadInto(ante$modelJson, (DynamicTrainModel) (Object) this);
            ante$modelJson = null;   // 加载完就不再持有
        }
    }
}
