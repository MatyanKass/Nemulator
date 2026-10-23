#!/bin/sh
# Собирает ядро для APK: QEMU x86_64 из пакетов Alpine (aarch64, musl) +
# наш ALSA-плагин звука. Запускать внутри Alpine aarch64: в CI это
# docker-контейнер, а на телефоне — прямо в proot-Alpine.
#
#   tools/build-bundle.sh <куда положить bundle.tar.xz>
#
# В APK нет ни proot, ни chroot: приложение запускает QEMU через загрузчик
# musl (lib/ld-musl-aarch64.so.1 --library-path ... qemu-system-x86_64),
# поэтому все пути внутри бандла относительные к папке, куда его распакуют.
set -eu

OUT=${1:?куда положить bundle.tar.xz}
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
ROOT=$WORK/root
trap 'rm -rf "$WORK"' EXIT

apk add --no-cache build-base alsa-lib-dev xz python3 >/dev/null

mkdir -p "$ROOT/etc/apk"
cp -r /etc/apk/keys "$ROOT/etc/apk/"
apk --root "$ROOT" --initdb --no-scripts --no-cache \
    --repositories-file /etc/apk/repositories \
    add qemu-system-x86_64 qemu-img qemu-audio-alsa alsa-lib >/dev/null

gcc -O2 -Wall -DPIC -shared -fPIC \
    -o "$ROOT/usr/lib/libasound_module_pcm_nemu.so" \
    "$HERE/native/alsa-nemu/pcm_nemu.c" -lasound -lpthread
gcc -O2 -Wall -shared -fPIC \
    -o "$ROOT/usr/lib/libnemu_sigsys.so" "$HERE/native/sigsys/nemu_sigsys.c"

# libslirp (сеть QEMU) берёт DNS телефона из /etc/resolv.conf, а на Android
# такого файла нет — интернет в Windows был бы без DNS. Подменяем путь на
# строку той же длины: /proc/self/cwd/r — файл «r» в рабочей папке QEMU,
# его пишет приложение перед запуском.
SLIRP=$(ls "$ROOT"/usr/lib/libslirp.so.0.* | head -1)
python3 - "$SLIRP" <<'PY'
import sys
p = sys.argv[1]
d = open(p, 'rb').read()
assert d.count(b'/etc/resolv.conf\0') == 1
open(p, 'wb').write(d.replace(b'/etc/resolv.conf\0', b'/proc/self/cwd/r\0'))
PY

# Оставляем только то, что нужно для ПК q35 с Windows.
B=$WORK/bundle
mkdir -p "$B/lib" "$B/usr/bin" "$B/usr/lib/qemu" "$B/usr/share/qemu"
cp -a "$ROOT"/lib/ld-musl-aarch64.so.1 "$B/lib/"
cp -a "$ROOT"/lib/*.so* "$B/lib/" 2>/dev/null || true
cp -a "$ROOT"/usr/lib/*.so* "$B/usr/lib/"
cp -a "$ROOT"/usr/lib/libasound_module_pcm_nemu.so "$ROOT"/usr/lib/libnemu_sigsys.so "$B/usr/lib/"
cp -a "$ROOT"/usr/lib/qemu/audio-alsa.so "$B/usr/lib/qemu/"
cp -a "$ROOT"/usr/bin/qemu-system-x86_64 "$ROOT"/usr/bin/qemu-img "$B/usr/bin/"
for f in bios-256k.bin bios.bin kvmvapic.bin linuxboot_dma.bin multiboot_dma.bin \
         vgabios.bin vgabios-stdvga.bin vgabios-bochs-display.bin vgabios-virtio.bin \
         vgabios-ramfb.bin efi-e1000.rom efi-e1000e.rom efi-rtl8139.rom efi-virtio.rom \
         pxe-e1000.rom edk2-x86_64-code.fd edk2-i386-vars.fd; do
    [ -e "$ROOT/usr/share/qemu/$f" ] && cp -aL "$ROOT/usr/share/qemu/$f" "$B/usr/share/qemu/"
done
cp -a "$ROOT"/usr/share/qemu/keymaps "$B/usr/share/qemu/"
find "$B" -type f -name '*.so*' -exec strip --strip-unneeded {} + 2>/dev/null || true
strip "$B"/usr/bin/* 2>/dev/null || true

# Версия ядра — по ней приложение понимает, что бандл надо перераспаковать.
( cd "$B" && find . -type f | sort | xargs sha256sum | sha256sum | cut -c1-16 ) > "$B/VERSION"

mkdir -p "$(dirname "$OUT")"
tar -C "$B" -cf - . | xz -T0 -6 > "$OUT"
# Рядом: версия и распакованный размер — для проверки и полосы прогресса.
echo "$(cat "$B/VERSION") $(du -sb "$B" | cut -f1)" > "$(dirname "$OUT")/bundle.version"
echo "бандл: $OUT ($(du -h "$OUT" | cut -f1)), распакованный $(du -sh "$B" | cut -f1)"
