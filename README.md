# Nemulator

Эмулятор ПК для Android-телефона: настоящая Windows 10 в QEMU, русский интерфейс,
управление пальцами, экранная ПК-клавиатура, интернет и микрофон.

## Как устроено

- **Ядро** — QEMU x86_64 из пакетов Alpine (aarch64, musl). `tools/build-bundle.sh`
  упаковывает его в `assets/bundle.tar.xz`, приложение распаковывает в `files/qemu`
  и запускает через загрузчик musl, без proot/chroot. Поэтому `targetSdk = 28`
  (как у Termux): с 29+ Android не даёт запускать программы из папки приложения.
- **Экран и ввод** — VNC через unix-сокет (`RfbClient.kt`). Клавиши идут скан-кодами
  (расширенное событие QEMU), мышь — абсолютными координатами на `usb-tablet`.
- **Звук и микрофон** — свой ALSA-плагин `native/alsa-nemu`: звук QEMU идёт в
  unix-сокеты, на той стороне `AudioTrack`/`AudioRecord` (`AudioBridge.kt`).
- **Интернет** — NAT QEMU (slirp) + сетевая карта e1000. DNS телефона пишется в файл
  `r` в рабочей папке: путь `/etc/resolv.conf` в libslirp заменён на `/proc/self/cwd/r`.
- **Питание** — QMP: «Завершить работу» (ACPI), перезагрузка, выдернуть шнур.

## Сборка

Только в GitHub Actions (`.github/workflows/apk.yml`), APK — в релизе `apk-latest`.
Ядро можно проверить прямо на телефоне в proot-Alpine:

    sh tools/build-bundle.sh /tmp/bundle.tar.xz
