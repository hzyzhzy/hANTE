package cn.zbx1425.mtrsteamloco.data;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.sowcer.math.Vector3f;

/**
 * 列车视角惯性（弹簧-阻尼）。
 *
 * 目标：玩家站在行驶中的列车上时，相机像系在车体上的弹簧重物 —— 列车加速时往后滞、
 * 减速时往前冲、过弯时往弯外甩，然后自行收敛回原位，类似真人头部在车厢里的晃动。
 *
 * ── 四个关键设计（都对应实测踩到的坑）────────────────────────────
 *
 * 1) 驱动量用**加速度**而不是速度。
 *    列车匀速行驶时相机应当完全跟随（无偏移），只有速度变化（起步、进站、过弯、坡道起伏）
 *    才有惯性位移。若用速度驱动，匀速行驶会留下恒定偏移，相机永远回不到座位正上方。
 *
 * 2) 驱动源用**车厢的世界坐标位置**（Rolling.getRidingCarPos），而不是相机位置。
 *    用相机位置会引入两类假惯性：
 *      · 玩家在车厢里自己走动 → 相机位置变了 → 误判为列车加速（车里走路也会晃）
 *      · 车体滚转/俯仰时眼高向量跟着转 → 相机位置随之变化 → 又一次误判（晃动和车一起晃）
 *    用车厢位置作驱动源，只有列车自身的运动才产生惯性。
 *
 * 3) 偏移保存在**世界坐标系**，叠加在相机最终位置上。
 *    这样惯性方向不会跟着车体姿态旋转（否则过弯时"甩"的方向会被车体旋转带偏）。
 *
 * 4) ★ 速度与加速度都必须**先低通再差分**（本次修复的核心）。
 *    车厢位置每帧带有微小抖动（MTR 的插值推进以帧时间为步长，帧时间本身在波动），
 *    而原实现是「位置差分取速度、速度差分取加速度」—— 两级差分对噪声是平方级放大：
 *        噪声 0.01 格/帧 → v≈0.6 格/s → a≈36 格/s²
 *        稳态偏移 = a·strength/stiffness ≈ 4.7 格，远超上限，于是被截到 MAX_OFFSET
 *    结果就是画面以最大幅度来回猛甩、列车看起来在抖动着前进、过弯时最夸张。
 *    现在对速度、加速度各加一阶低通（时间常数 0.12s / 0.18s）：每秒数十次的抖动噪声
 *    被衰减两个数量级，而真实的加减速（低频，持续数百毫秒以上）几乎不受影响。
 *
 * 另外识别"传送/强制对齐"：MTR 客服务端位置不同步，到站会强制对齐，那是位置突变。
 * 单帧位移换算出的速度超过阈值时视为传送，只重置基准、不产生惯性，避免"到站猛晃一下"。
 *
 * 稳态偏移量约为  a · inertiaStrength / inertiaStiffness  （格）
 * 三个参数都可在模组设置界面调（见 ConfigScreen）。
 */
public class CameraInertia {

