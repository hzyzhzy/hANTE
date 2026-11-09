package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import mtr.data.Rail;
import mtr.data.RailAngle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import cn.zbx1425.mtrsteamloco.render.scripting.util.DynamicModelHolder;
import cn.zbx1425.sowcerext.reuse.DrawScheduler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.Closeable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;

public abstract class RailChunkBase implements Closeable {

    public Long chunkId;
    public AABB boundingBox;
    public HashMap<BakedRail, ArrayList<Matrix4f>> containingRails = new HashMap<>();

    public final String modelKey;

    protected float modelYMin;
    protected float modelYMax;

    public boolean isDirty = false;
    public boolean bufferBuilt = false;
    public double cameraDistManhattanXZ = 0;

    public RailChunkBase(long chunkId, String modelKey) {
        this.chunkId = chunkId;
        this.modelKey = modelKey;
        long boundary = RailModelRegistry.getProperty(modelKey).getBoundingBox();
        modelYMin = Float.intBitsToFloat((int)(boundary >> 32));
        modelYMax = Float.intBitsToFloat((int)(boundary & 0xFFFFFFFFL));
        setBoundingBox(0, 0);
    }

    protected void setBoundingBox(float yMin, float yMax) {
        int posXMin = (int)(chunkId >> 32) << (4 + BakedRail.POS_SHIFT);
        int posZMin = (int)(chunkId & 0xFFFFFFFFL) << (4 + BakedRail.POS_SHIFT);
        int span = 1 << (4 + BakedRail.POS_SHIFT);
        // 加一圈余量，避免跨越区块边界的长模型被整块剔除（远处坐标下尤其明显）
        double margin = 8.0;
        boundingBox = new AABB(posXMin - margin, yMin + modelYMin - 1 - margin, posZMin - margin,
                posXMin + span + margin, yMax + modelYMax + 1 + margin, posZMin + span + margin);
    }

    public boolean isEven() { // Just for ease of debugging to show a checkerboard pattern.
        return ((int)(chunkId >> 32) + (int)(chunkId & 0xFFFFFFFFL)) % 2 == 0;
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos((int)(chunkId >> 32) << BakedRail.POS_SHIFT, (int)(chunkId & 0xFFFFFFFFL) << BakedRail.POS_SHIFT);
    }

    public boolean containsYSection(int yMin, int yMax) {
        return (yMin << 4) < boundingBox.minY || (yMax << 4) > boundingBox.maxY;
    }

    public double getCameraDistManhattanXZ(Vec3 cameraPos) {
        cameraDistManhattanXZ = Math.abs(cameraPos.x - (boundingBox.minX + boundingBox.maxX) / 2)
                + Math.abs(cameraPos.z - (boundingBox.minZ + boundingBox.maxZ) / 2);
        return cameraDistManhattanXZ;
    }

    public void addRail(BakedRail rail) {
        containingRails.put(rail, rail.coveredChunks.get(chunkId));
        isDirty = true;
    }

    public void removeRail(BakedRail rail) {
        containingRails.remove(rail);
        isDirty = true;
    }

    public void rebuildBuffer(Level world) {
        isDirty = false;
        bufferBuilt = true;
    }

    /**
     * 该区块在**世界坐标**中的原点（X/Z 对齐到区块，Y 固定为 0）。
     * <p>
     * 轨道几何现在一律以「区块相对坐标」烘焙：顶点与实例矩阵里只保存 0~32 范围的
     * 小数，世界坐标的大数值只保留在 double 侧。渲染时再把这个原点减去相机坐标，
     * 得到「相机相对位移」传给矩阵。
     * <p>
     * 这样做的原因是浮点精度：float 只有 24 位尾数，坐标到 100 万格时最小分辨率已
     * 退化到 1/16 格，到 1000 万格时约 1 格 —— 直接把世界坐标塞进 float 的顶点/矩阵，
     * 远处的轨道就会抖动，再远直接退化成零面积三角形而消失。
     */
    public Vec3 getChunkOrigin() {
        int posXMin = (int)(chunkId >> 32) << (4 + BakedRail.POS_SHIFT);
        int posZMin = (int)(chunkId & 0xFFFFFFFFL) << (4 + BakedRail.POS_SHIFT);
        return new Vec3(posXMin, 0, posZMin);
    }

