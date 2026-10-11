# 运输船 CF（Android 原生版）

穿越火线经典地图「运输船」的 **Android 原生移植版**，参考项目：
`E:\workspace\_other\_Origin\claude-opus-5-5-demo\cf-transport-ship`（Three.js 网页版）。

全部代码为 **Kotlin + OpenGL ES 3.0**，零第三方依赖、零外部资源 ——
纹理、模型、音效全部程序化生成（与原版一致）。

## 功能

- **团队竞技**：潜伏者 vs 保卫者，4v4 / 6v6 / 8v8，目标 30/50/100 击杀，10 分钟时限
- **运输船地图完整还原**：甲板、集装箱管道、二楼平台、中路 V 形箱、船头箱堆、上层建筑、吊机与吊装集装箱（随风摆动）、旋转雷达、船体与海洋
- **武器**：AK-47 / M4A1 / AWM（双段开镜）/ MP5 / 沙漠之鹰 / 军刀（轻重击+背刺）/ 手雷，全部参数与原版一致（散布、后坐、穿透、距离衰减、护甲穿透）
- **机器人 AI**：rush / flank / hold 三种角色，A* 寻路，听觉与视觉感知，反应时间、瞄准误差、压枪补偿按难度分级（简单→地狱），会换弹、丢雷、开镜、拉栓
- **战斗系统**：爆头/胸/腹/四肢分区伤害、穿墙（木箱可穿）、出生保护、死亡镜头、连杀播报（双杀→超神 + TTS 英文语音）、击杀信息流、计分板
- **渲染**：方向光阴影贴图（PCF）、8 盏点光源、指数雾、Preetham 天空、Gerstner 海浪（泡沫/尾迹/开尔文波）、云层、程序化 PBR 简化光照、泛光、ACES 色调映射、受击/低血量/死亡/出生保护屏幕特效
- **程序化纹理**：甲板钢板（焊缝/螺栓/防滑点/锈迹）、8 色集装箱（瓦楞/箱号/做旧）、木箱/弹药箱、船体（水线/锈流/排水口）、栅栏/格栅/标识牌图集/枪口火焰/血渍/焦痕
- **程序化音频**（AudioTrack 实时合成）：按武器合成的枪声（瞬态爆裂+主体+低频冲击+尾音+回声）、按地面材质的脚步声、换弹/切枪/拉栓、命中反馈、爆炸、耳鸣与闷音、海浪/海风/主机/海鸥/汽笛环境音、低血量心跳
- **角色**：19 骨骼刚体蒙皮士兵、程序动画（走跑蹲跳/死亡/手臂两骨 IK 持枪）、12 部位命中盒
- **第一人称**：武器 Springer 模型（弯弹匣/拉栓/套筒可动）、换弹/检视/挥刀/投雷动画、手臂 IK、弹壳抛射、枪口火焰
- **触屏操作**：左侧虚拟摇杆 + 右侧滑动视角 + 全套按钮（开火/开镜/跳/蹲/换弹/切枪/检视/换装/计分板/暂停）；支持外接键盘（WASD/Shift/空格/C/R/Q/B/F/Tab/Esc/1-4）

## 构建

```bash
# 需要 JDK 17 与 Android SDK（local.properties 指向 SDK）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

调试通道：`adb shell am start -n com.cf.transportship/.MainActivity --ez autostart true` 可跳过菜单直接开局。

## 架构

```
app/src/main/java/com/cf/transportship/
├── engine/     数学库(Mat4/Quat/Vec3)、GL 工具、着色器(GLSL ES 3.0)、
│               网格(VAO/静态合批/动态)、渲染管线(阴影→场景→第一人称→泛光→后期)
├── gfx/        程序化纹理(fbm 噪声/Canvas)、几何图元、士兵(骨骼/蒙皮/IK/命中盒)、
│               枪械模型、第一人称 ViewModel、特效(粒子/贴花/曳光)
├── game/       OBB 碰撞世界(射线/扫掠/台阶)、NavGrid A*、武器数据与弹道、
│               地图构建(按材质合批/AO 烘焙)、Actor/Player/Bot AI、Game 主控
├── audio/      DSP(双二阶/包络/噪声库/混响/软限幅) + AudioTrack 流式合成引擎
└── ui/         HUD/菜单(Canvas 绘制) + 触屏控制 + GLSurfaceView 装配
```

## 与原版的对应关系

| 网页版 (Three.js) | Android 版 |
|---|---|
| render.js 后期链 | Shaders.kt POST_FRAG + bloom 管线 |
| env.js 天空/海洋/云 | Shaders.kt SKY/OCEAN/CLOUD（GLSL 直接移植） |
| map.js 地图 | game/Map.kt + MapCore.kt（逐行移植，含 AO 烘焙） |
| physics.js | game/Physics.kt |
| weapons.js | game/Weapons.kt |
| actor/player/bots | game/Actor.kt / Player.kt / Bot.kt |
| character.js | gfx/Soldier.kt |
| guns.js / viewmodel.js | gfx/GunModels.kt / ViewModel.kt |
| effects.js | gfx/Effects.kt |
| audio.js (WebAudio) | audio/AudioEngine.kt（AudioTrack 合成） |
| hud.js / touch.js | ui/Hud.kt（Canvas 绘制） |

## 已知差异

- 分辨率缩放按设备 dpr 自适应（高/均衡/流畅三档）
- 暂不支持第一人称武器 HUD 图标（用文字代替原版的离屏渲染剪影）
- 队友头顶名称以屏幕投影文字绘制