    /** 单帧最大时间步，避免卡顿后一次性积分出巨大偏移。 */
    private static final float MAX_DT = 0.10f;
    /**
     * ★ 最小积分步长（秒）：小于它就认定是「同一帧内的重复调用」，直接跳过、不积分。
     *
     * `Camera.setup` 一帧内会被调用多次（主 pass、光影的阴影 pass、第三人称 mod 自己的相机），
     * 每次都会走到这里。不跳过的话，时间被切成 1~5ms 的碎片，而位置低通的增量 ΔsmoothC
     * 在这么小的 dt 下只剩量化噪声 —— `rawV = ΔsmoothC / dt` 于是给出几十上百格/秒的假速度。
     * 实测日志：车速 6.5 时「原始vPeak=83.8 平滑aPeak=34.8」，且一段里「传送」触发 393 次。
     * 这是用户报的「速度 100 以上时车厢突然晃到一边、又马上弹回来」的根源。
     *
     * ⚠ 跳过时**绝不能**更新 lastNanos：否则每次调用都把时间基准往前推，
     * 真正该积分的那一次永远等不到足够长的 dt，惯性会彻底失效。
     */
    private static final float MIN_DT = 0.004f;
    /**
     * ★ 子步积分的最大步长（秒）。
     *
     * 弹簧用的是显式欧拉：`vel -= off·k·dt; off += vel·dt`，它的稳定条件是 **k·dt < 2**。
     * 用户把刚度调到了 70，于是 dt > 2/70 ≈ 28.6ms（约 35 FPS 以下）时积分就开始发散 ——
     * 帧率一抖就「猛地晃一下」，之后还要来回振荡一秒以上才停，正是用户报告的
     * 「遭遇卡顿时还是会猛地晃一下，而且会持续很久」。
     *
     * 把每帧的 dt 切成 ≤ 8ms 的子步轮流积分（k·dt ≤ 0.56，远在稳定区内），
     * 既不发散，又让手感与帧率无关。子步数上限 16，最坏开销也只是几十次浮点运算。
     */
    private static final float MAX_SUB_DT = 0.008f;
    /** 子步积分循环的上限，防止极端 dt 下循环过长。 */
    private static final int MAX_SUBSTEPS = 16;
    /** 目标速度上限（格/秒）：正常列车远低于此值，超过即视为传送/对齐跳变。
     *
     * ★ 80 → 250：线路上车速能到 28 格/秒（约 100 km/h），而位置噪声换算出的假速度峰值
     *   实测能到 80+，80 的阈值在高速段几乎每帧都误判成传送。250 只抓真正的远距离瞬移，
     *   小位移瞬移仍由下面的 JUMP_DV 负责。 */
    private static final float TELEPORT_SPEED = 250.0f;
    /**
     * 单帧速度突变阈值（格/秒）：超过即视为位置被强制对齐（瞬移）。
     *
     * 列车加速度有限，正常情况下每帧速度变化只有 a·dt ≈ 0.03 格/秒；而停车时被服务器
     * 强制对齐的位移可能只有 0.2 格，换算成速度却有 10 格/秒。只比绝对值（80）抓不住
     * 这种小位移瞬移，它会被弹簧当成巨大加速度，偏移瞬间顶到上限 —— 就是「停下来抽一下」。
     */
    private static final float JUMP_DV = 8.0f;
    /** 偏移上限（格），防止急刹/碰撞时相机飞出车外。 */
    // 从 0.85 降到 0.45：相机是「+偏移」、车厢是「-偏移」（相对地形），两者之间的
    // 相对位移是偏移的两倍 —— 0.85 时就是 1.7 格，足以让相机穿出车厢壁（用户实测）。
    // 0.45 对应相对位移 0.9 格，仍在车厢半宽（约 1.3 格）之内。
    private static final float MAX_OFFSET = 0.45f;
    /**
     * 速度低通时间常数（秒）。
     *
     * ★ 不能设太大：起步、入弯、出弯产生的加速度都是**短促脉冲**（几百毫秒），
     * 时间常数一大就把脉冲磨平，结果是「只有停车（大幅减速）时才晃一下」，
     * 起步和过弯完全没手感。真正需要重手平滑的是位置那一路（POSITION_TAU），
     * 阶梯跳变已经在那一级滤掉了，这里就该轻一些，让脉冲保留下来。
     */
    private static final float VELOCITY_TAU = 0.10f;
    /** 加速度低通时间常数（秒）：略大于速度那级，抑制残余抖动，但不能吃掉脉冲。 */
    private static final float ACCEL_TAU = 0.15f;
    /**
     * ★ 加速度变化率上限（格/秒³）—— 针对「隔几秒一次的大幅前后晃动」。
     *
     * 用户描述的症状：匀速时每隔几秒突然来一次大幅度前后晃，中间的小幅随机晃动是正常的。
     * 这是**间歇性位置跳变**的典型特征 —— 列车每隔一段时间（轨道段切换 / 服务器位置同步）
     * 车厢位置会有一帧小跳变，而它恰好绕过了现有两道防线：
     *   · 相对卡顿检测要求 dt 异常大 —— 这里 dt 完全正常；
     *   · 传送判据要求 |v| > 250 或 |Δv| > JUMP_DV(8) —— 而跳 0.5 格在 60FPS 下只有
     *     31 格/秒、Δv 约 6，两个门槛都够不着。
     * 于是跳变被当成真实运动，弹簧被猛激励一次；过几秒再来一次，就是「隔几秒一次」。
     *
     * 真实列车的 jerk（加速度变化率）很小：起步/制动全过程也就 1~3 格/秒³，过弯进入时约 5；
     * 而一帧内的位置跳变等效 jerk 上千。限住每帧允许的加速度变化量，尖峰就被削成斜坡，
     * 真实加减速完全不受影响（低通的正常步长 ≈ 2.4 × (1-e^(-0.0167/0.15)) ≈ 0.25 < 0.5）。
     */
    private static final float MAX_JERK = 30.0f;
    /**
     * 平移偏移 → 相机角度的换算系数（度/格）。取 4：
     * 偏移 0.5 格 ≈ 2°，已经能明显感到「头被带了一下」；顶到限幅 0.85 格时约 3.4°，不会晕。
     */
    private static final float PITCH_DEG_PER_METER = 4.0f;
    private static final float ROLL_DEG_PER_METER = 4.0f;
    /**
     * ★ 位置低通时间常数（秒）—— 驱动源的第一道、也是最关键的一道平滑。
     *
     * MTR 的车厢位置不是按渲染帧连续插值的：位置数组按 tick（20Hz）推进，而我们在
     * 渲染帧（60Hz+）采样，于是同一个位置会重复两三帧、然后突然跳一下。实测单帧位移
     * 可达 1.84 格，而按当时车速（17 格/秒）算正常只有 0.34 格。
     * 直接差分：假速度 30~90 格/秒 → 假加速度可达 38 格/秒² → 弹簧偏移被顶到上限卡住，
     * 表现为「刚入弯不抽、入弯越久抽得越明显，而且完全没有摇晃感」。
     * 先对位置做一阶低通把阶梯抹平，真实的加减速（持续数百毫秒以上）几乎不受影响。
     */
    private static final float POSITION_TAU = 0.08f;

    /**
     * ★ 偏移输出低通时间常数（秒）—— 「景物一抽一抽」的修复。
     *
     * 驱动力是车厢位置的二阶差分，对噪声天然敏感；实测惯性偏移的**帧间跳变**可达 0.07 格，
     * 在 60 FPS 下相当于 4 格/秒的抖动速度。相机位置这样抖，视觉上就是
     * 「行驶时旁边景物完全不是匀速后退，而是一抽一抽的」。
     * 输出前再做一阶低通（0.06 秒 ≈ 4 帧延迟），抖动被压掉一个数量级，惯性手感不变。
     */
    private static final float OFFSET_SMOOTH_TAU = 0.06f;

