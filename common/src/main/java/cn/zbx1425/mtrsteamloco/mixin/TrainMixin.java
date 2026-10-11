package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import cn.zbx1425.mtrsteamloco.block.BlockEyeCandy;
import net.minecraft.world.phys.Vec3;
import mtr.render.TrainRendererBase;
import net.minecraft.util.Mth;
import mtr.path.PathData;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import org.msgpack.core.MessagePacker;
import cn.zbx1425.mtrsteamloco.network.util.StringMapSerializer;
import org.msgpack.value.Value;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.ConfigResponder;
import mtr.data.*;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Train.class)
public abstract class TrainMixin implements TrainExtraSupplier{

	protected List<Double> distances;
	protected List<PathData> path;

    private Map<String, String> customConfigs = new HashMap<>();
	private Map<String, ConfigResponder> configResponders = new HashMap<>();
	private boolean isConfigsChanged = false;
	
	@Override
	public Map<String, String> getCustomConfigs() {
		return customConfigs;
	}

	@Override
	public void setCustomConfigs(Map<String, String> customConfigs) {
		this.customConfigs = customConfigs;
	}

	@Override
	public boolean isConfigsChanged() {
		return isConfigsChanged;
	}

	@Override
	public void isConfigsChanged(boolean isConfigsChanged) {
		this.isConfigsChanged = isConfigsChanged;
	}

	@Override
	public void setConfigResponders(Map<String, ConfigResponder> configResponders) {
		this.configResponders = configResponders;
	}

	@Override
	public Map<String, ConfigResponder> getConfigResponders() {
		return configResponders;
	}

	@Shadow(remap = false)
    public abstract int getIndex(double tempRailProgress, boolean roundDown);

	/**
	 * 轨道滚转角查询。
	 *
	 * 参数 value 的单位是**沿整条线路的里程（米）**，不是车厢序号 ——
	 * 车厢序号到里程的换算在 TrainExtraSupplier.getRollAngleAt(Train, int) 静态方法里完成。
	 *
	 * 诊断：这里打印 mapSize，用来区分「轨道本来就没设滚转角（mapSize=0）」与
	 * 「设了但取值落到 0 区间」。mapSize=0 时 RailExtraSupplier.getRollAngle 必定返回 0。
	 */
	@Override
	public float getRollAngleAt(double value) {
		final double rawProgress = value;
		int i = getIndex(value, true);
		if (i != 0) value -= distances.get(i - 1);
        Rail r = path.get(i).rail;
        float rot = RailExtraSupplier.getRollAngle(r, value);
		if (cn.zbx1425.mtrsteamloco.data.Rolling.tickRollDiag()) {
			cn.zbx1425.mtrsteamloco.Main.LOGGER.info(String.format(
					"[ANTE-Roll] progress=%.2f -> i=%d/%d local=%.2f railLen=%.2f mapSize=%d roll=%.3f",
					rawProgress, i, path.size(), value, r.getLength(),
					((RailExtraSupplier) r).getRollAngleMap().size(), rot));
		}
		return rot;
	}

	@Inject(method = "<init>(JFLjava/util/List;Ljava/util/List;IIFZIILjava/util/Map;)V", at = @At("TAIL"), remap = false)
	private void fromMassagePack(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			Map<String, Value> map, CallbackInfo ci
	) {
		MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		try {
			customConfigs = StringMapSerializer.deserialize(messagePackHelper.getString("custom_configs"));
		} catch (IOException e) {
			customConfigs = new HashMap<>();
		}
	}

	@Inject(method = "<init>(JFLjava/util/List;Ljava/util/List;IIFZIILnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
	private void fromCompoundTag(
			long sidingId, float railLength,
			List<PathData> path, List<Double> distances, int repeatIndex1, int repeatIndex2,
			float accelerationConstant, boolean isManualAllowed, int maxManualSpeed, int manualToAutomaticTime,
			CompoundTag compoundTag, CallbackInfo ci
	) {
		try {
			customConfigs = StringMapSerializer.deserialize(compoundTag.getString("custom_configs"));
		} catch (IOException e) {
			customConfigs = new HashMap<>();
		}
	}

	@Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
	private void fromFriendlyByteBuf(FriendlyByteBuf buffer, CallbackInfo ci) {
		try {
			customConfigs = StringMapSerializer.deserialize(buffer.readUtf());
		} catch (IOException e) {
			customConfigs = new HashMap<>();
		}
	}

	@Inject(method = "toMessagePack", at = @At("TAIL"), remap = false)
    private void toMessagePack(MessagePacker messagePacker, CallbackInfo ci) throws IOException {
		String res;
		try {
			res = StringMapSerializer.serializeToString(customConfigs);
		} catch (IOException e) {
			res = "";
		}
		messagePacker.packString("custom_configs").packString(res);
	}

    @Inject(method = "messagePackLength", at = @At("TAIL"), cancellable = true, remap = false)
    private void messagePackLength(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() + 1);
    }

