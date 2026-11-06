package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.render.integration.DynamicTrainModelLoader;
import cn.zbx1425.mtrsteamloco.render.integration.DynamicTrainModelUnloadManager;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.client.DoorAnimationType;
import mtr.client.DynamicTrainModel;
import mtr.model.ModelTrainBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers OBJ/train model loading until the train is actually rendered, and
 * lets the aggressive unload manager rebuild an unloaded model on demand.
 *
 * The pristine properties snapshot kept by the unload manager must be restored
 * before rebuilding, otherwise the loader's destructive part-name rewriting
 * leaves the train with no parts (invisible train).
 */
@Mixin(DynamicTrainModel.class)
public class DynamicTrainModelMixin {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void ctor(JsonObject model, JsonObject properties, DoorAnimationType doorAnimationType, CallbackInfo ci) {
        DynamicTrainModel self = (DynamicTrainModel) (Object) this;
        DynamicTrainModelUnloadManager.register(self, model, properties);

        if (!ClientConfig.experimentalLazyTrainModels) {
            DynamicTrainModelLoader.loadInto(model, self);
            DynamicTrainModelUnloadManager.markLoaded(self);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void render(PoseStack matrices, VertexConsumer vertices, ModelTrainBase.RenderStage renderStage, int light, float doorLeftX, float doorRightX, float doorLeftZ, float doorRightZ, int currentCar, int trainCars, boolean head1IsFront, boolean renderDetails, CallbackInfo ci) {
        DynamicTrainModel self = (DynamicTrainModel) (Object) this;
        if (!DynamicTrainModelUnloadManager.isLoaded(self)) {
            JsonObject model = DynamicTrainModelUnloadManager.getModelJson(self);
            if (model != null) {
                DynamicTrainModelUnloadManager.restoreProperties(self);
                DynamicTrainModelLoader.loadInto(model, self);
            }
            DynamicTrainModelUnloadManager.markLoaded(self);
        }
        DynamicTrainModelUnloadManager.markRendered(self);
    }
}
