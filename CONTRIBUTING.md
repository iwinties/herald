# Build from source

From a checkout with Docker installed, run the following commands.

```sh
tools/build.sh       # Android tests and APK build
tests/ha/run.sh      # Offline Home Assistant tests
```

The TV app is written to `out/herald.apk`. For installation on a TV you use every day, download the [official APK](https://github.com/iwinties/herald/releases/latest). [Install with ADB](docs/SETUP.md#install-with-adb)

Bug reports and feature requests are welcome through the [issue templates](https://github.com/iwinties/herald/issues/new/choose).
