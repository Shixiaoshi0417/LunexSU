# LunexSU とは?

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

LunexSU は Android GKI デバイスのための root ソリューションです。カーネルモードで動作し、カーネル空間で直接ユーザー空間アプリに root 権限を付与します。

## 機能

LunexSU の最大の特徴は、**カーネルベース**であることです。LunexSU はカーネルモードで動作するため、今までにないカーネルインターフェイスを提供できます。例えば、カーネルモードで任意のプロセスにハードウェアブレークポイントを追加できる、誰にも気づかれずに任意のプロセスの物理メモリにアクセスできる、カーネル空間で任意のシステムコールを傍受できる、などです。

さらに、LunexSU は [metamodule システム](metamodule.md) を提供しています。これはモジュール管理のためのプラグ可能なアーキテクチャです。従来の root ソリューションがマウントロジックをコアに組み込むのとは異なり、LunexSU はこの作業を metamodule に委任します。これにより、metamodule ([meta-overlayfs](https://github.com/Shixiaoshi0417/LunexSU/tree/main/userspace/meta-overlayfs)など) をインストールして、`/system`パーティションや他のパーティションへのsystemless変更を提供できます。

## 使用方法

こちらをご覧ください: [インストール方法](installation)

## ビルド方法

[ビルドするには](../../guide/how-to-build)

## ディスカッション

- GitHub: [LunexSU](https://github.com/Shixiaoshi0417/LunexSU/issues)
