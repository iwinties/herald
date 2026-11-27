FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl zip && rm -rf /var/lib/apt/lists/*
ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/clt.zip -d $ANDROID_SDK_ROOT/cmdline-tools \
 && mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest && rm /tmp/clt.zip
RUN yes | $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
 && $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager "build-tools;36.1.0" "platforms;android-36" "platform-tools" >/dev/null
ENV PATH=$PATH:/opt/android-sdk/build-tools/36.1.0:/opt/android-sdk/platform-tools

COPY deps.lock /opt/deps/deps.lock
RUN set -eu; cd /opt/deps; chmod 644 deps.lock; \
    while read -r file digest role url; do curl -fsSL -o "$file" "$url"; done < deps.lock; \
    awk '{print $2"  "$1}' deps.lock | sha256sum -c -
