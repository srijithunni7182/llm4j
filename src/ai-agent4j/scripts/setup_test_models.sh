#!/bin/bash
set -e

MODELS_DIR="src/test/resources/models"
mkdir -p "$MODELS_DIR/onnx_minilm"
mkdir -p "$MODELS_DIR/onnx_bge"

echo "Downloading ONNX model 1 (all-MiniLM-L6-v2)..."
curl -L "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx" -o "$MODELS_DIR/onnx_minilm/model.onnx"
curl -L "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json" -o "$MODELS_DIR/onnx_minilm/tokenizer.json"

echo "Downloading ONNX model 2 (bge-small-en-v1.5)..."
curl -L "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx" -o "$MODELS_DIR/onnx_bge/model.onnx"
curl -L "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/tokenizer.json" -o "$MODELS_DIR/onnx_bge/tokenizer.json"

echo "Setup complete."
