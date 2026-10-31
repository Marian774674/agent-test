#!/usr/bin/env bash
# 下载 BGE-small-zh-v1.5 的 ONNX 模型与词表到 ./models/bge-small-zh-v1.5/。
# 模型文件不入库（见仓库根 .gitignore 的 QQMessageAssistantJava/models/）。
set -euo pipefail

BASE_URL="https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main"
MODEL_DIR="$(dirname "$0")/../models/bge-small-zh-v1.5"

MODEL_FILE="onnx/model.onnx"
MODEL_SIZE=94851877
VOCAB_FILE="vocab.txt"
VOCAB_SIZE=109540

mkdir -p "$MODEL_DIR"

download() {
    local remote="$1" local_name="$2" expected="$3"
    local target="$MODEL_DIR/$local_name"

    if [ -f "$target" ]; then
        local actual
        actual=$(wc -c < "$target" | tr -d '[:space:]')
        if [ "$actual" = "$expected" ]; then
            echo "已存在且大小正确，跳过：$target ($actual bytes)"
            return 0
        fi
        echo "大小不符（$actual != $expected），重新下载：$target" >&2
        rm -f "$target"
    fi

    echo "下载 $BASE_URL/$remote -> $target"
    curl -L --fail --retry 3 --retry-delay 2 -o "$target.part" "$BASE_URL/$remote"
    mv "$target.part" "$target"

    local actual
    actual=$(wc -c < "$target" | tr -d '[:space:]')
    if [ "$actual" != "$expected" ]; then
        echo "文件大小校验失败：$target 实际 $actual 字节，期望 $expected 字节" >&2
        exit 1
    fi
    echo "完成：$target ($actual bytes)"
}

download "$MODEL_FILE" "model.onnx" "$MODEL_SIZE"
download "$VOCAB_FILE" "vocab.txt" "$VOCAB_SIZE"

echo "模型已就绪：$MODEL_DIR"
