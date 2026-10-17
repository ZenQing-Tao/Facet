#!/usr/bin/env bash
# 不依赖 Gradle 的最小构建：aapt2 + javac + d8 + zipalign + apksigner
#
#   T=<工具目录> bash build.sh
#
# 工具目录里需要：jdk/（JDK 17）、bt/（Android build-tools 36）、plat/（android-36 的 android.jar）
# 可选环境变量：
#   OUT=<中间产物目录>            默认 ./build
#   KEYSTORE=<签名文件>           默认 ~/.facet/facet.keystore（不存在会自动生成，口令存在同目录 keystore.pass）
#   VERSION_NAME / VERSION_CODE   覆盖 AndroidManifest 里的版本号（测试更新用）
set -euo pipefail
cd "$(dirname "$0")"

: "${T:?请设置 T=<工具目录>，见脚本开头的说明}"
OUT="${OUT:-$PWD/build}"
KS_DIR="$(cygpath -m "$HOME" 2>/dev/null || echo "$HOME")/.facet"
KEYSTORE="${KEYSTORE:-$KS_DIR/facet.keystore}"
KS_PASS_FILE="$(dirname "$KEYSTORE")/keystore.pass"

EXE=""; [ -f "$T/bt/aapt2.exe" ] && EXE=".exe"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
JAVA="$T/jdk/bin/java"; JAVAC="$T/jdk/bin/javac"; KEYTOOL="$T/jdk/bin/keytool"
ANDROID_JAR="$T/plat/android.jar"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/cls" "$OUT/stubcls" "$OUT/dex" dist

VER_FLAGS=(--replace-version)
[ -n "${VERSION_NAME:-}" ] && VER_FLAGS+=(--version-name "$VERSION_NAME")
[ -n "${VERSION_CODE:-}" ] && VER_FLAGS+=(--version-code "$VERSION_CODE")

echo "[1/6] 资源"
"$T/bt/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$T/bt/aapt2$EXE" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 31 --target-sdk-version 34 "${VER_FLAGS[@]}" \
    --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "[2/6] Xposed API 桩"
"$JAVAC" -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/stubcls" \
    $(find stubs -name '*.java') 2>&1 | grep -v "bootstrap\|^warning\|^[0-9]* warning" || true

echo "[3/6] 编译 Java"
"$JAVAC" -J-Duser.language=en -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR$SEP$T/bt/core-lambda-stubs.jar" \
    -cp "$OUT/stubcls" -d "$OUT/cls" $(find src "$OUT/gen" -name '*.java')

echo "[4/6] dex"
"$JAVA" -cp "$T/bt/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 31 \
    --lib "$ANDROID_JAR" --classpath "$OUT/stubcls" --output "$OUT/dex" $(find "$OUT/cls" -name '*.class')

echo "[5/6] 打包"
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
    "$OUT/base.apk" "$OUT/dex/classes.dex"
"$T/bt/zipalign$EXE" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    python -c "import secrets;print(secrets.token_urlsafe(24))" > "$KS_PASS_FILE"
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass:file "$KS_PASS_FILE" -keypass:file "$KS_PASS_FILE" \
        -alias facet -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Facet" >/dev/null 2>&1
    echo "已生成签名文件 $KEYSTORE —— 请备份，丢了就无法再发布能覆盖安装的更新"
fi
"$JAVA" -jar "$T/bt/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "file:$KS_PASS_FILE" \
    --ks-key-alias facet --out dist/Facet.apk "$OUT/aligned.apk"
ls -la dist/Facet.apk