    /**
     * 把 {@code viewMatrix} 的平移列换成「本区块相对相机」的位移，得到该区块模型矩阵。
     * <p>
     * 为什么不能直接 {@code viewMatrix.copy().translate(chunkOrigin)}：MTR 在
     * {@code RenderTrains.render} 开头就做过一次 {@code translate(-cameraX, -cameraY, -cameraZ)}，
     * 所以传进来的 viewMatrix 平移列已经是 {@code R * (-cam)}（量级可达 1000 万）。
     * 再用 float 叠加一个同量级的 {@code R * chunkOrigin}，两者相减会把有效数字全部吃掉
     * （float 在 1000 万处最小分辨率约 1 格），远处轨道就会抖动甚至消失。
     * <p>
     * 这里改为先在 double 下算出小数值的相机相对位移，再用旋转矩阵变换后**直接覆盖**
     * 平移列。参与运算的两个量都是小数值，精度完整保留。
     *
     * @param relX/relY/relZ 目标相对相机的位移（世界坐标差，double）
     */
    public static Matrix4f cameraRelativeMatrix(Matrix4f viewMatrix, double relX, double relY, double relZ) {
        float[] vals = new float[16];
        FloatBuffer buf = FloatBuffer.wrap(vals);
        viewMatrix.store(buf);
        // 列主序：col0 = (0,1,2)，col1 = (4,5,6)，col2 = (8,9,10)
        double tx = vals[0] * relX + vals[4] * relY + vals[8]  * relZ;
        double ty = vals[1] * relX + vals[5] * relY + vals[9]  * relZ;
        double tz = vals[2] * relX + vals[6] * relY + vals[10] * relZ;
        vals[12] = (float) tx;
        vals[13] = (float) ty;
        vals[14] = (float) tz;
        buf.position(0);
        Matrix4f result = viewMatrix.copy();
        result.load(buf);
        return result;
    }

    /**
     * 判断传进来的 {@code viewMatrix} 里是否已经烘焙了相机平移。
     * <p>
     * 原版 MTR 会在 {@code RenderTrains.render} 开头 {@code translate(-cameraX, -cameraY, -cameraZ)}，
     * 但这正是「远处坐标精度崩坏」的根源之一。YMTR 上游的「距离现象修复」把这次平移**去掉**了，
     * 改成每个物体自己在 double 下减相机坐标（提交 80395926）。
     * <p>
     * hANTE 要在两种宿主上都能跑：
     * <ul>
     *   <li>原版 MTR/YMTR（未修复）：栈里带 {@code -cam}，用世界坐标直接画即可；</li>
     *   <li>YMTR（已修复）：栈里不带 {@code -cam}，必须自己减相机坐标。</li>
     * </ul>
     * 判断方法：栈的平移列本应是 {@code R * (-cam)}，拿它和期望值比一下就能区分。
     * 相机靠近原点时两者都接近 0，此时两种做法结果相同，判错也无害。
     */
    public static boolean isCameraBakedIn(Matrix4f viewMatrix, Vec3 cameraPos) {
        float[] vals = new float[16];
        FloatBuffer buf = FloatBuffer.wrap(vals);
        viewMatrix.store(buf);
        double ex = -(vals[0] * cameraPos.x + vals[4] * cameraPos.y + vals[8]  * cameraPos.z);
        double ey = -(vals[1] * cameraPos.x + vals[5] * cameraPos.y + vals[9]  * cameraPos.z);
        double ez = -(vals[2] * cameraPos.x + vals[6] * cameraPos.y + vals[10] * cameraPos.z);
        // 容差取 16 格：远大于 float 在 1000 万格处的误差（约 1 格），又远小于通常的相机距离
        final double tolerance = 16.0;
        return Math.abs(vals[12] - ex) < tolerance
                && Math.abs(vals[13] - ey) < tolerance
                && Math.abs(vals[14] - ez) < tolerance;
    }

    /**
     * 就地改写矩阵的平移列：{@code translation = R * (relX, relY, relZ)}，
     * 其中 R 取自矩阵自身的 3×3。整个过程用 double 计算，再一次性写回，
     * 避免「世界坐标 + 相机坐标」两个大数在 float 里相减。
     * <p>
     * 用于把宿主 pose stack 里已经烘焙好的 {@code R * (-cam)} 直接替换成
     * {@code R * (目标坐标 - cam)}。
     */
    public static void setCameraRelativeTranslation(Matrix4f liveMatrix, double relX, double relY, double relZ) {
        float[] vals = new float[16];
        FloatBuffer buf = FloatBuffer.wrap(vals);
        liveMatrix.store(buf);
        vals[12] = (float)(vals[0] * relX + vals[4] * relY + vals[8]  * relZ);
        vals[13] = (float)(vals[1] * relX + vals[5] * relY + vals[9]  * relZ);
        vals[14] = (float)(vals[2] * relX + vals[6] * relY + vals[10] * relZ);
        buf.position(0);
        liveMatrix.load(buf);
    }

    /** 去掉矩阵的平移部分，只保留旋转/缩放。 */
    public static Matrix4f withoutTranslation(Matrix4f matrix) {
        float[] vals = new float[16];
        FloatBuffer buf = FloatBuffer.wrap(vals);
        matrix.store(buf);
        vals[12] = 0; vals[13] = 0; vals[14] = 0;
        buf.position(0);
        Matrix4f result = matrix.copy();
        result.load(buf);
        return result;
    }

    public abstract void enqueue(BatchManager batchManager, ShaderProp shaderProp);

    @Override
    public void close() {

    }
}
