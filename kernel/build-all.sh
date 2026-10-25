#!/bin/bash
set -euo pipefail

if [ -z "${1:-}" ]; then
    KMIS="android12-5.10 android13-5.10 android13-5.15 android14-5.15 android14-6.1 android15-6.6 android16-6.12 android17-6.18"
else
    KMIS=$1
fi

# Some patch is required to use separate build dir when building for android16-6.12, see:
# https://github.com/5ec1cff/ddk#local-%E6%A8%A1%E5%BC%8F%E6%9E%84%E5%BB%BA%E9%80%82%E7%94%A8%E4%BA%8E%E5%A4%9A%E4%B8%AA-target-%E7%89%88%E6%9C%AC%E7%9A%84%E5%86%85%E6%A0%B8%E6%A8%A1%E5%9D%97

if [ -e .ddk-version.bak ]; then
    echo 'Refusing to overwrite an existing .ddk-version.bak' >&2
    exit 1
fi
restore_ddk_version=false
if [ -e .ddk-version ]; then
    mv .ddk-version .ddk-version.bak
    restore_ddk_version=true
fi
restore_version() {
    if "$restore_ddk_version"; then
        mv .ddk-version.bak .ddk-version
    fi
}
trap restore_version EXIT

failed=false
outputs=()

for kmi in $KMIS; do
    echo "========== Building $kmi =========="
    ODIR="$(realpath .)/out/$kmi"
    OUTPUT="kernelsu-${kmi}.ko"
    # Neither a prior public output nor an old DDK module can satisfy this run.
    rm -f -- "$OUTPUT" "$ODIR/kernelsu.ko"
    if ddk build "$kmi" "ODIR=$ODIR" -e CONFIG_KSU=m; then
        if [ -f "$ODIR/kernelsu.ko" ]; then
            llvm-strip -d "$ODIR/kernelsu.ko"
            bash "$(dirname "$0")/../.github/scripts/check-lkm-version.sh" "$ODIR/kernelsu.ko"
            cp "$ODIR/kernelsu.ko" "$OUTPUT"
            outputs+=("$OUTPUT")
            echo "✓ Built $OUTPUT"
        else
            echo "✗ Build produced no module for $kmi" >&2
            failed=true
        fi
    else
        echo "✗ Build failed for $kmi" >&2
        failed=true
    fi
    echo ""
done

echo "========== Final output =========="
if [ "${#outputs[@]}" -gt 0 ]; then
    ls -l -- "${outputs[@]}"
fi
if "$failed"; then
    exit 1
fi
