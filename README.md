# N1Probe · N7500 只读验证工具

项目阶段：第一阶段  
版本：0.1.0  
包名：`com.bi4byf.n7500link.probe`  
作者署名：BI4BYF

## 1. 本阶段目标

在斐讯 N1 上直接通过系统蓝牙连接 WENNUO N7500，不启动 HT 手机版、不登录 HT 账号、不访问 HT 云服务器或聊天室。

本工具用于验证独立蓝牙协议链路，完成以下只读查询：

| 命令 | 指令号 | 用途 |
| --- | ---: | --- |
| `GET_DEV_INFO` | 4 | 读取设备、固件、信道数量等信息 |
| `REGISTER_NOTIFICATION` | 6 | 注册 HT 状态通知 |
| `READ_SETTINGS` | 10 | 读取双信道设置、静噪、扫描状态 |
| `READ_RF_CH` | 13 | 读取指定信道的频率、亚音、带宽、功率标志、名称 |
| `GET_HT_STATUS` | 20 | 读取电源、收发、RSSI、当前信道等状态 |
| `GET_VOLUME` | 22 | 读取音量 |

## 2. 安全边界

本阶段程序**不包含**以下功能：

- PTT 按下或释放
- 音频采集、接收或发射
- 信道写入
- 音量写入
- 扫描写入
- 固件升级
- HT 账号、聊天室或服务器连接
- C4FM 功能

主界面发送入口只接受上述六条只读命令，其他命令会被白名单直接拒绝。APK 不申请网络权限。

## 3. 交付文件

- 安装包：`dist/N1Probe-N7500-v0.1.0-debug.apk`
- 构建脚本：`build.ps1`
- 协议自测：`test.ps1`
- 安装与实机验收说明：`第一阶段-安装与验证说明.txt`
- 调试签名：`keystore/n1probe-debug.jks`

调试签名口令和别名见 `build.ps1`。该证书只用于第一阶段安装和升级，不应作为正式发布证书。

## 4. 安装

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
  "C:\Users\Zhang minjun\Desktop\N7500\第一阶段-N1Probe\dist\N1Probe-N7500-v0.1.0-debug.apk"
```

启动：

```powershell
C:\adb-n1\platform-tools\adb.exe shell am start -n `
  com.bi4byf.n7500link.probe/.MainActivity
```

## 5. 使用顺序

1. 完全退出 HT 手机版，确保 N7500 没有被 HT 占用。
2. 在 N1 的系统蓝牙设置中完成 N7500 配对。
3. 打开 N1Probe，允许定位权限。Android 7.1 读取蓝牙设备信息需要该权限。
4. 在下拉框选择已配对的 N7500，点击“连接 N7500”。
5. 连接成功后会依次自动读取设备信息、注册通知、读取设置、状态、音量和当前信道。
6. 需要连续观察时，点击“开始只读轮询”。程序每 5 秒读状态，每 30 秒读音量。
7. 所有 TX/RX 原始字节、半包/粘包统计和解析结果都显示在“原始收发记录”中。

## 6. 日志与环境快照

程序将日志保存在应用专属外部目录，不需要存储权限：

```text
/sdcard/Android/data/com.bi4byf.n7500link.probe/files/N1Probe-logs/
```

取回全部日志：

```powershell
C:\adb-n1\platform-tools\adb.exe pull `
  /sdcard/Android/data/com.bi4byf.n7500link.probe/files/N1Probe-logs `
  "C:\Users\Zhang minjun\Desktop\N7500\第一阶段-N1Probe\实测日志"
```

每次启动会生成一份 `environment-*.txt`，内容包括 N1 型号、Android 版本、ABI、蓝牙状态和已配对设备数量。会话文件 `n1probe-*.txt` 保存原始收发记录。

## 7. 构建与测试

协议编解码自测：

```powershell
powershell -ExecutionPolicy Bypass -File .\test.ps1
```

重新构建 APK：

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

构建脚本会完成：

1. 编译资源。
2. 生成 `R.java`。
3. 编译 Java。
4. 生成 DEX。
5. 对齐 APK。
6. 签名并验证 v2/v3 签名。
7. 输出到 `dist`。

工具链路径默认为 `C:\adb-n1\toolchain`，可通过参数覆盖。

## 8. 协议说明

串口 GAIA 帧的当前编码方式：

```text
FF 01 FLAGS LENGTH 00 02 CMD_HI CMD_LO [PAYLOAD...]
```

已实现的接收处理：

- 按 `FF 01` 查找帧头
- 根据长度字段重组半包
- 连续解析同一数据块中的多个帧
- 记录无法识别的丢弃字节
- 对失败响应保留完整原始帧，不伪装成成功

协议常量与解析结构参考 HTCommander 的公开实现。N7500 当前固件的实际兼容性仍需通过实机验证。

## 9. 已知限制

- 当前只覆盖只读查询，不包含任何控制或音频功能。
- 信道索引是否从 0 开始、通知掩码、部分字段位定义仍需用 N7500 实机确认。
- 蓝牙连接优先使用 SPP/SDP，失败后依次尝试 RFCOMM 信道 1 到 12。
- 本版本是前台 Activity，尚不是开机自启动的后台服务。
- 若读取失败，应以 `N1Probe-logs` 中的原始帧为准继续修正协议，不应仅依据界面文字判断。