	@Inject(method = "writePacket", at = @At("TAIL"))
    private void toPacket(FriendlyByteBuf packet, CallbackInfo ci) {
		String res;
		try {
			res = StringMapSerializer.serializeToString(customConfigs);
		} catch (IOException e) {
			res = "";
		}
		packet.writeUtf(res);
	}

	protected abstract boolean skipScanBlocks(Level world, double trainX, double trainY, double trainZ);

    protected abstract boolean openDoors(Level world, Block block, BlockPos checkPos, int dwellTicks);

	protected float doorValue;

	protected boolean doorTarget;

	private static Class<?> IBlockPlatformClass = Void.class;

	static {
		try {
			IBlockPlatformClass = Class.forName("team.dovecotmc.metropolis.block.interfaces.IBlockPlatform");
			Main.LOGGER.info("Loaded metropolis IBlockPlatformClass");
		} catch (ClassNotFoundException e) {
			Main.LOGGER.error("Failed to load metropolis IBlockPlatformClass");
		}
	}

    @Inject(method = "scanDoors", at = @At("HEAD"), cancellable = true)
    private void onScanDoors(Level world, double trainX, double trainY, double trainZ, float checkYaw, float pitch, double halfSpacing, int dwellTicks, CallbackInfoReturnable<Boolean> ci) {
        if (skipScanBlocks(world, trainX, trainY, trainZ)) {
            ci.setReturnValue(false);
			return;
		}

		boolean hasPlatform = false;
		boolean isClientSide = world.isClientSide();
		final Vec3 offsetVec = new Vec3(1, 0, 0).yRot(checkYaw).xRot(pitch);
		final Vec3 traverseVec = new Vec3(0, 0, 1).yRot(checkYaw).xRot(pitch);
		Set<BlockPos> OKPos = new HashSet<>();
		for (int checkX = 1; checkX <= 3; checkX++) {
			for (int checkY = -2; checkY <= 3; checkY++) {
				for (double checkZ = -halfSpacing; checkZ <= halfSpacing; checkZ++) {
					final BlockPos checkPos = RailwayData.newBlockPos(trainX + offsetVec.x * checkX + traverseVec.x * checkZ, trainY + checkY, trainZ + offsetVec.z * checkX + traverseVec.z * checkZ);
					final Block block = world.getBlockState(checkPos).getBlock();

					if (block instanceof BlockPlatform || block instanceof BlockPSDAPGBase || IBlockPlatformClass.isInstance(block)) {
						openDoors(world, block, checkPos, dwellTicks);
						hasPlatform = true;
					}else if (block instanceof BlockEyeCandy) {
						if (OKPos.contains(checkPos)) continue;
						int[] dir = new int[]{1, -1};
						int[] f = new int[]{1, 0, 0, 1, 0, 0};
						if (checkEyeCandy(world, checkPos, isClientSide)) hasPlatform = true;
						for (int i = 0; i < 3; i++) {
							for (int j = 0; j < 2; j++) {
								for (int k = 1; k <= 40; k++) {
									int v = dir[j] * k;
									BlockPos pos = checkPos.offset(f[i] * v, f[i + 1] * v, f[i + 2] * v);
									if (OKPos.contains(pos)) break;
									OKPos.add(pos);
									if (checkEyeCandy(world, pos, isClientSide)) hasPlatform = true;
									else break;
								}
							}
						}
						
					}
				}
			}
		}
        ci.setReturnValue(hasPlatform);
		return;
    }

	private boolean checkEyeCandy(Level world, BlockPos pos, boolean isClientSide) {
		final BlockEntity entity = world.getBlockEntity(pos);
		if (entity instanceof BlockEyeCandy.BlockEntityEyeCandy) {
			BlockEyeCandy.BlockEntityEyeCandy e = (BlockEyeCandy.BlockEntityEyeCandy) entity;
			if (e.isPlatform()) {
				if (isClientSide) {
					e.setDoorTarget(doorTarget);
					e.setDoorValue(doorValue);
				}
				return true;
			} else return false;
		} else {
			return false;
		}
	}

	private static interface Void {
	}

	@Inject(method = "convertMaxManualSpeed", at = @At("HEAD"), cancellable = true, remap = false)
	private static void convertMaxManualSpeed(int maxManualSpeed, CallbackInfoReturnable<RailType> ci) {
		maxManualSpeed = Math.min(maxManualSpeed, RailType.values().length - 1);
		ci.setReturnValue(RailType.values()[maxManualSpeed]);
		ci.cancel();
		return;
	}
}
