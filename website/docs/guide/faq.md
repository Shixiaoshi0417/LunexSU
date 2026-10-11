# FAQ

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

## Does LunexSU support my device?

LunexSU supports devices running Android with an unlocked bootloader. However, official support is only for GKI Linux Kernels 5.10+ (in practice, this means your device needs to have Android 12 out-of-the-box to be supported).

You can easily check the support for your device through the LunexSU manager, which is available [here](https://github.com/Shixiaoshi0417/LunexSU/releases).

If the app shows `Not installed`, it means your device is officially supported by LunexSU.

If the app shows `Unsupported`, it means your device isn't officially supported at present. However, you can build kernel source code and integrate LunexSU to make it work, or use [Unofficially supported devices](unofficially-support-devices).

## Do I need to unlock the bootloader to use LunexSU?

Yes. LunexSU requires an unlocked bootloader.

## Does LunexSU support modules?

Yes, most Magisk modules work on LunexSU. However, if your module needs to modify `/system` files, you need to install a [metamodule](metamodule.md) (such as `meta-overlayfs`). Other module features work without a metamodule. Check [Module guide](module.md) for more info.

## Does LunexSU support Xposed?

Yes, you can use LSPosed (or other modern Xposed derivative) with [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext).

## Does LunexSU support Zygisk?

LunexSU has no built-in Zygisk support, but you can use a module like [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext) to support it.

## Is LunexSU compatible with Magisk?

LunexSU's module system conflicts with Magisk's magic mount. If any module is enabled in LunexSU, Magisk will stop working entirely.

However, if you only use the `su` of LunexSU, it will work well with Magisk. LunexSU modifies the `kernel`, while Magisk modifies the `ramdisk`, allowing both to work together.

## Will LunexSU substitute Magisk?

No. Replacing Magisk isn't our goal. Magisk is already an excellent userspace root solution. LunexSU focuses on exposing kernel interfaces to users instead of supplanting Magisk.

## Can LunexSU support non-GKI devices?

It's possible. But you should download the kernel source, integrate LunexSU into the source tree, and compile the kernel yourself.

## Can LunexSU support devices below Android 12?

It's the device's kernel that affects LunexSU's compatibility, and it has nothing to do with the Android version. The only restriction is that devices launched with Android 12 must have a kernel version of 5.10+ (GKI devices). So:

1. Devices launched with Android 12 must be supported.
2. Devices with an older kernel (some devices with Android 12 also have the older kernel) are compatible (you should build kernel yourself).

## Can LunexSU support old kernel?

It's possible. LunexSU is backported to kernel 4.14 now. For older kernels, you need to backport it manually, and PRs are always welcome!

## How to integrate LunexSU for an older kernel?

Please check the [Integrate for non-GKI devices](how-to-integrate-for-non-gki) guide.

## Why my Android version is 13, and the kernel shows "android12-5.10"?

The kernel version has nothing to do with the Android version. If you need to flash kernel, always use the kernel version; the Android version isn't as important.

## I'm GKI 1.0, can I use this?

GKI 1.0 is completely different from GKI 2.0, you must compile kernel by yourself.

## How can I make `/system` RW?

We don't recommend that you modify the system partition directly. Please check [Module guide](module.md) to modify it systemlessly. If you insist on doing this, check [magisk_overlayfs](https://github.com/HuskyDG/magic_overlayfs).

## Can LunexSU modify hosts? How can I use AdAway？

Of course. But LunexSU doesn't have built-in hosts support, you can install a module like [systemless-hosts](https://github.com/symbuzzer/systemless-hosts-LunexSU-module) to do it.

## Why aren't my modules working after fresh install?

If your modules need to modify `/system` files, you need to install a [metamodule](metamodule.md) to mount the `system` directory. Other module features (scripts, sepolicy, system.prop) work without a metamodule.

**Solution**: See the [Metamodule Guide](metamodule.md) for installation instructions.

## What is a metamodule and why do I need one?

A metamodule is a special module that provides infrastructure for mounting regular modules. See the [Metamodule Guide](metamodule.md) for a complete explanation.
