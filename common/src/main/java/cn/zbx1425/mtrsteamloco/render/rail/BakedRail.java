package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.util.AttrUtil;
import net.minecraft.world.phys.Vec3;
import mtr.data.Rail;
import cn.zbx1425.mtrsteamloco.data.RailModelProperties;
import net.minecraft.util.Mth;
import cn.zbx1425.mtrsteamloco.Main;

import java.util.ArrayList;
import java.util.HashMap;
import cn.zbx1425.mtrsteamloco.render.scripting.rail.RailScriptContext;

public class BakedRail {

    public HashMap<Long, ArrayList<Matrix4f>> coveredChunks = new HashMap<>();

    public static final int POS_SHIFT = 1;

    public String modelKey;
    public int color;
    public RailScriptContext scriptContext;

    public BakedRail(Rail rail) {
        modelKey = RailRenderDispatcher.getModelKeyForRender(rail);
        RailModelProperties prop = getProperties();
        color = AttrUtil.argbToBgr(rail.railType.color | 0xFF000000);

        if (prop.script != null) {
            scriptContext = new RailScriptContext(rail);
        }

        if (!modelKey.equals("null")) {
            RailExtraSupplier supplier = (RailExtraSupplier) rail;
            final boolean reverse = supplier.getRenderReversed();
            final float interval = prop.repeatInterval;
            final float yOffset = prop.yOffset;
            final double length = rail.getLength();
            final double num = Math.floor(length / interval);
            final double ins = (length - num * interval) / num + interval;
            Vec3 pre = rail.getPosition(0);
            for (double i = ins; i < length + ins * 0.8D; i += ins) {
                Vec3 thi = rail.getPosition(i);
                Vec3 mid = rail.getPosition(i - interval / 2);
                float roll = RailExtraSupplier.getRollAngle(rail, i - interval / 2);
                // 关键：矩阵里只存「区块相对坐标」，不要把世界坐标塞进 float。
                // 远处坐标（百万级以上）下 float 精度不足以区分相邻轨道片，会抖动甚至消失。
                final Vec3 bakePos;
                if (ClientConfig.precisionFix) {
                    Vec3 chunkOrigin = new Vec3((int) mid.x & ~31, 0, (int) mid.z & ~31);
                    bakePos = mid.subtract(chunkOrigin);
                } else {
                    bakePos = mid;
                }
                coveredChunks
                    .computeIfAbsent(chunkIdFromWorldPos((int) mid.x, (int) mid.z), ignored -> new ArrayList<>())
                    .add(getLookAtMat(bakePos, pre, thi, roll, yOffset, reverse));
                pre = thi;
            }
        }
    }

    public void dispose() {
        if (scriptContext != null) {
            scriptContext.dispose();
        }
    }

    public RailModelProperties getProperties() {
        return RailModelRegistry.getProperty(modelKey);
    }

    /**
     * 用 int 而不是 float 接收坐标：float 在 1000 万格处无法区分相邻方块，
     * 会把轨道片分配到错误的区块，导致远处轨道整段消失。
     */
    public static long chunkIdFromWorldPos(int bpX, int bpZ) {
        return ((long)(bpX >> (4 + POS_SHIFT)) << 32) | ((long)(bpZ >> (4 + POS_SHIFT)) & 0xFFFFFFFFL);
    }

    public static long chunkIdFromSectPos(int spX, int spZ) {
        return ((long)(spX >> POS_SHIFT) << 32) | ((long)(spZ >> POS_SHIFT) & 0xFFFFFFFFL);
    }

    public static Matrix4f getLookAtMat(Vec3 pos, Vec3 last, Vec3 next, float roll, float yOffset, boolean reverse) {

        Matrix4f matrix4f = new Matrix4f();
        matrix4f.translate((float) pos.x, (float) pos.y, (float) pos.z);

        if (reverse) {
            Vec3 temp = last;
            last = next;
            next = temp;
        }

        final float yaw = (float) Mth.atan2(next.x - last.x, next.z - last.z);
        final float pitch = (float) Mth.atan2(next.y - last.y, (float) Math.sqrt((next.x - last.x) * (next.x - last.x) + (next.z - last.z) * (next.z - last.z)));

        matrix4f.rotateY(yaw);
        matrix4f.rotateX(-pitch);
        matrix4f.translate(0, yOffset, 0);
        matrix4f.rotateZ(reverse? -roll : roll);

        return matrix4f;
    }
}
