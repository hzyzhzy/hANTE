package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Mutable;

import java.util.List;

@Mixin(Train.class)
public interface TrainAccessor {

    @Accessor(value = "distances", remap = false)
    List<Double> getDistances();

    @Mutable
    @Accessor(value = "maxManualSpeed", remap = false)
    void setMaxManualSpeed(int value);
}
