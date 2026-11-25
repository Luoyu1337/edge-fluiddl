# AGENTS.md

接手这个仓库的说明：怎么改代码、构建、装机验证。

## 概况

LSPosed 模块，在 Edge 进程内 hook `NotificationManager.notify(...)`，把下载通知改写成
Android 16 的 `ProgressStyle` + promoted ongoing，交给 ColorOS 流体云显示。
下载完成后加「打开」和「关闭」两个按钮。只改内存对象，不写文件。

- 包名 `io.github.luoyu1337.edgefluiddl`，版本 1.0（versionCode 1）
- 作者 mahiroDesu（[B站主页](https://space.bilibili.com/511020312/)），图标来自 `mahiro_edge.svg`
- 目标应用 `com.microsoft.emmx`（Microsoft Edge）
- 最低 Android 16（API 36），ColorOS 16 / 17 实测可用

## 目录

```
app/src/main/
  AndroidManifest.xml   xposedmodule / xposeddescription / xposedminversion / xposedscope / icon
  assets/xposed_init    入口类 io.github.luoyu1337.edgefluiddl.Module
  res/                  启动图标：mipmap-anydpi-v26 自适应图标 + 各密度 PNG
  java/io/github/luoyu1337/edgefluiddl/
    Module.java              IXposedHookLoadPackage，只对 Edge 装 hook，SDK<36 直接跳过
    NotifyHook.java          beforeHookedMethod：替换 Notification 参数
    NotificationRewriter.java 改写逻辑、按钮、MediaStore、广播接收器
    OpenProxyActivity.java  「打开」收尾页：只发撤通知广播，不参与打开流程
compat/stubs/           编译期 Xposed API 占位，只参与 javac，不打包进 APK
tools/DumpApi.java      真机反射探测小工具（查系统方法签名用）
scripts/build.sh        Linux：javac + d8
scripts/link.sh         设备：aapt2 link（aapt2 是 bionic 二进制，只能在设备跑）
scripts/package.sh      Linux：塞 dex + apksigner 签名
scripts/keystore.jks    签名密钥（alias=module，CN=mahiroDesu，storepass/keypass=android）
```

## 构建与安装

```bash
bash scripts/build.sh                 # Linux chroot
sh scripts/link.sh                    # 设备上执行
bash scripts/package.sh               # Linux chroot
pm install -r -d build/Edgefluiddl.apk
am force-stop com.microsoft.emmx      # 必须，否则 Edge 里跑的还是旧 dex
```

默认路径写死在脚本里：SDK `/data/local/tmp/fluiddl/sdk`、aapt2
`/data/local/tmp/tprefix/data/data/com.termux/files/usr/bin`。换机器改这两个变量。
`version-code` / `version-name` 在 `scripts/link.sh` 里，发版时改那一行。

## 关键 API 事实（Android 17 / ColorOS 17 实测）

- `Notification$ProgressStyle`：`setProgress(int)`、`setProgressIndeterminate(boolean)`、
  `setProgressTrackerIcon(Icon)`、`setStyledByProgress(boolean)`。
  没有三参数的 `setProgress(int,int,boolean)`（早期版本在这里踩过坑）。
- `Notification.Builder`：`setRequestPromotedOngoing(boolean)`（不是 setPromotedOngoing）、
  `setShortCriticalText(String)`、`setProgress(int,int,boolean)`。
- 通知要被流体云接管，必须同时满足：`ProgressStyle` + `setRequestPromotedOngoing(true)`
  + `setOngoing(true)`，NMS 才会打上 `PROMOTED_ONGOING`。
- Edge 的下载通道：`downloads`，完成后 `completed_downloads`；通知 id 从 100039x 递增，
  groupKey 为 `Downloads`。Edge 不走系统 DownloadManager。
- `Notification.Builder.setTimeoutAfter(ms)`：完成通知 60 秒后由系统撤销，不依赖 Edge 进程存活。
- chooser 的结果回调用 `IntentSender`，ColorOS 不会回调，不能依赖。改用
  `PendingIntent.getActivities`：系统先启动 chooser，再启动模块内 `OpenProxyActivity` 撤通知；
  收尾页起不来也不影响打开，另有 `setTimeoutAfter` 兜底。

## 踩过的坑（改代码前必读）

1. 完成通知的 extras 必须和 style 对齐：只设 `ProgressStyle(100)` 而不调
   `setProgress(100,100,false)`，流体云会按 0/0 排版，两个按钮会被挤出卡片底边。
2. 按钮不要给图标：1×1 透明图标也会让 ColorOS 预留图标位，标题整体右偏
   （容器中心 353、文字中心 395，改成 `Action.Builder(null, …)` 后二者重合）。
   个别系统不接受空图标时，`buildAction()` 会自动退回透明图标。
3. 进度条上的圆形把手是 `setProgressTrackerIcon()` 的默认图标，传 `null` 才消失。
4. manifest 的 `xposedscope` 用 `android:value` 写包名。用
   `android:resource="@array/…"` 的写法能打包、`aapt2 dump` 也正常，
   但模块完全不注入（Edge 反复重启都没有 hook 日志）。
5. 不要 kill 或手动重启 lspd：会导致 `System server: not connected`，模块不再注入，
   只能重启手机恢复。改完模块用 `am force-stop 目标应用` 即可。
6. 直接改 `modules_config.db` 里的作用域后，lspd 可能仍用旧缓存，需要重装模块 +
   重启目标应用，必要时在管理器里重新开关一次模块。

## 验证方法

```bash
# 1. 起一个慢速下载（60MB，约 30 秒）
mkdir -p /data/local/tmp/ww/cgi-bin && cat > /data/local/tmp/ww/cgi-bin/slowdl <<'EOF'
#!/system/bin/sh
echo "Content-Type: application/octet-stream"
echo "Content-Length: 62914560"
echo
i=0
while [ $i -lt 60 ]; do
  dd if=/dev/zero bs=1048576 count=1 2>/dev/null | tr "\0" "A"
  i=$((i+1)); sleep 0.5
done
EOF
chmod 755 /data/local/tmp/ww/cgi-bin/slowdl
busybox httpd -f -p 8099 -h /data/local/tmp/ww   # 后台常驻

# 2. 让 Edge 下这个地址（首次会弹「选择下载位置」，点下载）
am start -a android.intent.action.VIEW -d "http://127.0.0.1:8099/cgi-bin/slowdl" -p com.microsoft.emmx

# 3. 看结果
L=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep Edgefluiddl $L | tail            # hooked / progress N/100 / completed id=…
dumpsys notification --noredact | grep "channel=downloads " | grep emmx
#   下载中：flags 里应有 PROMOTED_ONGOING
#   完成：  flags 里应有 PROMOTED_ONGOING，且 actions=2

# 4. 清理
rm -f /sdcard/Download/slowdl*
```

「打开」按钮点下去应出现系统的「选择打开方式」（`com.android.intentresolver`）；
「关闭」按钮应让通知立刻消失。

## 代码约定

- 所有系统 API 都先用反射探测：拿不到就放弃改写、保留原通知，不向 Edge 抛异常。
- `NotificationRewriter.logFailure()` 只打印有限次数，避免高频通知刷日志。
- 模块不写文件、不建库、不写 SharedPreferences、不加权限、无常驻服务；
  卸载后除 LSPosed 记录的模块条目外不留任何东西。新增功能请保持这个约束。
- 只处理 Edge 的两个下载通道；要支持别的浏览器就加通道常量和目标包名，别改动现有逻辑。
- 同一次 `notify` 会经过多个重载 hook（2 参 → 3 参 → `notifyAsPackage`），改写后的通知在 extras
  里打 `io.github.luoyu1337.edgefluiddl.rewritten` 标记，后续 hook 直接跳过，
  避免重复改写和重复查 MediaStore。
- 通知组摘要（`FLAG_GROUP_SUMMARY`）不改写；「关闭」按钮的 requestCode 由 `id/tag` 派生，
  因为 extras 不参与 PendingIntent 身份比较，固定 requestCode 会让多个完成通知共用同一个 PendingIntent。

## 发布到 LSPosed 模块仓库

- 提交 issue 标题：`[submission] io.github.luoyu1337.edgefluiddl`，仓库名必须与包名完全一致。
- 模块仓库根目录只放 `SUMMARY`（首页一句话简介）和 `README.md`，不要上传源码。
- Release：标题填版本名（如 `1.0`），tag 用 `版本号-版本名`（如 `1-1.0`），附上签名的 APK。
- Home Page 填支持链接（B 站主页或源码仓库）。
- APK 侧要求已满足：`io.github.*` 包名、`assets/xposed_init`、`xposedmodule` /
  `xposedminversion` / `xposedscope` / `xposeddescription` 齐全，已签名，未打包 Xposed API 桩类。
- 发布前对照 `tools/DumpApi.java` 重新确认系统 API 签名，避免 Android 版本升级后静默失效。

## 版本号

对外版本统一在仓库根目录的 `version.env`（`VERSION_CODE` / `VERSION_NAME`）；
当前 1.0（versionCode 1）。

更换签名后，已安装的旧版无法覆盖安装：必须先在 LSPosed 里停用并卸载旧包，
再安装新包，重新启用模块并勾选 Edge 作用域。
