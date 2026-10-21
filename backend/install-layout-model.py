"""Install the Apache-2.0 Docling Heron export at an immutable revision."""
import hashlib
from pathlib import Path
import sys
import urllib.request

revision = '40bde044036bb181c130ddf6c51792187268748f'
files = {
    'model.onnx': '59c81a3a2923042d85034ffc487f8f47e4854117e879aef89b2b9f728fb4922a',
    'config.json': 'c6e67b6cdf64fa245779d0409bb994aa96e8c1790e15ec07a65843628e232180',
    'preprocessor_config.json': '54d086cf0d7d371f7fac36e5d3a0dae31e211298affc816106cc376506799d58',
}
directory = Path(sys.argv[1])
directory.mkdir(parents=True, exist_ok=True)
for name, checksum in files.items():
    url = f'https://huggingface.co/docling-project/docling-layout-heron-onnx/resolve/{revision}/{name}'
    target = directory / name
    urllib.request.urlretrieve(url, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
        target.unlink()
        raise ValueError(f'Unexpected model artifact checksum: {name}')
