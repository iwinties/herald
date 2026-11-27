FROM herald-build:36-m3-1.11.1

# Google's emulator-container template includes the ncurses 5 compatibility ABI.
# Ubuntu 26.04 no longer ships it. Use the signed Jammy archive at fallback
# priority so packages available in the base distribution always win.
RUN sed -i "s@http://security.ubuntu.com/ubuntu@http://archive.ubuntu.com/ubuntu@g" /etc/apt/sources.list.d/ubuntu.sources \
 && echo 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://archive.ubuntu.com/ubuntu jammy main universe' > /etc/apt/sources.list.d/herald-compat.list \
 && printf 'Package: *\nPin: release n=jammy\nPin-Priority: 1\n' > /etc/apt/preferences.d/herald-compat \
 && apt-get update && apt-get install -y --no-install-recommends \
    libc6 libdbus-1-3 libfontconfig1 libgcc1 libpulse0 libtinfo5 libx11-6 \
    libxcb1 libxdamage1 libnss3 libxcomposite1 libxcursor1 libxi6 libxext6 \
    libxfixes3 zlib1g libgl1 pulseaudio socat curl ca-certificates \
    python3 python3-pil tesseract-ocr tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/* /etc/apt/sources.list.d/herald-compat.list /etc/apt/preferences.d/herald-compat

ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/emulator:${PATH}"
RUN sdkmanager "emulator" "system-images;android-36;android-tv;x86_64" \
 && echo no | avdmanager create avd -n herald36 -k "system-images;android-36;android-tv;x86_64" -d tv_1080p
