#!/system/bin/sh
# 在设备上执行驱动命令，避免宿主 shell 转义问题
# 用法: sh /data/local/tmp/drive.sh start "问题文本"
PKG=com.xueyiweng.app.debug
ACT=$PKG/com.xueyiweng.app.ui.MainActivity

CMD="$1"
shift

am start -n "$ACT" --es drive "$CMD" "$@" >/dev/null 2>&1
sleep 0.8
logcat -d -s XYW_DRIVE:* | sed 's/^.*XYW_DRIVE: //' | tail -n 15
logcat -c
