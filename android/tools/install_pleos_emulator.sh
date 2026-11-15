#!/bin/zsh
# Pleos Connect Emulator v2.1.0 시스템 이미지 설치 (Android Studio SDK Manager 대신 수동).
# 실행 = 42dot "Pleos Connect SDK License Agreement" 에 동의하는 것으로 간주된다 (라이선스 원문: sysimg.xml 안).
# 필요 디스크: 약 20 GB (system.img 18 GB). zip 안에 source.properties 가 이미 있으면 그대로 쓴다.
set -e
ZIP="${1:?usage: install_pleos_emulator.sh <crp_car_arm64_emulator.zip>}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
DEST="$SDK/system-images/android-34/pleos_car_emulator/arm64-v8a"
TMP="$(mktemp -d)"
echo "unzip → $TMP"; unzip -q "$ZIP" -d "$TMP"
mkdir -p "$(dirname "$DEST")"; rm -rf "$DEST"
mv "$TMP/crp_avd_out_arm64-v8a" "$DEST"
[ -f "$DEST/source.properties" ] || cat > "$DEST/source.properties" <<'P'
Pkg.Desc=Pleos Connect v2.1.0 APIs arm64 System Image
Pkg.Revision=2
AndroidVersion.ApiLevel=34
SystemImage.Abi=arm64-v8a
SystemImage.TagId=android-automotive
SystemImage.TagDisplay=Pleos Connect v2.1.0 APIs
Addon.VendorId=pleos
Addon.VendorDisplay=Pleos Connect v2.1.0
P
# 라이선스 수락 기록 (sdkmanager 형식)
mkdir -p "$SDK/licenses"
echo "" >> "$SDK/licenses/pleos-connect-sdk-license"
rmdir "$TMP" 2>/dev/null || true
echo "installed: $DEST"; ls "$DEST" | head
echo
echo "다음: Android Studio > Tools > Device Manager > Create device > Automotive 폼팩터 > API 34 'Pleos Connect v2.1.0 APIs' 선택,"
echo "      Advanced: Cold boot, RAM 4 GB. 또는 아래 명령:"
echo "  $SDK/cmdline-tools/latest/bin/avdmanager create avd -n pleos -k 'system-images;android-34;pleos_car_emulator;arm64-v8a' -d automotive_1024p_landscape"
