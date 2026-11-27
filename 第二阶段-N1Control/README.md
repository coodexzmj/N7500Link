# N1Control · N7500 低风险控制

项目阶段：第二阶段（低风险控制 MVP）  
版本：0.2.1  
包名：`com.bi4byf.n7500link.control`  
作者署名：BI4BYF

## 1. 本阶段目标

第二阶段在已完成的只读协议链路上，增加可以回读确认的低风险写入：

| 功能 | 实现方式 | 验证方式 |
| --- | --- | --- |
| 读取设备信息 | `GET_DEV_INFO` | 显示原始帧与解析结果 |
| 读取设置 | `READ_SETTINGS` | 解析信道 A/B、扫描、双信道模式、静噪 |
| 读取状态 | `GET_HT_STATUS` | 显示电源、收发、RSSI、当前信道 |
| 读取音量 | `GET_VOLUME` | 解析 0 到 15 |
| 写入音量 | `SET_VOLUME` | 写后约 900 ms 回读，要求值一致 |
| 切换扫描 | `WRITE_SETTINGS` 的设置位 `payload[1] bit 0x80` | 写后回读完整设置块 |
| 切换已保存信道 A/B | `WRITE_SETTINGS` 的双信道选择位 | 写后回读完整设置块 |
| 恢复原音量 | 使用写入前读取到的音量 | 只回滚一次并回读 |
| 恢复上次设置 | 保存写入前完整设置块 | 只回滚一次并回读 |
| 50 次稳定性自检 | 同一音量连续写入/回读 | 任一次失败立即停止，不盲重试 |

本阶段**不包含**：

- PTT 按下或释放
- 音频采集、接收或发射
- 网络服务、WebSocket、HT 账号或 HT 服务器
- `WRITE_RF_CH` 信道记录写入
- 频率、亚音、带宽、功率编辑
- 固件升级
- C4FM 或其他语音解码
- 自动盲重试或多写并发

APK 不申请 `INTERNET` 权限。发送入口只接受上表中需要的读取命令、`SET_VOLUME` 和 `WRITE_SETTINGS`。

## 2. 交付文件

- 安装包：`dist/N1Control-N7500-v0.2.1-debug.apk`
- 签名附属文件：`dist/N1Control-N7500-v0.2.1-debug.apk.idsig`
- SHA-256：`dist/SHA256SUMS.txt`
- 构建脚本：`build.ps1`
- 协议自测：`test.ps1`
- 安装与实机验收说明：`第二阶段-安装与验证说明.txt`
- 制作记录：`第二阶段-制作记录.md`
- 调试签名：`keystore/n1probe-debug.jks`

调试签名口令和别名见 `build.ps1`。不要将调试证书冒充正式发布证书。

## 3. 安装

先确认电脑能通过 ADB 访问 N1：

```powershell
C:\adb-n1\platform-tools\adb.exe devices -l
```

如果 N1 尚未出现在列表中，可先连接 N1 的网络 ADB 端口：

```powershell
C:\adb-n1\platform-tools\adb.exe connect <N1局域网IP>:5555
```

安装：

```powershell
C:\adb-n1\platform-tools\adb.exe install -r `
  "C:\Users\Zhang minjun\Desktop\N7500\第二阶段-N1Control\dist\N1Control-N7500-v0.2.1-debug.apk"
```

启动：

```powershell
C:\adb-n1\platform-tools\adb.exe shell am start -n `
  com.bi4byf.n7500link.control/.MainActivity
```

## 4. 使用顺序

1. 完全退出 HT 手机版，确保 N7500 的蓝牙串口没有被占用。
2. 在 N1 系统蓝牙设置中完成 N7500 配对。
3. 打开 N1Control，允许 Android 7.1 所需的定位权限。
4. 选择已配对的 N7500，点击“连接 N7500”。
5. 等待自动只读快照完成。
6. 读取当前音量后，用滑块选择目标音量，点击“写入并回读音量”。
7. 读取设置后，点击“切换扫描状态”或“选择信道 A/B”。
8. 需要撤销时，点击对应的“恢复上次音量”或“恢复上次设置”。
9. 需要压力验证时，点击“开始 50 次音量稳定性自检”。

