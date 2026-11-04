package cn.zbx1425.mtrsteamloco.mixin;

import mtr.data.VehicleRidingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * YMTR 3.6.3's VehicleRidingClient has no stopRiding(UUID); virtual drive needs to detach the
 * player from the real train after copying the riding percentages.
 */
@Mixin(value = VehicleRidingClient.class, remap = false)
public abstract class VehicleRidingClientAccessor {

    @Accessor(value = "ridingEntities", remap = false)
    public abstract Set<UUID> getRidingEntities();

    @Accessor(value = "percentagesX", remap = false)
    public abstract Map<UUID, Float> getPercentagesX();

    @Accessor(value = "percentagesZ", remap = false)
    public abstract Map<UUID, Float> getPercentagesZ();

    @Accessor(value = "newPercentagesX", remap = false)
    public abstract Map<UUID, Float> getNewPercentagesX();

    @Accessor(value = "newPercentagesZ", remap = false)
    public abstract Map<UUID, Float> getNewPercentagesZ();

    public void removeRider(UUID uuid) {
        getRidingEntities().remove(uuid);
        getPercentagesX().remove(uuid);
        getPercentagesZ().remove(uuid);
        getNewPercentagesX().remove(uuid);
    }
}