    /**
     * ★ 车体坐标下的前后 / 侧向增益（用户要求：侧向加大、前后减小）。
     *
     * 实测：侧向偏移峰值只有 0.07 格，而前后能到 0.85 格（差十几倍），
     * 用户在有明显小半径弯道（R < 200 格）的线路上仍看不出左右摇晃。原因是两层：
     *   · 侧向加速度本身比加減速小；
     *   · 镜头朝前时，同样的位移量，侧向的观感天然弱于前后（前方景物近、进出视野明显）。
     * 所以这里按用户要求把侧向放大、前后缩小，让两个方向的观感匹配。
     * 代价：物理上不再严格等比，属于观感取舍。
     */
    // ★ 与刚度 70 配套的一组幅度系数（用户确定不再改刚度，所以上一轮为「刚度 15」做的
    //   4.7 倍补偿全部撤销，否则幅度会只有原来的 1/6）。
    //   横向 6.0 → 8.0、纵向 5.0 → 2.5：横向/纵向比从 1.2 倍拉到 **3.2 倍** ——
    //   用户反馈「横向相对纵向太弱」。这一组同时满足「横向调高」和「整体幅度缩减」
    //   （横向 +33%、纵向 -50%）。
    // 依据：镜头对前后方向比左右敏感（前方景物近、进出视野明显），所以横向必须给得更大；
    // 而真实转弯向心加速度（2.4~5）本来也远大于纵向加减速（1~1.5）。
    private static final float LONGITUDINAL_GAIN = 2.5f;
    private static final float LATERAL_GAIN = 8.0f;

    /**
     * ★ 原始（车体坐标）加速度上限，单位 格/秒²。**乘增益之前**先限幅。
     *
     * 真实列车加速度：起步约 1、常用制动约 1.5、小半径弯道向心约 2.4~5，
     * 极端的 100 米半径 + 30 格/秒 也就 9 —— 所以 8 已经是很宽松的上界。
     * 而卡顿造成的假加速度实测能到 34.8（真实值的 30 倍）。
     * 限幅放在增益**之前**，侧向（增益 6）和纵向（增益 5）才不会被假信号带飞。
     */
    private static final float RAW_ACC_LIMIT = 8.0f;
    /**
     * ★ 相对卡顿判据：单帧 dt 超过「近期平均帧时间」的多少倍就视为帧率抖动。
     *
     * 绝对值阈值（MAX_DT=0.1s）只能抓严重卡顿，抓不住最常见的「帧时间在 0.02~0.06 之间跳」——
     * 而后者正是「卡顿造成的晃动」的来源：Δ位置/dt 的 dt 一旦偏大，假加速度就爆表。
     * 用相对判据后，帧率整体低（但稳定）不会误判，只有**突然变慢的那一帧**会被丢掉。
     */
    private static final float CATCHUP_DT_RATIO = 2.0f;
    /** 平均帧时间的滑动平均系数（越小越慢，约 1/alpha 帧后跟上）。 */
    private static final float AVG_DT_ALPHA = 0.08f;

    /**
     * ★ 轨道不平顺：直线行驶时的随机微晃（**前后 / 左右各一路**，两个方向系数相同）。
     *
     * 真实铁路即使直线段也有方向不平顺、道床沉降、轮轨游间，车厢会持续轻微摇摆；
     * 而纯物理的弹簧惯性只在「有加速度」时才动，直线匀速段完全静止，看着不像真的。
     * 所以额外叠一个**低频随机**驱动，前后与左右各一条独立序列（共用同一套系数，
     * 但随机相位不同 —— 共用一条序列会变成斜向直线晃，很假）。
     *
     * 幅度包络（用户要求：80 以下是渐强，80 以上**指数级**削弱）：
     *   · 0 ~ 80 km/h：线性增长（停着时为 0，不会出现「车没动却一直晃」）；
     *   · 80 km/h 以上：exp(-(v-80)/40)，即 120 时剩 37%、160 时剩 14%、200 时剩 5%。
     * ⚠ 这里用 **km/h** 而不是格/秒 —— 用户是按游戏里显示的速度说「80」的
     *   （80 km/h ≈ 22.2 格/秒）。
     *
     * ⚠ 噪声不是白噪声：白噪声每帧都换值，看起来是高频抖动而不是「摇摆」。
     *   这里每隔 0.35~0.85 秒才换一个目标值，再用一阶低通（RAIL_NOISE_TAU）平滑过去，
     *   得到的是缓慢起伏的随机位移，接近真实的车体摆动。
     */
    // 撤销上一轮为「刚度 15」做的补偿，回到与刚度 70 配套的值。
    private static final float RAIL_NOISE_ACC = 5.0f;
    /**
     * ★ 前后向随机晃动的**独立**系数（明显小于左右）。
     *
     * 匀速时纵向本来没有任何真实加速度，唯一的纵向驱动就是这个噪声 ——
     * 所以它稍大一点，观感就是「匀速行驶时莫名其妙的前后晃」（用户实测反馈）。
     * 而且镜头对前后方向比左右敏感得多（前方景物近、进出视野明显），
     * 同样的位移量，前后看起来要强好几倍。
     * 5.0 → 1.0：前后只用左右的 1/5。
     * （曾一度压到 0.3 去追「匀速时前后有不明晃动」，但那个症状的真因是**间歇性位置跳变**，
     *   已由 MAX_JERK 解决；用户也确认「中间的小幅随机晃动是正常的」，所以这里调回 1.0。）
     */
    private static final float RAIL_NOISE_ACC_F = 1.0f;
    /** 幅度峰值对应的车速（km/h），见上面包络说明。 */
    private static final float RAIL_NOISE_PEAK_KMH = 80.0f;
    /** 80 km/h 以上的指数衰减尺度（km/h）：越大衰减越慢。 */
    private static final float RAIL_NOISE_DECAY_KMH = 40.0f;
    private static final float RAIL_NOISE_TAU = 0.18f;
    private static final float RAIL_NOISE_HOLD_MIN = 0.35f;
    private static final float RAIL_NOISE_HOLD_RANGE = 0.50f;

