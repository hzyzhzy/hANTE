# hANTE

<p align="left">
  <img src="docs/hante-icon.png" alt="hANTE" width="320">
</p>

<p align="left"><a href="#english">English</a> | 中文</p>

hANTE 是
[Aphrodite's Nemo's Transit Expansion (MTR-ANTE)](https://github.com/aphrodite281/mtr-ante)
的独立分支，面向 **Minecraft 1.20.1 / Forge / Fabric** 和 **MTR 3**（包括 YMTR）。
本分支侧重兼容性、驾驶/相机手感、性能，以及挑选上游修复。

本仓库**不是**准备合并回上游的 PR 分支，而是一条独立维护的线路。
上游历史完整保留，便于随时 cherry-pick 需要的修复。

## 与上游的差异

### 跨机场飞机路径修复

ANTE（以及 hANTE 1.1.2 的早期构建）在两个机场之间生成路径时可能报
`gui.mtr.path_not_found_between`。原因是 `BetterPathFinder.addPathPart` 缺少原版
MTR/YMTR `PathFinder` 的“跑道起飞”分支：当列车位于 `RailType.RUNWAY` 且该点只有一个
连接时，原版会把所有跑道端点加入候选；ANTE 改写后没有这一步，飞机无法离开跑道端点
进入空中段。

hANTE 恢复了该分支，并补上：

- `PathPart` 的 `airborne` 标记，避免在空中重复触发跑道逻辑；
- 跳过当前跑道端点和已访问位置，防止自环；
- `addAirplanePath` 使用 8 方向转向步数（不再依赖 `RailAngle` 枚举身份比较）；
- 对 `lastConnections`、`newConnections`、`calculateNewDirection` 的空值保护。

主路径现在可以正常生成。如果整个车厂仍然失败，通常是**地图拓扑**问题，例如：

- 单向跑道只支持一个方向，回程没有可用的起飞/降落跑道；
- 车厂范围内存在完全孤立的侧线，导致车厂取所有侧线的最小成功段数。

### 增强型手动驾驶（移植自 NeoMTR 内置 NTE）

hANTE 把 [NeoMTR](https://github.com/zbx1425/NeoMTR) 内置 NTE 的
**增强型手动驾驶**移植到了 Minecraft 1.20.1 / Forge / Fabric / MTR 3（YMTR 为 Forge 分支）。

功能：

- `/mtrnte virtdrive` 接管当前乘坐的列车；
- 原版 `drive_hmi.png` 驾驶界面：圆形速度表、ATP 黄/红针、目标速度与目标距离条；
- 牵引/制动级位、车门联锁、屏蔽门对标信息、ATP 超速紧急制动；
- 虚拟列车会隐藏原车，避免两辆车头重叠；
- 退出时清理 MTR 的传送/座位状态，避免浮空、无法跳跃/潜行、`/kill` 无效或被传回列车；
- 客户端和服务端使用同一个 jar 时支持多人。

教程：

1. **客户端和服务端安装同一个 hANTE jar**，然后重启；
2. 右键坐进一辆**自动运行**的列车；
3. 输入：
   ```
   /mtrnte virtdrive
   ```
4. 默认键位（MTR 3.6.3）：
   - `↑` / `W`：加速
   - `↓` / `S`：制动
   - `←`：开关车门
   - `X`：回零/惰行
   - 按住潜行约 1.5 秒：退出虚拟驾驶
5. 其他指令：
   - `/mtrnte virtdrive atpcutout`：切除/恢复 ATP（默认开启）；
   - `/mtrnte virtdrive reset`：退出后强制清理卡住的传送/座位状态。
6. 停站接管时给牵引会自动关门，等门动画结束即可起步。

ATP 默认开启；超过 ATP 红针会触发紧急制动。需要完全手动控制时可执行
`atpcutout`。

### 相机与视角

- **相机惯性**：`CameraInertia` + `CameraAccessor` 把惯性偏移烘焙进相机位置；
- **视角滚转**：`Rolling` 支持相机和乘坐位置跟随列车滚转，相机滚转可单独关闭；
- **车厢稳定**：`VehicleRidingClientMixin` 改用不含惯性偏移的相机位置，修复
  “相机在车厢里晃、车厢在画面上被推走”的问题；
- **滚转不再压低相机**（1.1.2）：`Rolling.applyRolling` 恢复滚转造成的 Y 位移，只保留
  水平面内的位移；
- **惯性不再被误清**（1.1.2）：“滚转归零”和“判定下车”使用两个独立超时；
- **过弯采用离心力手感**（1.1.2）：侧向运动使用临界阻尼、无超调；侧向增益从 8 提高到 26。

### YMTR 兼容

- `YmtrAnteFallbackMixin` 关闭 YMTR 的 ANTE fallback 轨道渲染路径。该路径每帧对每根
  铁轨调用两次 `Rail.getPosition`，实测会把帧率压到原来的约 1/5。

### 实验性内存优化（默认关闭）

大追加包环境下模型内存主要来自「所有模型在资源加载时一次性解析并上传」。hANTE 提供了三组
独立开关，把上传推迟到模型第一次真正渲染时：

| 开关 | 作用 |
| --- | --- |
| 实验性：懒加载列车模型 | 列车第一次渲染时才加载 OBJ/BBMODEL |
| 实验性：懒加载 EyeCandy 模型 | 装饰物第一次渲染时才加载模型 |
| 实验性：懒加载轨道模型 | 自定义轨道第一次渲染时才解析/上传模型 |

三者都**默认关闭**，保持与原版一致的行为。开启后会在对应模型首次出现时有一次加载延迟，
之后正常；未出现在视野内的追加包内容完全不占模型内存。

> 这些开关只影响内存占用，不会卸载已经加载的模型，因此不会出现模型消失或透明的问题。

### Bug 修复

| 问题 | 修复 |
| --- | --- |
| 跨机场飞机路径无法创建 | 恢复 `addPathPart` 的跑道起飞分支（见上文） |
| 缆车在某些角度无法连接 | `RailCalculator.calculate` 不再用 `Section.isValid()` 过滤结果；任一参数为 NaN 时旧逻辑会丢弃整组结果 |

## 版本

当前版本：**1.1.6**（预发布），基线为上游 `alpha` 的 `0506691f`。

### 1.1.6 主要变更

- 新增 **【实验性】修复远坐标抖动**（默认开启）：hANTE 自己的几何体（轨道、装饰物件、
  脚本列车、节点方块、距离标签）改为「以轨道区块为基准烘焙 + 相机相对矩阵」，
  世界坐标只保留在 double 侧。修复远离出生点后的轨道抖动，以及超过约 1000 万格后
  轨道直接消失的问题；
- 新增 **【实验性】开启 MTR 距离现象修复（Mixin）**（默认关闭）：直接改写 MTR 自身
  渲染中的世界坐标平移，覆盖列车、屏蔽门、吊板、指示牌、线路图。
  注意：开启后乘坐列车时车厢可能错位，且宿主已经修好距离现象时（如 YMTR 3.6.4）不需要它；
- 配置界面重组：新增 **视角** 分页（视角滚转、视角惯性、惯性强度、回弹速度、余晃时间），
  实验性项目统一收进 **实验性** 分页并统一为「实验性：」前缀，JS 调试信息置于该页最前；
- 配置界面标题改为「hANTE 客户端配置」。

### 1.1.5 主要变更

- 新增三组**实验性模型懒加载**开关（列车模型 / EyeCandy 模型 / 轨道模型），**默认关闭**，
  可在 `hANTE 设置 → 实验性功能` 中开启；追加包较多时实测可省约 1.5 GB 内存；
- 模型解析缓存改为弱引用，上传后的 CPU 侧副本可被回收；
- Fabric 侧的 ModMenu 描述与 Forge 统一。

### 1.1.4 主要变更

- 修复跨机场飞机路径缺少跑道起飞段的问题；
- 移植 NeoMTR 内置 NTE 的增强型手动驾驶（HMI、ATP、虚拟列车、指令与教程见上文）；
- 修复退出虚拟驾驶后的浮空、无法跳跃/潜行、`/kill` 无效、偶尔被传回列车的问题；
- 修复停站接管时车门联锁导致无法起步，以及基础模拟把速度夹在 20 km/h 的问题；
- 更新模组名称、作者、主页、MIT 许可证与 HZYMTR 分支图标；
- 新增 Forge + Fabric 双加载器通用包（Fabric 支持已可用）。

## 兼容性

- Minecraft **1.20.1**
- Forge / Fabric 通用 jar（需要 Fabric API + MTR Fabric）
- MTR **3**（Forge 侧已在 YMTR 3.6.3 测试，Fabric 侧已在 MTR Fabric 测试）
- 多人虚拟驾驶要求客户端和服务端使用同一个 jar

## 构建

需要 **JDK 17**（不是 21）。

```powershell
$env:JAVA_HOME = "<你的 JDK 17 路径>"
$env:GRADLE_USER_HOME = "<你的 Gradle 缓存路径>"
.\\gradlew.bat build
```

产物：
- Forge：`forge/build/libs/forge-<版本>+1.20.1.jar`
- Fabric：`fabric/build/libs/fabric-<版本>+1.20.1.jar`
- 双加载器：`build/MTR-hANTE-<版本>+1.20.1-universal.jar`

发布时只保留双加载器 universal 包，Forge / Fabric 单独产物仅用于本地构建。

已知构建陷阱：

1. **不要使用 `--offline`** —— 首次构建需要联网拉取 parchment 映射。
2. **必须使用 JDK 17**。JDK 19+ 会触发 Loom 的 LWJGL 升级流程，可能报
   `Cannot mutate content repository descriptor 'LoomLocalRemappedMods(...)' after repository has been used`。
3. 如果 `:forge:remapJar` 报
   `Jar ... already contains an access transformer`，执行一次 clean build。

## 授权与致谢

授权继承上游，详见 [LICENSE](LICENSE)。

MTR-ANTE（原名 MTRSteamLoco）由 **Zbx1425** 创建于
[Teacon 2022](https://teacon.cn)，现由 **Aphrodite** 维护。
本分支由 **HZYZHZY** 和 **Ch1hayaAnonQWQ** 维护。

增强型手动驾驶实现移植自 [NeoMTR](https://github.com/zbx1425/NeoMTR) 内置 NTE。

- 上游仓库：https://github.com/aphrodite281/mtr-ante
- 上游文档：https://aphrodite281.github.io/mtr-ante/#
- 上游 Modrinth：https://modrinth.com/mod/mtr-ante
- MTR：https://github.com/jonafanho/Minecraft-Transit-Railway

---

# English

hANTE is an independent fork of
[Aphrodite's Nemo's Transit Expansion (MTR-ANTE)](https://github.com/aphrodite281/mtr-ante),
targeting **Minecraft 1.20.1 / Forge / Fabric** and **MTR 3** (including YMTR).
It focuses on compatibility, driving/camera feel, performance, and selected upstream fixes.

This repository is **not** a pull-request branch intended to be merged upstream; it is a
separately maintained line. The full upstream history is preserved so useful fixes can be
cherry-picked at any time.

## Differences from upstream

### Cross-airport airplane path fix

ANTE (and early hANTE 1.1.2 builds) can report
`gui.mtr.path_not_found_between` when generating a path between two airports. The cause is
that `BetterPathFinder.addPathPart` was missing the runway-takeoff branch used by the
original MTR/YMTR `PathFinder`: when a train is on a `RailType.RUNWAY` position with exactly
one connection, the original adds all runway endpoints as jump targets. ANTE's rewrite did
not, so an airplane could not leave the runway endpoint and enter the airborne segment.

hANTE restores that branch and adds:

- an `airborne` flag on `PathPart` so runway logic is not re-triggered in the air;
- skipping the current runway endpoint and already visited positions to prevent self-loops;
- 8-direction turn-step calculation in `addAirplanePath` (no `RailAngle` enum identity
  comparison);
- null guards for `lastConnections`, `newConnections`, and `calculateNewDirection`.

The main route can now be generated correctly. If a depot still fails as a whole, it is
usually a **map topology** issue, for example:

- a one-way runway supports only one direction (no takeoff/landing runway for the return leg);
- a completely isolated siding inside the depot area makes the depot take the minimum
  successful segment count across all sidings.

### Enhanced manual driving (ported from NeoMTR's built-in NTE)

hANTE ports the **enhanced manual driving** feature from
[NeoMTR](https://github.com/zbx1425/NeoMTR)'s built-in NTE to Minecraft 1.20.1 / Forge / Fabric / MTR 3
(YMTR is a Forge branch).

Features:

- `/mtrnte virtdrive` takes over the train you are currently riding;
- the original `drive_hmi.png` cab display: round speedometer, ATP yellow/red needles,
  target speed and target-distance bar;
- traction/brake notches, door interlock, platform-door alignment information, and ATP
  overspeed emergency braking;
- the virtual train hides the original one, avoiding two overlapping train fronts;
- on exit, MTR teleport/seat state is cleaned up to prevent floating, disabled jump/sneak,
  ineffective `/kill`, or being teleported back onto a train;
- multiplayer support when client and server use the same jar.

Tutorial:

1. Install the **same hANTE jar on both client and server**, then restart.
2. Right-click to sit in an **automatically running** train.
3. Run:
   ```
   /mtrnte virtdrive
   ```
4. Default controls (MTR 3.6.3):
   - `↑` / `W`: accelerate
   - `↓` / `S`: brake
   - `←`: toggle doors
   - `X`: neutral / coast
   - hold sneak for about 1.5 seconds: exit virtual driving
5. Other commands:
   - `/mtrnte virtdrive atpcutout`: cut out / restore ATP (ATP is enabled by default);
   - `/mtrnte virtdrive reset`: force-clear a stuck teleport/seat state after exiting.
6. When taking over at a station, applying power automatically closes the doors; wait for
   the door animation, then the train can move.

ATP is enabled by default; exceeding the ATP red needle triggers emergency braking. Use
`atpcutout` when full manual control is desired.

### Camera and view

- **Camera inertia**: `CameraInertia` + `CameraAccessor` bake the inertia offset into the
  camera position.
- **View roll**: `Rolling` supports camera and passenger-position roll with the train; camera
  roll can be disabled separately.
- **Car stability**: `VehicleRidingClientMixin` was reworked so cars use a camera position
  without the inertia offset — fixing the "camera shakes inside the car while the car is
  pushed around the screen" problem.
- **Roll no longer pushes the camera down** (1.1.2): `Rolling.applyRolling` restores the Y
  displacement caused by roll and keeps only the horizontal displacement.
- **Inertia is no longer reset incorrectly** (1.1.2): "roll zero" and "left the train" use
  separate timeouts.
- **Cornering uses a centrifugal feel** (1.1.2): lateral motion is critically damped with no
  overshoot; lateral gain was increased from 8 to 26.

### YMTR compatibility

- `YmtrAnteFallbackMixin` disables YMTR's ANTE fallback rail-rendering path. That path calls
  `Rail.getPosition` twice per rail per frame and was measured to reduce FPS to about 1/5.

### Experimental memory optimisation (off by default)

With large addon packs, model memory comes mostly from parsing and uploading every model
during resource load. hANTE offers three independent toggles that defer upload until a model
is rendered for the first time:

| Toggle | Effect |
| --- | --- |
| Experimental: lazy-load train models | Load OBJ/BBMODEL only when a train is first rendered |
| Experimental: lazy-load EyeCandy models | Load decorative models only when first rendered |
| Experimental: lazy-load rail models | Parse/upload custom rail models only when first rendered |

All three are **off by default**, matching vanilla behaviour. When enabled there is a one-off
load hitch the first time a model appears; afterwards it behaves normally, and addon content
that never enters view occupies no model memory at all.

> These toggles only affect memory usage. They never unload an already-loaded model, so models
> cannot disappear or turn transparent.

### Bug fixes

| Problem | Fix |
| --- | --- |
| Cross-airport airplane path cannot be created | Restore the runway-takeoff branch in `addPathPart` (see above) |
| Cable car cannot connect at some angles | `RailCalculator.calculate` no longer filters results with `Section.isValid()` — a NaN in any parameter discarded the whole result |

## Version

Current version: **1.1.6** (pre-release), based on upstream `alpha` at `0506691f`.

### 1.1.6 highlights

- Added **Experimental: Fix Far-Coordinate Jitter** (on by default). hANTE's own geometry
  (rails, eye candy, scripted trains, node blocks, distance labels) is now baked relative to
  rail chunks and drawn with camera-relative matrices, keeping world coordinates in double
  precision. Fixes rail wobble far from spawn and the rails disappearing beyond roughly
  10 million blocks;
- added **Experimental: Enable MTR Distance-Fix (Mixin)** (off by default). It rewrites the
  world-coordinate translation inside MTR's own rendering, covering trains, platform screen
  doors, hanging panels, signs and route maps. Note: while riding a train the car may be
  misplaced, and hosts that already fixed the distance phenomenon (e.g. YMTR 3.6.4) do not
  need it;
- config screen rework: new **View** page (camera rolling, camera inertia, strength,
  stiffness, damping); all experimental options moved into the **Experimental** page and
  renamed with an "Experimental:" prefix, with the JS debug toggle listed first;
- the config screen title is now "hANTE Client Config".

### 1.1.5 highlights

- Added three **experimental model lazy-loading** toggles (train models / EyeCandy models /
  rail models). They are **off by default** and can be enabled in
  `hANTE settings → experimental features`; with many addon packs they save around 1.5 GB;
- the model parse cache now uses weak references so uploaded CPU-side copies can be collected;
- the Fabric ModMenu description now matches Forge.

### 1.1.4 highlights

- Fixed the missing runway-takeoff segment for cross-airport airplane pathfinding;
- ported NeoMTR's built-in NTE enhanced manual driving (HMI, ATP, virtual train, commands and
  tutorial above);
- fixed floating / disabled jump and sneak / ineffective `/kill` / occasional teleport back
  onto a train after exiting virtual driving;
- fixed the station door interlock preventing departure and the base simulation clamping
  speed to 20 km/h;
- updated mod name, authors, homepage, MIT license, and icon for the HZYMTR branch;
- added a Forge + Fabric universal jar (Fabric support is available).

## Compatibility

- Minecraft **1.20.1**
- Forge / Fabric universal jar (requires Fabric API + MTR Fabric)
- MTR **3** (tested with YMTR 3.6.3 on Forge and with MTR Fabric)
- Client and server must use the same jar for multiplayer virtual driving

## Building

Requires **JDK 17** (not 21).

```powershell
$env:JAVA_HOME = "<your JDK 17 path>"
$env:GRADLE_USER_HOME = "<your Gradle cache path>"
.\\gradlew.bat build
```

Output:
- Forge: `forge/build/libs/forge-<version>+1.20.1.jar`
- Fabric: `fabric/build/libs/fabric-<version>+1.20.1.jar`
- Universal: `build/MTR-hANTE-<version>+1.20.1-universal.jar`

Releases ship only the universal jar; the loader-specific jars are local build outputs.

Known build traps:

1. Do **not** use `--offline` — the first build needs network access for parchment mappings.
2. Use **JDK 17**. JDK 19+ triggers Loom's LWJGL upgrade flow and can fail with
   `Cannot mutate content repository descriptor 'LoomLocalRemappedMods(...)' after repository has been used`.
3. If `:forge:remapJar` reports
   `Jar ... already contains an access transformer`, run a clean build.

## License and credits

Licensed under the MIT license inherited from upstream; see [LICENSE](LICENSE).

MTR-ANTE (formerly MTRSteamLoco) was created by **Zbx1425** at
[Teacon 2022](https://teacon.cn) and is maintained by **Aphrodite**.
This fork is maintained by **HZYZHZY** and **Ch1hayaAnonQWQ**.

The enhanced manual driving implementation is ported from
[NeoMTR](https://github.com/zbx1425/NeoMTR)'s built-in NTE.

- Upstream repository: https://github.com/aphrodite281/mtr-ante
- Upstream documentation: https://aphrodite281.github.io/mtr-ante/#
- Upstream Modrinth: https://modrinth.com/mod/mtr-ante
- MTR: https://github.com/jonafanho/Minecraft-Transit-Railway
