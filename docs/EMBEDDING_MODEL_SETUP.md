# Embedding Model Setup

Recurra uses local embeddings for semantic similarity matching. This guide explains how to set up the e5-small-v2 model.

## Quick Start

### Option 1: Download Pre-converted ONNX Model (Recommended)

```bash
# Create models directory
mkdir -p src/main/resources/models

# Download e5-small-v2 ONNX model (if available from HuggingFace)
# Note: As of now, you may need to convert the model yourself (see Option 2)

# If pre-converted model is available:
cd src/main/resources/models
wget https://huggingface.co/intfloat/e5-small-v2/resolve/main/onnx/model.onnx -O e5-small-v2.onnx
```

### Option 2: Convert PyTorch Model to ONNX

If pre-converted model is not available, convert it yourself:

```bash
# Install required Python packages
pip install torch transformers onnx onnxruntime optimum

# Convert model
python scripts/convert_model_to_onnx.py
```

**Conversion Script** (`scripts/convert_model_to_onnx.py`):

```python
from transformers import AutoTokenizer, AutoModel
import torch
import os

# Load model and tokenizer
model_name = "intfloat/e5-small-v2"
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModel.from_pretrained(model_name)

# Set model to eval mode
model.eval()

# Create dummy input
dummy_input = {
    "input_ids": torch.randint(0, 1000, (1, 128)),
    "attention_mask": torch.ones(1, 128, dtype=torch.long)
}

# Export to ONNX
output_path = "src/main/resources/models/e5-small-v2.onnx"
os.makedirs(os.path.dirname(output_path), exist_ok=True)

torch.onnx.export(
    model,
    (dummy_input["input_ids"], dummy_input["attention_mask"]),
    output_path,
    input_names=["input_ids", "attention_mask"],
    output_names=["last_hidden_state"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "sequence"},
        "attention_mask": {0: "batch", 1: "sequence"},
        "last_hidden_state": {0: "batch", 1: "sequence"}
    },
    opset_version=14
)

print(f"Model exported to {output_path}")
print(f"Model size: {os.path.getsize(output_path) / 1024 / 1024:.2f} MB")
```

### Option 3: Use Optimum for Better Conversion

```bash
# Install optimum
pip install optimum[onnxruntime]

# Convert using optimum
optimum-cli export onnx \
    --model intfloat/e5-small-v2 \
    --task feature-extraction \
    src/main/resources/models/
```

## Model Specifications

- **Model**: intfloat/e5-small-v2
- **Dimensions**: 384
- **Parameters**: ~33M
- **ONNX Size**: ~120 MB
- **HuggingFace**: https://huggingface.co/intfloat/e5-small-v2

## Configuration

Update `application.yml`:

```yaml
recurra:
  embeddings:
    provider: local  # Use local ONNX model
    model-path: src/main/resources/models/e5-small-v2.onnx
    dimensions: 384
```

## Verification

Test that the model loads correctly:

```bash
# Start Recurra
./mvnw spring-boot:run

# Check logs for:
# "ONNX embedding model loaded successfully"
# "Model inputs: [input_ids, attention_mask]"
# "Model outputs: [last_hidden_state]"
```

## Alternative: Remote Embeddings (OpenAI)

If you don't want to run local embeddings:

```yaml
recurra:
  embeddings:
    provider: remote
    api-key: ${OPENAI_API_KEY}
    model: text-embedding-3-small
    dimensions: 1536
```

**Trade-offs**:
- ✅ No local model file needed
- ✅ Higher quality embeddings
- ❌ API costs ($0.02 per 1M tokens)
- ❌ Requires internet connectivity
- ❌ Slower (network latency)
- ❌ Privacy concerns (data sent to OpenAI)

## Troubleshooting

### Error: "Failed to load ONNX model"

1. Check model file exists:
   ```bash
   ls -lh src/main/resources/models/e5-small-v2.onnx
   ```

2. Verify file is valid ONNX format:
   ```bash
   python -c "import onnx; model = onnx.load('src/main/resources/models/e5-small-v2.onnx'); print('Valid ONNX model')"
   ```

3. Check Java heap size (model needs ~500MB RAM):
   ```bash
   export JAVA_OPTS="-Xms512m -Xmx2g"
   ```

### Model loads but embeddings are zero

This usually means tokenization failed. The current implementation uses simplified tokenization. For production:

1. Use HuggingFace tokenizers Java library
2. Or pre-tokenize on the Python side

### Performance Issues

If embedding generation is slow (> 50ms):

1. Increase ONNX thread count:
   ```java
   sessionOptions.setIntraOpNumThreads(8); // more threads
   ```

2. Use GPU (requires ONNX Runtime GPU):
   ```java
   sessionOptions.addCUDA();
   ```

3. Batch embeddings when possible

## Performance Benchmarks

Expected performance on modern hardware (CPU):

- **Single embedding**: 30-50ms
- **Batch of 10**: 80-120ms
- **Throughput**: ~200 embeds/sec (single-threaded)
- **Memory**: ~500MB loaded model

## Next Steps

Once the model is set up:

1. Restart Recurra
2. Make a request - embedding will be generated automatically
3. Check Postgres for populated `embedding` column
4. Template matching will now use semantic similarity!

```bash
# Check embeddings in database
psql -h localhost -U recurra -d recurra -c "SELECT id, embedding IS NOT NULL AS has_embedding FROM cache_entries LIMIT 5;"
```