    /**
     * 诊断输出：**默认开启**，每 40 帧输出一行区间峰值。
     * 不想要时加启动参数 -Dante.inertiaDebug=false 关闭。
     *
     * 输出格式（全部投影到车体坐标系，「前 / 右 / 上」）：
     *   偏移Peak          本区间惯性偏移的最大绝对值 —— 就是画面被推开的幅度
     *   帧间跳变Peak      ★「抽动」的直接度量。正常 < 0.02 格/帧；出现 0.3+ 就是明显的抽
     *   车厢位移Peak      驱动源（车厢位置）的单帧位移峰值。30 格/秒的列车在 60 帧下应约 0.5 格
     *   原始vPeak         未平滑速度峰值 —— 若远超正常车速，说明位置在跳变
     *   平滑aPeak         平滑后加速度峰值 —— 弹簧的驱动力
     *   rebase(首次/卡顿/传送)  三类基准重置各触发了几次。启动/停止时若「传送」次数飙升，
     *                     说明 MTR 的位置突变被当成了传送，惯性会被反复清零 —— 那就是抽动源
     *   dt=[min,max]      本区间帧时间范围（秒）。最大值远大于最小值说明帧率在抖
     */
    private static final boolean DEBUG = System.getProperty("ante.inertiaDebug", "false").equalsIgnoreCase("true");

    // ── 抽动诊断：区间峰值保持 ─────────────────────────────────────────
    // 抽动是瞬态的（只在启动/停止的一两帧出现），固定每 20 帧采样一次必然错过那一帧。
    // 所以对每个可疑量做「本区间内的绝对值峰值」，每 40 帧输出一次并清零。
    // 偏移/步长都分解到**车体坐标系**（前 / 右 / 上），这样能直接回答「前后向抽动有多大」。
    private static float pkOffF, pkOffR, pkOffU;      // 惯性偏移峰值（格）
    private static float pkOffDelta;                  // 偏移的帧间变化峰值（格/帧）——「抽」的直接度量
    private static float pkStepF, pkStepR, pkStepU;   // 车厢单帧位移峰值（格）
    private static float pkRawV, pkAccel;             // 原始速度峰值（格/秒）、平滑加速度峰值（格/秒²）
    private static float dtMin = 1.0f, dtMax = 0.0f;  // 本区间帧时间范围（秒）
    private static int rbFirst, rbDt, rbTeleport;     // 各类 rebase 触发次数
    private static int diagFrames;

    private static final int RB_FIRST = 0, RB_DT = 1, RB_TELEPORT = 2;

    private static float offPrevF, offPrevR, offPrevU;

    /**
     * ★ 惯性偏移的状态量，保存在**世界坐标系**。
     *
     * 曾一度改成车体坐标系（以为「跟着车晃」是要让偏移随车体转），那是**改反了**：
     * 用户要的恰恰是「摇晃不要跟着车厢」。车体坐标系下，车体一转偏移方向就跟着转，
     * 观感就是晃动和车厢焊在一起。世界坐标系下偏移方向固定，才是独立的弹簧运动。
     *
     * 用户给的判据：「只有列车停下来的时候摇晃才不跟着列车」——
     * 停车时车体姿态稳定（无滚转、无加速度），跟着车转的量自然消失；车一动它就出现。
     * 那说明跟着车的是**滚转**（车体超高的正常效果），而不是惯性偏移。
     */
    /**
     * ★ 惯性偏移的状态量，保存在**车体坐标系**（前 / 侧 / 上）。
     *
     * 必须用车体坐标：偏移存世界坐标时，列车一转弯车体转了、偏移方向却不跟着转，
     * 原本的「侧向偏移」在车体看来就变成了「前后偏移」—— 用户实测描述正是
     * 「过弯的时候前后晃动很明显，怀疑把左右晃动数值混到前后了」。
     * 存车体坐标后，过弯时的偏移方向相对车厢固定在侧向，不会漂到前后去。
     *
     * 施加时再转回世界坐标（相机位置是世界坐标），见 worldOffX/Y/Z 与 getSmoothOffX/Y/Z。
     */
    private static float offF, offR, offU;
    private static float velF, velR, velU;
    /** 车体坐标偏移换算到世界坐标的结果（每帧算一次），供 CameraMixin 施加到相机位置。 */
    private static float worldOffX, worldOffY, worldOffZ;
    /** 输出用的平滑偏移（世界坐标，见 OFFSET_SMOOTH_TAU）。 */
    private static float smoothOffX, smoothOffY, smoothOffZ;
    private static float lastCX, lastCY, lastCZ;
    private static float lastVX, lastVY, lastVZ;
    /** 低通后的速度与加速度。 */
    private static float smoothVX, smoothVY, smoothVZ;
    private static float smoothAX, smoothAY, smoothAZ;
    /** ★ 由惯性偏移换算出的相机角度偏移（度），供 GameRendererMixin 乘到渲染视图上。 */
    private static float offPitchDeg, offRollDeg;
    /** ★ 低通后的车厢位置：所有差分都以它为基准（见 POSITION_TAU 注释）。 */
    private static float smoothCX, smoothCY, smoothCZ;
    private static boolean hasLast = false;
    private static long lastNanos = 0L;

