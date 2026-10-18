# No Root UI demo

The independent UI demo is for checking layouts, themes, animations and workflows on a
device without LunexSU/Core or root. It is not a root implementation or a way to unlock
the production Manager. The production build keeps its original authentication and
kernel/userspace interfaces.

## Build and identity

In GitHub Actions, choose **Build Manager**, **Run workflow**, and enable
`no_root_demo`. It defaults to false. The enabled option builds only the isolated
demo and uploads `manager-no-root-demo`; it skips Core, ksud and production signing
and does not publish a release. Each run uses a fresh disposable demo signing key,
so demos from different Actions runs may require uninstalling the previous demo.
Ordinary push/PR/reusable release builds keep the production path.

After staging the normal build prerequisites described in `AGENTS.md`, build with:

```sh
cd manager
./gradlew clean assembleRelease testDebugUnitTest -PNO_ROOT_DEMO=true
```

The package is always `com.shixiaoshi0417.lunexsu.demo`, its label is
`LunexSU No Root Demo`, and its version ends in `-no-root-demo`. It uses the Android
debug signing configuration, never the production signing identity. An existing local
debug keystore can be selected with `-PNO_ROOT_DEMO_KEYSTORE=/path/to/debug.keystore`.
The demo can coexist with the normal Manager and has separate app-private preferences
and files. Do not try to bind its identity to a production Core.

A banner identifies **all working, authorization and module states as simulated**,
including on nested pages and in Material, MIUIX and Fina presentations. Its close
button saves dismissal in this demo package's private preferences and recreates the
activity with the ordinary full-window layout, rebuilding backdrop/blur layers instead
of retaining resized layers. It stays dismissed across restarts and rotation; clearing
the demo app's data resets this choice. The separate demo app name remains visible.
Theme/preferences changes and user-selected imports/exports affect only this app's
sandbox; they do not modify LunexSU or another app's private data.

## Safety boundary

- Only the explicit build property selects demo sources. There is no runtime switch
  that disables production authentication.
- Demo native, shell, module, profile and SUSFS repositories replace their production
  counterparts at compilation. Native Core/ksud libraries and bundled Zygisk binaries
  are excluded from the demo APK.
- The demo manifest has no app-zygote preload, boot receiver or root service. It does
  not register the production external root/module action handlers.
- Arbitrary shell jobs return an explicit nonzero failure. Raw shell tasks never run,
  even if the phone happens to be rooted. No `su`, ksud, reboot, ADB-root restart or
  boot-image patch command is executed.
- Module/profile/SUSFS switches only update in-memory demo data. Flash/script screens
  print a simulation notice without reading or executing their selected payload.
  The normal ZNN warning and its recovery acknowledgement/countdown remain visible.
- Reopening the process resets simulated backend data; it does not mean a real boot,
  module activation or root authorization took place.

The ordinary build (without `NO_ROOT_DEMO`) still uses the normal native and shell
implementations. Test both builds when changing this isolated demo. Compilation and
host tests do not establish device rendering, real root behavior or boot compatibility.
