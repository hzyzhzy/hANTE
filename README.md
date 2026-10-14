# hANTE

基于 [Aphrodite's Nemo's Transit Expansion (MTR-ANTE)](https://github.com/aphrodite281/mtr-ante)
的独立分支，针对 **YMTR**（Minecraft Transit Railway 的第三方分支）做了兼容与手感调整，
并移植了上游的部分修复。

本仓库**不是**准备合并回上游的 PR 分支，而是一条独立维护的线路 —— 与上游的功能取向
已经分化（上游侧重 JS 引擎与新功能，本分支侧重 YMTR 兼容与手感/性能），故单独建库。
上游历史完整保留，便于随时挑选需要的修复。

---

## 与上游的差异

### 相机与视角

- **相机惯性**：新增 `CameraInertia` + `CameraAccessor`，把惯性偏移烘焙进相机位置
- **视角滚转**：`Rolling` 重写，支持相机与乘坐位置跟随列车滚转，可单独关闭相机滚转
- **车厢稳定**：`VehicleRidingClientMixin` 重构，车厢改用**不含惯性偏移**的相机位置
  —— 解决「相机在车厢里晃、车厢在画面上跟着被推走」的问题
- **滚转不再压低相机**（1.1.2）：`Rolling.applyRolling` 把滚转造成的相机 Y 位移拉回原值，
  只保留水平面内的位移 —— 过超高弯时不再有「相机被压向地面」的观感；朝向倾斜完全不受影响
- **惯性不再被误清**（1.1.2）：「滚转归零」与「判定下车」拆成两个超时。
  原先 `setOffsets` 在停站 / 低速 / 进出站时中断 1 秒就会被判成下车，`CameraInertia`
  随即 `reset()` 把前 / 侧 / 上三个偏移全部清零 —— 侧向晃动因此永远建立不起来
- **过弯改成离心力手感**（1.1.2）：侧向单独钉在**临界阻尼**（无超调、单调到位、不回弹），
  不再一进弯就来回摆；侧向增益 8 → **26**（实测侧向偏移原先只有 0.01~0.12 格，
  而纵向有 0.18 格，差十几倍）

### YMTR 兼容

- `YmtrAnteFallbackMixin`：关闭 YMTR 的 ANTE fallback 轨道渲染路径。
  该路径每帧对每根铁轨调用两次 `Rail.getPosition`，实测会把帧率压到原来的 1/5。
  机制说明见该文件头部注释。

### Bug 修复（移植自上游 1.1.1）

| 问题 | 修复 |
| --- | --- |
| 飞机无法创建路径 | `BetterPathFinder` 改为收集全部候选后择优（按总耗时最短选取），不再因单个分支解不出就丢弃整条路径；另加 `VisitedSegment` 去重防绕环 |
| 缆车在某些角度无法连接 | `RailCalculator.calculate` 不再用 `Section.isValid()` 过滤结果 —— 该检查只判断各参数是否有限，出现 NaN 就把整组作废 |

---

## 版本

当前 **1.1.2**，基线为上游 `alpha` 分支的 `0506691f`。

---

## 构建

环境要求 **JDK 17**。注意不是 21，原因见下。

```powershell
$env:JAVA_HOME = "<你的 JDK 17 路径>"
$env:GRADLE_USER_HOME = "<你的 Gradle 缓存路径>"
.\gradlew.bat build
```

产物：`forge/build/libs/forge-<版本>+1.20.1.jar`

两个已知的构建陷阱：

1. **不要加 `--offline`** —— 首次构建需要联网拉取 parchment 映射，离线会直接解析失败。
2. **必须用 JDK 17 编译**。用 JDK 19 及以上会触发 loom 的 LWJGL 升级流程去修改依赖仓库，
   在 Gradle 8.11 + Architectury Loom 1.9.424 下报
   `Cannot mutate content repository descriptor 'LoomLocalRemappedMods(...)' after repository has been used`
   导致配置阶段失败。换回 JDK 17 即可绕过。
3. 若 `:forge:remapJar` 报 `Jar ... already contains an access transformer`，
   是增量缓存不一致，执行一次 `gradlew clean build`。

---

## 与上游同步

```powershell
git fetch upstream
git log upstream/alpha --oneline -10      # 看上游有什么新东西
git cherry-pick <需要的提交>
```

---

## 授权与致谢

授权继承上游，详见 [LICENSE](LICENSE)。

原项目 MTR-ANTE（原名 MTRSteamLoco）由 **Zbx1425** 创建于
[Teacon 2022](https://teacon.cn) 模组开发活动，现由 **Aphrodite** 开发维护。

- 上游仓库：https://github.com/aphrodite281/mtr-ante
- 上游文档：https://aphrodite281.github.io/mtr-ante/#
- 上游 Modrinth：https://modrinth.com/mod/mtr-ante
- 依赖的 MTR 本体：https://github.com/jonafanho/Minecraft-Transit-Railway
