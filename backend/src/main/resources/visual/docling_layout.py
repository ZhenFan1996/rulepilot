"""Raw Docling Heron picture proposals; chapter composition owns semantic selection.

Use the published ONNX preprocessor and the Docling detection engine's default
score threshold (0.3), without the layout postprocessor's second 0.5 picture
filter or overlap suppression. Neither confidence nor overlap identifies a
complete teaching illustration. No rules, labels, or padding are inferred here.
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pypdfium2 as pdfium
from PIL import Image


def extract(pdf_path, model_directory):
    directory = Path(model_directory)
    config = json.loads((directory / 'config.json').read_text())
    preprocessing = json.loads((directory / 'preprocessor_config.json').read_text())
    if preprocessing['do_rescale'] or preprocessing['do_normalize'] or preprocessing['do_pad']:
        raise ValueError('unsupported Heron ONNX preprocessing contract')
    size = preprocessing['size']
    options = ort.SessionOptions()
    # One indexing subprocess owns the CPU and memory admission permit in Java.
    options.intra_op_num_threads = 1
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    session = ort.InferenceSession(str(directory / 'model.onnx'), options, providers=['CPUExecutionProvider'])
    regions = []
    with pdfium.PdfDocument(pdf_path) as document:
        for number in range(len(document)):
            page = document[number]
            try:
                bitmap = page.render(scale=1)
                try:
                    with bitmap.to_pil().convert('RGB') as image:
                        pixels = np.asarray(image.resize((size['width'], size['height']),
                                            Image.Resampling(preprocessing['resample']))).transpose(2, 0, 1)[None]
                        labels, boxes, scores = session.run(None, {
                            'images': pixels,
                            'orig_target_sizes': np.array([[image.width, image.height]], dtype=np.int64),
                        })
                        for label, box, score in zip(labels[0], boxes[0], scores[0]):
                            if score < 0.3 or config['id2label'][str(int(label))].lower() != 'picture':
                                continue
                            if not np.isfinite(box).all():
                                raise ValueError('layout model returned non-finite geometry')
                            left = max(0, math.floor(float(box[0]) / image.width * 1000))
                            top = max(0, math.floor(float(box[1]) / image.height * 1000))
                            right = min(1000, math.ceil(float(box[2]) / image.width * 1000))
                            bottom = min(1000, math.ceil(float(box[3]) / image.height * 1000))
                            # Existing normalized region storage contract, not a teaching image count limit.
                            if right - left < 20 or bottom - top < 20 or (left, top, right, bottom) == (0, 0, 1000, 1000):
                                continue
                            region = dict(pageNumber=number + 1, kind='DOCLING_RAW_PICTURE',
                                          x=left, y=top, width=right-left, height=bottom-top)
                            if region not in regions:
                                regions.append(region)
                finally:
                    bitmap.close()
            finally:
                page.close()
        return dict(source='docling-heron-raw-v1', pageCount=len(document), regions=regions)


if __name__ == '__main__':
    result = extract(sys.argv[1], sys.argv[2])
    Path(sys.argv[3]).write_text(json.dumps(result))
