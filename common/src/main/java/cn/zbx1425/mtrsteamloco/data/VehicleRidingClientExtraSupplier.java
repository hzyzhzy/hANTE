package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public interface VehicleRidingClientExtraSupplier {
    float getRoll(int index);
    void setRoll(float[] rolls);
    void setPositions(Vec3[] positions);
    void setReversed(boolean reversed);
    void removeRider(UUID uuid);
}