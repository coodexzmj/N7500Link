# 第四阶段 · N1RemoteControl

项目阶段：第四阶段（远程低风险控制）

版本：0.4.0（versionCode 15）

作者署名：BI4BYF

当前状态：0.4.0 已完成软件实现、111 项离线门禁、APK 签名与对齐校验，并已覆盖安装到真实 N1。真实 N7500 上的登录、页面、快照、SSE 共 17 项检查全部通过；音量、扫描、A/B 三项受限写入均完成“基线读取 → 单次写入 → 回读确认 → 恢复原值”，最终电台状态已恢复。PTT、音频、频率、亚音、带宽和信道记录写入没有执行。

总设计：[项目总设计方案（仓库主页）](../README.md)

## 本阶段解决什么

第三阶段已验证 N1 可以常驻读取 N7500。本阶段在不引入 PTT 和音频的前提下，把三项风险最低、可从快照回读确认的设置开放给受鉴权网页：

- 音量 `0..15`。
- 扫描开/关。
- 当前 A/B 信道侧切换。

```text
PC / iPhone
    |
家庭 Wi-Fi / Tailscale
    |
N1RemoteControl 0.4.0
    |-- 登录、令牌、会话
    |-- 只读快照与 SSE
    |-- 音量 / 扫描 / A/B 受限写入
    |-- 基线、回读、超时与一次回滚
    |
Bluetooth Classic / RFCOMM / GAIA
    |
WENNUO N7500
```

## 与第三阶段的区别

| 项目 | 第三阶段 0.3.8 | 第四阶段 0.4.0 |
| --- | --- | --- |
| 包名 | `com.bi4byf.n7500link.gateway` | `com.bi4byf.n7500link.remote` |
| 核心定位 | 只读远程监控 | 只读监控 + 三项受限写入 |
| 写命令 | 完全禁止 | 仅允许 `SET_VOLUME` 与完整设置块 `WRITE_SETTINGS` |
| 控制接口 | 无 | `/api/v1/control`、`/volume`、`/scan`、`/side` |
| 写入保护 | 无 | 登录鉴权、`confirm:true`、单操作互斥、回读、超时、最多一次回滚 |
| PTT / 音频 | 禁止 | 继续禁止 |
| 频率/亚音/带宽/信道记录写入 | 禁止 | 继续禁止 |

第四阶段使用独立包名，可与第二阶段、第三阶段应用共存。三个应用不能同时占用同一台 N7500，实际运行时只应保留一个蓝牙执行节点。

## 安全状态机

每次控制只允许一个操作在途：

```text
ACCEPTED
  -> BASELINE_READ
  -> WRITING
  -> READBACK
  -> READBACK_OK
```

异常路径：

```text
BASELINE_READ / WRITING / READBACK 超时（12 秒）
  -> 若已经写入且基线有效：ROLLBACK_WRITING
  -> ROLLBACK_OK 或 ROLLBACK_FAILED（回滚超时 8 秒）

链路 epoch 变化或蓝牙断开
  -> LINK_LOST / CANCELLED

已有操作未结束
  -> 409 CONTROL_BUSY
```

规则：

1. 写入前必须先读取当前音量或完整设置块。
2. 一次请求只允许一次目标写入，不自动重试写命令。
3. 回读必须与目标一致；不一致时自动发起最多一次恢复写。
4. 任何部分设置都不得直接写入。扫描和 A/B 使用完整原始设置块复制，只修改对应位。
5. 网页必须弹出二次确认；请求体必须显式包含 `confirm:true`。
6. 同一时间只允许一个操作；操作完成后才允许下一项。
7. 不使用 HT 账号、HT 频道口令或 HT 服务器。

## 已完成实现