    /** 轨道不平顺噪声（见 RAIL_NOISE_ACC）：前后 / 左右各一条独立序列，各自记目标值与换目标时间。 */
    private static final java.util.Random RAIL_RAND = new java.util.Random();
    private static float railNoiseTargetF = 0.0f, railNoiseF = 0.0f;
    private static float railNoiseTargetR = 0.0f, railNoiseR = 0.0f;
    private static long railNoiseNextNanosF = 0L, railNoiseNextNanosR = 0L;
    /** 近期平均帧时间（秒），供相对卡顿判据使用（见 CATCHUP_DT_RATIO）。 */
    private static float avgDt = 1.0f / 60.0f;

    /** 下车/切世界/关闭功能时清空，避免残留偏移。 */
    public static void reset() {
        hasLast = false;
        offF = offR = offU = 0.0f;
        velF = velR = velU = 0.0f;
        worldOffX = worldOffY = worldOffZ = 0.0f;
        smoothOffX = smoothOffY = smoothOffZ = 0.0f;
        offPitchDeg = offRollDeg = 0.0f;
        lastVX = lastVY = lastVZ = 0.0f;
        smoothVX = smoothVY = smoothVZ = 0.0f;
        smoothAX = smoothAY = smoothAZ = 0.0f;
        smoothCX = smoothCY = smoothCZ = 0.0f;
        railNoiseF = railNoiseTargetF = 0.0f;
        railNoiseR = railNoiseTargetR = 0.0f;
        railNoiseNextNanosF = railNoiseNextNanosR = 0L;
        avgDt = 1.0f / 60.0f;
    }

    /** 当前惯性偏移量（格），供调试/显示用。 */
    public static float currentOffset() {
        return (float) Math.sqrt(worldOffX * worldOffX + worldOffY * worldOffY + worldOffZ * worldOffZ);
    }

    /** 平滑后的惯性偏移（世界坐标），由 CameraMixin 加到 Camera.getPosition() 的返回值上。 */
    public static float getSmoothOffX() { return smoothOffX; }
    public static float getSmoothOffY() { return smoothOffY; }
    public static float getSmoothOffZ() { return smoothOffZ; }

    /** 惯性造成的相机俯仰偏移（度）：加速时头往后仰、刹车时往前冲。 */
    public static float getOffsetPitchDeg() {
        return offPitchDeg;
    }

    /** 惯性造成的相机横滚偏移（度）：过弯时头往弯外歪。 */
    public static float getOffsetRollDeg() {
        return offRollDeg;
    }

    /** 只更新基准、不积分（首次进入 / 卡顿时 / 传送时），并记录触发原因。 */
    private static void rebase(float cx, float cy, float cz, int cause) {
        if (cause == RB_DT) rbDt++;
        else if (cause == RB_TELEPORT) rbTeleport++;
        else rbFirst++;
        lastCX = cx; lastCY = cy; lastCZ = cz;
        smoothCX = cx; smoothCY = cy; smoothCZ = cz;
        lastVX = lastVY = lastVZ = 0.0f;
        smoothVX = smoothVY = smoothVZ = 0.0f;
        smoothAX = smoothAY = smoothAZ = 0.0f;
    }