程序每次写入都遵循同一顺序：

```text
读取原值 → 发送一次写命令 → 等待约 900 ms → 主动回读
→ 回读一致才算成功 → 不一致只回滚一次 → 回滚后回读确认
```

部分固件对 `WRITE_SETTINGS` 不返回写响应，因此程序不把“没有写响应”当作失败，而是以主动回读结果为准。

## 5. 设置字段规则

`READ_SETTINGS` 响应中的设置块从 `body[5]` 开始。写入时不能从零重建设置，而是复制完整原设置块，只覆盖以下目标位：

```text
payload[0] = 信道 A 低半字节 | 信道 B 低半字节
payload[1] = 扫描位 0x80 | 双信道位 0x30 | 静噪低 4 位
payload[9] = 信道 A 高半字节 | 信道 B 高半字节
```

特别说明：原 APK 中的 `SET_IN_SCAN` 负载 `0/1` 用于蓝牙发现扫描，不是信道扫描。本阶段使用 `WRITE_SETTINGS` 的 `payload[1] bit 0x80` 切换信道扫描。

## 6. 日志

应用日志保存在应用专属外部目录，不需要存储权限：

```text
/sdcard/Android/data/com.bi4byf.n7500link.control/files/N1Control-logs/
```

取回日志：

```powershell
C:\adb-n1\platform-tools\adb.exe pull `
  /sdcard/Android/data/com.bi4byf.n7500link.control/files/N1Control-logs `
  "C:\Users\Zhang minjun\Desktop\N7500\第二阶段-N1Control\实测日志"
```

日志会记录：

- 每次 TX/RX 的原始十六进制字节
- 命令名、状态码、负载
- 写入前状态、写入后回读结果
- 回滚尝试和回滚回读结果
- 稳定性自检的逐次进度
- N1 型号、Android 版本、ABI、蓝牙状态

## 7. 构建与自测

协议自测：

```powershell
powershell -ExecutionPolicy Bypass -File .\test.ps1
```

重新构建 APK：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

工具链默认路径为 `C:\adb-n1\toolchain`。构建脚本会完成资源编译、Java 编译、DEX 生成、zipalign、v2/v3 签名、对齐校验和清单检查。

## 8. 验证状态

当前已完成：

- 37 项 JVM 协议/状态自测通过。
- APK 构建、对齐、v2/v3 签名和清单检查通过。
- 权限仅包含 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`。
- 未申请 `INTERNET`。
- 0.2.1 横屏界面已安装到 N1，竖屏下连接区域被裁切的问题已修复。
- N1 已通过 SDP 自动信道连接 N7500，设备信息、设置、状态和通知读取成功。
- 同一命令在途请求互斥，写入期间刷新按钮禁用。
- SHA-256：`5DC98ADB7DA2EDC570CEF123D1E8F576CB4D486F16D0CA6DC9F4D80D6525563D`。

当前尚未完成：

- 尚未在真实 N7500 上完成音量写入、扫描写入、A/B 切换和 50 次稳定性自检。
- 信道索引基准、不同固件对 `WRITE_SETTINGS` 的响应行为仍需实机确认。
- 当前实机结论只覆盖界面和只读链路，不代表低风险写入已通过验收。

离线自测和只读实机检查通过，不代表写入实机验收通过。实机测试时应使用合法频率、合适假负载和受控功率，并由操作者随时可切断电台电源。
## 9. 安全边界

本程序不发送 PTT、音频、信道记录写入、频率编辑、固件升级或网络请求。它不替代原版 HT 的聊天室、服务器和音频功能，也不是远程发射系统。

下一阶段如果进入音频或 PTT，需要单独设计硬件/软件急停、断线释放、超时释放和远程权限控制，不能直接在本版本上叠加未经验证的发射命令。