- `GatewayService` 前台服务、开机恢复、唯一蓝牙会话和重连。
- 只读快照、10 位访问令牌、HttpOnly 会话、JSON Bearer/Cookie 鉴权、失败限流。
- SSE 状态摘要与网页自动刷新。
- 微信风格桌面/移动布局，包含总览、远程控制、设备、信道、网关、诊断和关于页签。
- 音量、扫描、A/B 三个控制卡；操作前二次确认，操作后展示基线、目标、回读和结果。
- `LowRiskWriteController` 单操作状态机、12 秒操作超时、8 秒回滚超时和最多一次回滚。
- `GaiaCodec` 写命令白名单，仅允许 `CMD_WRITE_SETTINGS` 与 `CMD_SET_VOLUME`。
- 完整设置块写入，不允许直接编辑频率、亚音、带宽或信道记录。
- 工具脚本 `tools/Test-LiveGateway.ps1`（登录/快照/SSE）与 `tools/Test-LiveControl.ps1`（三项受限写入及恢复）。

## 真实实机结果

2026-11-28 在真实 N1 与 N7500 上完成：

| 检查 | 结果 |
| --- | --- |
| 前台服务 | `isForeground=true`，服务在线 |
| 屏幕 | `Awake / ON`，HT 应用未运行 |
| 登录/页面/快照/SSE | 17/17 通过 |
| 音量 | `3 → 4 → 3`，两次均 `READBACK_OK`，最终恢复 3 |
| 扫描 | `OFF → ON → OFF`，两次均 `READBACK_OK`，最终恢复关闭 |
| A/B | `A → B → A`，两次均 `READBACK_OK`，最终恢复 A |
| 回滚 | 三项正常测试均未触发回滚，`rollbackSent=false` |
| 最终信道 | `1451直频 / 145.1 MHz / 当前 A` |
| 发射状态 | 未发射 |

不把离线模拟或历史版本结果替代本次 0.4.0 实机结果。

## 明确未执行

- PTT 按下或释放。
- 音频采集、上传、播放、录音或语音转文字。
- 频率、亚音、带宽、功率写入。
- 信道记录写入。
- 固件升级、工厂复位。
- HT 云端登录、聊天室或房间管理。
- N1/N7500 实际断电 3 次、两小时长稳和实际 iPhone/Tailscale 验收。

因此 0.4.0 是“三项低风险控制的实机验证版本”，不是远程发射版本，也不能替代尚未完成的长稳验收。

## 安装包

```text
dist/N1RemoteControl-N7500-v0.4.0-debug.apk
SHA-256: 49611CC70B39AB143077C578F18B8B816504F9C456C0E710874F632023796A03
大小: 99044 字节
签名: debug v2/v3
```

该 APK 使用本地调试证书，不是正式发布签名。

## 验证方法

离线检查：

```powershell
pwsh -NoProfile -File .\test.ps1
```

实机登录、页面、快照与 SSE：

```powershell
pwsh -NoProfile -File .\tools\Test-LiveGateway.ps1 `
  -TokenFile <本地令牌文件> `
  -BaseUrl http://<N1地址>:8710
```

三项受限写入及恢复（会实际修改并恢复电台设置）：

```powershell
pwsh -NoProfile -File .\tools\Test-LiveControl.ps1 `
  -TokenFile <本地令牌文件> `
  -BaseUrl http://<N1地址>:8710 `
  -ExecuteWrites
```

令牌只能放在本地受保护文件中或通过参数临时传入，不得提交到仓库、文档、日志或 URL。

## 设计文件

- [总体设计 V0.1](设计V0.1.md)
- [实现规格 V0.1](实现规格V0.1.md)
- [HTTP API V1 合同](API-V1.yaml)
- [验收记录](验收记录.md)

## 当前结论

0.4.0 已经证明：经过独立鉴权和显式确认后，电脑网页可以安全地完成音量、扫描、A/B 三项低风险远程控制，并能通过回读和恢复保持电台原状。

下一阶段仍不应直接加入 PTT。必须先完成长稳、断电重连、多人并发和三操作重复稳定性，再单独评审音频与发射安全设计。