    /**
     * 就地修改传入的相机位置：加上当前惯性偏移（世界坐标系）。
     *
     * @param cameraPos 相机最终位置（世界坐标，已应用滚转；会被原地修改）
     */
    public static void apply(Vector3f cameraPos) {
        if (!ClientConfig.enableCameraInertia || !Rolling.isRiding()) {
            reset();
            return;
        }

        // 关键：以车厢世界位置为驱动源，而不是相机位置
        final Vector3f carPos = Rolling.getRidingCarPos();
        final float cx = carPos.x();
        final float cy = carPos.y();
        final float cz = carPos.z();

        final long now = System.nanoTime();
        float dt = hasLast ? (now - lastNanos) / 1.0e9f : 0.0f;
        // ★ 同一帧内的重复调用：不推进时间基准、不积分（见 MIN_DT 注释）。
        if (hasLast && dt < MIN_DT) return;
        lastNanos = now;

        if (DEBUG && dt > 0.0f) {
            if (dt < dtMin) dtMin = dt;
            if (dt > dtMax) dtMax = dt;
        }

        if (!hasLast || dt <= 0.0f) {
            rebase(cx, cy, cz, RB_FIRST);
            hasLast = true;
            return;
        }
        // ── 卡顿判据（★ 用户要求「避免因为卡顿造成的晃动」）─────────────────
        // 两条一起用：
        //   · 绝对：dt > MAX_DT（0.1s）—— 严重掉帧；
        //   · 相对：dt > 近期平均帧时间的 CATCHUP_DT_RATIO 倍 —— 帧率抖动。
        // 后者才是常见的假晃动来源：实测车速只有 6.5 格/秒时，「平滑加速度」峰值到过 34.8，
        // 是真实值的 30 倍 —— 全是 Δ位置/dt 里 dt 突然变大造成的。
        final boolean stutter = dt > MAX_DT || dt > avgDt * CATCHUP_DT_RATIO;
        // 平均帧时间：把异常帧的 dt **夹到 2 倍平均值**再参与平均。
        //   不夹 → 单帧尖峰污染平均；完全不参与 → 帧率整体下降时 avgDt 永远追不上，
        //   会变成「每帧都判为抖动」→ 惯性彻底失效。夹住后它能在十来帧内跟上真实的帧率变化。
        avgDt += (Math.min(dt, avgDt * CATCHUP_DT_RATIO) - avgDt) * AVG_DT_ALPHA;

        if (stutter) {
            // 这一帧的位置变化跨越太久或明显异常，不能当作连续运动处理，只重置基准。
            rebase(cx, cy, cz, RB_DT);
            // ★ 只清速度，**不动已有偏移**。
            //   早先这里还乘了一个 exp(-dt/0.12) 的衰减，本意是让卡顿后更快回正，
            //   但 dt=0.1 时一帧就衰减 56% —— 观感就是「每卡一次相机位置被拽回去一下」
            //   （用户实测反馈）。清掉速度已经足够：没有速度，弹簧就是单调回正、不会来回振荡，
            //   而回正本身由后续正常帧平滑完成，没有突变。
            velF = velR = velU = 0.0f;
            return;
        }

        // ── 0) 位置低通：驱动源要先平滑、再差分（见 POSITION_TAU 注释）。
        // MTR 的位置按 tick 推进、我们按渲染帧采样 → 位置呈阶梯状，直接差分得到的
        // 假速度/假加速度会把弹簧顶到限幅，弯道里就表现为持续抽搐且不摇晃。
        final float aPos = 1.0f - (float) Math.exp(-dt / POSITION_TAU);
        smoothCX += aPos * (cx - smoothCX);
        smoothCY += aPos * (cy - smoothCY);
        smoothCZ += aPos * (cz - smoothCZ);

        // ── 1) 原始速度（单帧差分，基于低通后的位置）
        final float rawVx = (smoothCX - lastCX) / dt;
        final float rawVy = (smoothCY - lastCY) / dt;
        final float rawVz = (smoothCZ - lastCZ) / dt;
        lastCX = smoothCX; lastCY = smoothCY; lastCZ = smoothCZ;

        // 诊断：把「车厢的单帧位移」分解到车体坐标系，直接回答「前后向跳变有多大」。
        // 抽动若来自驱动源的逐帧噪声，这里会看到前后向位移峰值明显大于正常值
        // （30 格/秒的列车在 60 帧下正常只有约 0.5 格/帧）。
        if (DEBUG) {
            final float[] fwd = carAxes();
            final float stepF = fwd[0] * rawVx * dt + fwd[2] * rawVz * dt;
            final float stepR = fwd[1] * rawVx * dt + fwd[3] * rawVz * dt;
            final float stepU = rawVy * dt;
            if (Math.abs(stepF) > pkStepF) pkStepF = Math.abs(stepF);
            if (Math.abs(stepR) > pkStepR) pkStepR = Math.abs(stepR);
            if (Math.abs(stepU) > pkStepU) pkStepU = Math.abs(stepU);
            final float rawV = (float) Math.sqrt(rawVx * rawVx + rawVy * rawVy + rawVz * rawVz);
            if (rawV > pkRawV) pkRawV = rawV;
        }

        // 传送 / 到站强制对齐（含停车时被服务器强制对齐造成的瞬移）：
        //   · 判据一：速度绝对值超过物理上限 —— 长距离传送
        //   · 判据二：**单帧速度突变** —— 补上判据一抓不住的小位移瞬移（见 JUMP_DV 注释）
        final float rawVmag = (float) Math.sqrt(rawVx * rawVx + rawVy * rawVy + rawVz * rawVz);
        final float dvx = rawVx - smoothVX, dvy = rawVy - smoothVY, dvz = rawVz - smoothVZ;
        final float dvMag = (float) Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
        if (rawVmag > TELEPORT_SPEED || dvMag > JUMP_DV) {
            // 注意：这里**只对齐位置基准**，故意保留原来的平滑速度。
            // 若照 rebase() 那样把速度清零，下一帧 dvMag 又会等于正常车速而再次触发，
            // 于是每帧都 rebase，惯性彻底失效。瞬移不改变列车的真实速度。
            smoothCX = cx; smoothCY = cy; smoothCZ = cz;
            lastCX = smoothCX; lastCY = smoothCY; lastCZ = smoothCZ;
            // ★ 但**平滑速度必须对齐到当前 rawV**，不能原样保留。
            //   保留旧值时 dvMag 恒等于 |rawV - smoothV|；一旦它超过 JUMP_DV，
            //   之后每一帧都仍然超过 —— 实测一段 40 次积分的窗口里「传送」触发了 393 次，
            //   也就是说绝大多数调用都停在这个分支，惯性只剩零星几次生效、偏移被噪声猛甩。
            //   对齐之后最多连触发两帧就恢复（瞬移不改变列车真实速度，所以不会产生惯性）。
            smoothVX = rawVx; smoothVY = rawVy; smoothVZ = rawVz;
            smoothAX = smoothAY = smoothAZ = 0.0f;
            rbTeleport++;
            return;
        }

        // ── 2) 速度低通（滤掉车厢位置的逐帧抖动）
        final float aVel = 1.0f - (float) Math.exp(-dt / VELOCITY_TAU);
        smoothVX += aVel * (rawVx - smoothVX);
        smoothVY += aVel * (rawVy - smoothVY);
        smoothVZ += aVel * (rawVz - smoothVZ);

        // ── 3) 加速度：由**平滑后**的速度差分得到，并再做一次低通
        final float rawAx = (smoothVX - lastVX) / dt;
        final float rawAy = (smoothVY - lastVY) / dt;
        final float rawAz = (smoothVZ - lastVZ) / dt;
        lastVX = smoothVX; lastVY = smoothVY; lastVZ = smoothVZ;

        final float aAcc = 1.0f - (float) Math.exp(-dt / ACCEL_TAU);
        // ★ 低通之后再限一次**变化率**（见 MAX_JERK）：位置跳变造成的假加速度是「单帧尖峰」，
        //   低通只把它按比例缩小、仍然会留下一次明显冲击；限住每帧允许的变化量，
        //   尖峰被摊成斜坡，弹簧就不会被猛激励。
        final float jerkStep = MAX_JERK * dt;
        smoothAX += clamp(aAcc * (rawAx - smoothAX), jerkStep);
        smoothAY += clamp(aAcc * (rawAy - smoothAY), jerkStep);
        smoothAZ += clamp(aAcc * (rawAz - smoothAZ), jerkStep);

        // 可调参数（界面滑块），带合法性保护
        final float strength  = Math.max(0.0f, ClientConfig.cameraInertiaStrength);
        final float stiffness = Math.max(1.0f, ClientConfig.cameraInertiaStiffness);
        final float damping   = Math.min(0.98f, Math.max(0.01f, ClientConfig.cameraInertiaDamping));

        // ── 4) 弹簧-阻尼积分（**车体坐标系**：前 / 侧 / 上）
        // 驱动加速度先投影到车体坐标，偏移也累积在车体坐标 —— 这样过弯时偏移方向
        // 相对车厢固定在侧向，不会像世界坐标那样在车体转 90° 后漂成「前后」。
        final float[] ax = carAxes();
        // ★ 先在**车体坐标下**对原始加速度限幅，然后才乘增益。
        //   放到增益之后限幅是不行的：纵向增益是 5.0，卡顿的假加速度（实测 34.8）
        //   乘完就是 174，等于没限。（见 RAW_ACC_LIMIT 注释）
        final float rawF = clamp(ax[0] * smoothAX + ax[2] * smoothAZ, RAW_ACC_LIMIT);
        final float rawR = clamp(ax[1] * smoothAX + ax[3] * smoothAZ, RAW_ACC_LIMIT);
        // 前后提高、侧向放大（见 LONGITUDINAL_GAIN / LATERAL_GAIN 的说明）。
        final float accF = rawF * LONGITUDINAL_GAIN;
        final float accR = rawR * LATERAL_GAIN;
        final float accU = clamp(smoothAY, RAW_ACC_LIMIT);

        // ★ 轨道不平顺：前后 / 左右各一条独立随机序列，共用同一套幅度系数。
        //   两条序列的换值时刻与随机数都独立 —— 共用一条会变成斜向直线晃，很假。
        final float speedNow = (float) Math.sqrt(smoothVX * smoothVX + smoothVY * smoothVY + smoothVZ * smoothVZ);
        final long noiseNow = System.nanoTime();
        if (noiseNow >= railNoiseNextNanosR) {
            railNoiseTargetR = RAIL_RAND.nextFloat() * 2.0f - 1.0f;
            railNoiseNextNanosR = noiseNow + (long) ((RAIL_NOISE_HOLD_MIN + RAIL_RAND.nextFloat() * RAIL_NOISE_HOLD_RANGE) * 1.0e9f);
        }
        if (noiseNow >= railNoiseNextNanosF) {
            railNoiseTargetF = RAIL_RAND.nextFloat() * 2.0f - 1.0f;
            railNoiseNextNanosF = noiseNow + (long) ((RAIL_NOISE_HOLD_MIN + RAIL_RAND.nextFloat() * RAIL_NOISE_HOLD_RANGE) * 1.0e9f);
        }
        final float aNoise = 1.0f - (float) Math.exp(-dt / RAIL_NOISE_TAU);
        railNoiseR += aNoise * (railNoiseTargetR - railNoiseR);
        railNoiseF += aNoise * (railNoiseTargetF - railNoiseF);
        final float speedKmh = speedNow * 3.6f;
        // 幅度包络（0~80 渐强、80 以上指数削弱）两个方向共用；系数分开：前后明显更小。
        // ⚠ 速度单位是 km/h —— 用户是按游戏里显示的速度说的（详见 RAIL_NOISE_ACC 注释）。
        final float railEnv = speedKmh <= RAIL_NOISE_PEAK_KMH
                ? speedKmh / RAIL_NOISE_PEAK_KMH
                : (float) Math.exp(-(speedKmh - RAIL_NOISE_PEAK_KMH) / RAIL_NOISE_DECAY_KMH);
        final float accFTotal = accF + railNoiseF * (RAIL_NOISE_ACC_F * railEnv);
        final float accRTotal = accR + railNoiseR * (RAIL_NOISE_ACC * railEnv);

        // ★ 子步积分（见 MAX_SUB_DT 注释）：一帧的 dt 若大于稳定边界就直接积分会发散，
        //   切成若干 ≤ 8ms 的小步依次推进，等价于把同一段物理时间算得更细。
        final int substeps = Math.min(MAX_SUBSTEPS, Math.max(1, (int) Math.ceil(dt / MAX_SUB_DT)));
        final float sub = dt / (float) substeps;
        final float springSub = stiffness * sub;
        final float decaySub = (float) Math.pow(damping, sub);
        for (int i = 0; i < substeps; i++) {
            velF = (velF - accFTotal * strength * sub - offF * springSub) * decaySub;
            velR = (velR - accRTotal * strength * sub - offR * springSub) * decaySub;
            velU = (velU - accU * strength * sub - offU * springSub) * decaySub;

            offF += velF * sub;
            offR += velR * sub;
            offU += velU * sub;
        }

        // 限幅
        if (offF >  MAX_OFFSET) { offF =  MAX_OFFSET; velF = 0.0f; }
        if (offF < -MAX_OFFSET) { offF = -MAX_OFFSET; velF = 0.0f; }
        if (offR >  MAX_OFFSET) { offR =  MAX_OFFSET; velR = 0.0f; }
        if (offR < -MAX_OFFSET) { offR = -MAX_OFFSET; velR = 0.0f; }
        if (offU >  MAX_OFFSET) { offU =  MAX_OFFSET; velU = 0.0f; }
        if (offU < -MAX_OFFSET) { offU = -MAX_OFFSET; velU = 0.0f; }

        // 收敛判定：速度与偏移都极小则归零，避免长期残留微小抖动
        if (Math.abs(offF) < 1.0e-4f && Math.abs(velF) < 1.0e-3f) { offF = 0.0f; velF = 0.0f; }
        if (Math.abs(offR) < 1.0e-4f && Math.abs(velR) < 1.0e-3f) { offR = 0.0f; velR = 0.0f; }
        if (Math.abs(offU) < 1.0e-4f && Math.abs(velU) < 1.0e-3f) { offU = 0.0f; velU = 0.0f; }

        // ★ 车体坐标 → 世界坐标（相机位置是世界坐标），再做输出低通。
        worldOffX = ax[0] * offF + ax[1] * offR;
        worldOffY = offU;
        worldOffZ = ax[2] * offF + ax[3] * offR;

        final float aOut = 1.0f - (float) Math.exp(-dt / OFFSET_SMOOTH_TAU);
        smoothOffX += aOut * (worldOffX - smoothOffX);
        smoothOffY += aOut * (worldOffY - smoothOffY);
        smoothOffZ += aOut * (worldOffZ - smoothOffZ);

        // ★ 偏移**不在这里加到相机位置上**（这里只负责积分与状态维护）。
        // 施加点已改到 CameraMixin 对 Camera.getPosition() 的返回值注入 ——
        // 因为任何 setPosition 路径都可能被后续调用覆盖，而 getPosition 的返回值无法被覆盖。
        // 曾经的写法就是在这里 cameraPos.add(...)，结果是「惯性从来没生效过」。

        // ★ 把平移偏移换算成相机的俯仰 / 横滚微转。
        // 注意：offF / offR / offU 现在**本身就是车体坐标**（积分即在车体坐标里做），
        // 所以这里不需要再投影一次；ax 也已在上面的积分段算好并复用于输出。
        offPitchDeg = offF * PITCH_DEG_PER_METER;
        offRollDeg = offR * ROLL_DEG_PER_METER;

        if (DEBUG) {
            if (Math.abs(offF) > pkOffF) pkOffF = Math.abs(offF);
            if (Math.abs(offR) > pkOffR) pkOffR = Math.abs(offR);
            if (Math.abs(offU) > pkOffU) pkOffU = Math.abs(offU);

            final float dF = offF - offPrevF, dR = offR - offPrevR, dU = offU - offPrevU;
            final float dOff = (float) Math.sqrt(dF * dF + dR * dR + dU * dU);
            if (dOff > pkOffDelta) pkOffDelta = dOff;
            offPrevF = offF; offPrevR = offR; offPrevU = offU;

            final float acc = (float) Math.sqrt(smoothAX * smoothAX + smoothAY * smoothAY + smoothAZ * smoothAZ);
            if (acc > pkAccel) pkAccel = acc;

            if (++diagFrames >= 40) {
                final float speed = (float) Math.sqrt(smoothVX * smoothVX + smoothVY * smoothVY + smoothVZ * smoothVZ);
                cn.zbx1425.mtrsteamloco.Main.LOGGER.info(String.format(
                        "[ANTE-Inertia] 40帧: 偏移Peak(前%.3f 右%.3f 上%.3f) 帧间跳变Peak=%.3f | 车厢位移Peak(前%.4f 右%.4f 上%.4f) | 原始vPeak=%.1f 平滑aPeak=%.1f 车速=%.1f | rebase(首次%d 卡顿%d 传送%d) dt=[%.4f,%.4f]",
                        pkOffF, pkOffR, pkOffU, pkOffDelta,
                        pkStepF, pkStepR, pkStepU,
                        pkRawV, pkAccel, speed,
                        rbFirst, rbDt, rbTeleport, dtMin, dtMax));

                pkOffF = pkOffR = pkOffU = pkOffDelta = 0.0f;
                pkStepF = pkStepR = pkStepU = 0.0f;
                pkRawV = pkAccel = 0.0f;
                dtMin = 1.0f; dtMax = 0.0f;
                rbFirst = rbDt = rbTeleport = 0;
                diagFrames = 0;
            }
        }
    }

