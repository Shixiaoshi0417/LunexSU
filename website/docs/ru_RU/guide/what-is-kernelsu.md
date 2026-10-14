# Что такое LunexSU? {#introduction}

> Adapted from the upstream [KernelSU](https://github.com/tiann/KernelSU) guides. See the [LunexSU README](https://github.com/Shixiaoshi0417/LunexSU#readme) for package identity, authorization expiry and migration details.

LunexSU - это root-решение для устройств Android GKI, работающее в режиме ядра и предоставляющее root-права пользовательским приложениям непосредственно в пространстве ядра.

## Особенности {#features}

Основной особенностью LunexSU является то, что он **основан на ядре**. LunexSU работает в режиме ядра, поэтому он может предоставить интерфейс ядра, которого раньше не было. Например, мы можем добавить аппаратную точку останова любому процессу в режиме ядра; мы можем получить доступ к физической памяти любого процесса без чьего-либо ведома; мы можем перехватить любой syscall в пространстве ядра; и т.д.

Кроме того, LunexSU предоставляет [систему metamodule](metamodule.md), которая является подключаемой архитектурой для управления модулями. В отличие от традиционных root-решений, которые встраивают логику монтирования в свое ядро, LunexSU делегирует это metamodules. Это позволяет устанавливать metamodules (например [meta-overlayfs](https://github.com/Shixiaoshi0417/LunexSU/tree/main/userspace/meta-overlayfs)) для обеспечения бессистемных модификаций раздела `/system` и других разделов.

## Как использовать {#how-to-use}

Пожалуйста, обратитесь к: [Установка](installation)

## Как собрать {#how-to-build}

[Как собрать](how-to-build)

## Обсуждение {#discussion}

- GitHub: [LunexSU](https://github.com/Shixiaoshi0417/LunexSU/issues)
