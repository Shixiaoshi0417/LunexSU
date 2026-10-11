# Apa itu LunexSU?

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

LunexSU adalah solusi root untuk perangkat GKI Android, ia bekerja dalam mode kernel dan memberikan izin root ke aplikasi userspace secara langsung di ruang kernel.

## Fitur

Fitur utama dari LunexSU adalah **berbasis kernel**. LunexSU bekerja dalam mode kernel, sehingga dapat menyediakan antarmuka kernel yang belum pernah kita miliki sebelumnya. Sebagai contoh, kita dapat menambahkan breakpoint perangkat keras ke proses apa pun dalam mode kernel; Kita dapat mengakses memori fisik dari proses apa pun tanpa diketahui oleh siapa pun; Kita dapat mencegat syscall apa pun di ruang kernel; dll.

Selain itu, LunexSU menyediakan [sistem metamodule](metamodule.md), yang merupakan arsitektur yang dapat dipasang untuk manajemen modul. Tidak seperti solusi root tradisional yang mengintegrasikan logika mount ke dalam intinya, LunexSU mendelegasikan ini ke metamodules. Ini memungkinkan Anda untuk memasang metamodules (seperti [meta-overlayfs](https://github.com/Shixiaoshi0417/LunexSU/tree/main/userspace/meta-overlayfs)) untuk menyediakan modifikasi systemless pada partisi `/system` dan partisi lainnya.

## Bagaimana cara menggunakannya

Silakan merujuk ke: [Installation](installation)

## Bagaimana cara men-buildnya

[How to build](how-to-build)

## Diskusi

- GitHub: [LunexSU](https://github.com/Shixiaoshi0417/LunexSU/issues)