    /**
     * 车体水平朝向的两组单位向量，打包返回：
     *   [0] = 前向.x，[1] = 侧向.x，[2] = 前向.z，[3] = 侧向.z
     *
     * ★ MTR 的 yaw 是**弧度**（Train.calculateCar 里 `yaw = (float) Mth.atan2(dx, dz)`），
     *   这里不能再 toRadians —— 早先多转了一次，前/侧两个分量就错位了：
     *   车速 22 格/秒时「前向位移」只显示 0.008 格，而把前进方向算进了侧向（0.30 格）。
     * ★ 前向向量是 (sin yaw, 0, cos yaw)：atan2(dx, dz) 的定义决定了 sin/cos 与 dx/dz 成正比，
     *   所以不能加负号（早先写成 -sin 也反了）。
     * 相机偏移与车厢位移都投影到这两个方向，日志里的「前 / 侧」才是真实方向。
     */
    /** 把值限制在 ±limit 内（用于加速度限幅，见 RAW_ACC_LIMIT）。 */
    private static float clamp(float value, float limit) {
        if (value > limit) return limit;
        if (value < -limit) return -limit;
        return value;
    }

    private static float[] carAxes() {
        final double yawRad = Rolling.getRidingCarYaw();
        final float sinY = (float) Math.sin(yawRad);
        final float cosY = (float) Math.cos(yawRad);
        return new float[] {sinY, cosY, cosY, -sinY};
    }
}
