#!/usr/bin/env python3
"""Runtime-image smoke for the exact OpenCV candidate generator shipped by RulePilot."""

from __future__ import annotations

import importlib.util
import pathlib
import resource
import subprocess
import sys

sys.dont_write_bytecode = True

def load_production_module(path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("rulepilot_opencv_regions", path)
    if spec is None or spec.loader is None:
        raise AssertionError("production region proposal module could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def assert_valid(boxes: list[tuple[int, int, int, int]], width: int, height: int) -> None:
    if len(boxes) != len(set(boxes)) or len(boxes) > 32:
        raise AssertionError("region proposals are not uniquely bounded")
    for x, y, box_width, box_height in boxes:
        if x < 0 or y < 0 or box_width < 1 or box_height < 1:
            raise AssertionError("region proposal has invalid dimensions")
        if x + box_width > width or y + box_height > height:
            raise AssertionError("region proposal escapes the page")


def main() -> None:
    if len(sys.argv) != 2:
        raise AssertionError("production script path is required")
    production = load_production_module(pathlib.Path(sys.argv[1]))
    cv2 = production.cv2
    np = production.np

    address_space_limit, _ = resource.getrlimit(resource.RLIMIT_AS)
    if address_space_limit != production.ADDRESS_SPACE_LIMIT_BYTES:
        raise AssertionError("the native proposal process did not retain its applied address-space limit")
    working_address_space = address_space_limit - production.ADDRESS_SPACE_BASELINE_BYTES
    if working_address_space != production.DEFAULT_WORKING_ADDRESS_SPACE_BYTES:
        raise AssertionError("the native proposal process escaped its bounded working address space")

    original_proc_reader = production.proc_virtual_memory_bytes
    original_ps_runner = production.subprocess.run
    original_path_is_file = production.os.path.isfile
    try:
        def unavailable_proc_reader() -> int:
            raise RuntimeError("synthetic non-Linux host")

        production.proc_virtual_memory_bytes = unavailable_proc_reader
        production.os.path.isfile = lambda candidate: candidate == "/bin/ps"
        production.subprocess.run = lambda *args, **kwargs: subprocess.CompletedProcess(
            args=args[0], returncode=0, stdout="123456\n"
        )
        if production.current_virtual_memory_bytes() != 123_456 * 1_024:
            raise AssertionError("portable process address-space accounting changed units")
    finally:
        production.proc_virtual_memory_bytes = original_proc_reader
        production.subprocess.run = original_ps_runner
        production.os.path.isfile = original_path_is_file

    width, height = 1_200, 800
    blank = np.full((height, width, 3), 255, dtype=np.uint8)
    blank_boxes = production.candidate_boxes(blank, 32)
    if blank_boxes:
        raise AssertionError("a blank page must not invent a visual region")

    diagram = blank.copy()
    cv2.rectangle(diagram, (170, 150), (1_030, 650), (0, 0, 0), 8)
    cv2.circle(diagram, (390, 390), 100, (0, 0, 0), 8)
    cv2.circle(diagram, (810, 390), 100, (0, 0, 0), 8)
    cv2.arrowedLine(diagram, (500, 390), (700, 390), (0, 0, 0), 10)
    diagram_boxes = production.candidate_boxes(diagram, 32)
    if not diagram_boxes:
        raise AssertionError("a bounded synthetic diagram must produce a candidate")
    assert_valid(diagram_boxes, width, height)

    near_limit_width, near_limit_height = 4_000, 3_000
    working_width, working_height = production.working_dimensions(
        near_limit_width, near_limit_height
    )
    if working_width * working_height > production.MAX_WORKING_PIXELS:
        raise AssertionError("working image exceeds the pixel budget")
    if max(working_width, working_height) > production.MAX_WORKING_LONG_EDGE:
        raise AssertionError("working image exceeds the long-edge budget")

    texture = np.full((near_limit_height, near_limit_width, 3), 255, dtype=np.uint8)
    for y in range(8, near_limit_height - 8, 28):
        for x in range(8, near_limit_width - 8, 28):
            cv2.rectangle(texture, (x, y), (x + 10, y + 10), (0, 0, 0), 2)

    encoded_ok, encoded_texture = cv2.imencode(
        ".jpg", texture, [cv2.IMWRITE_JPEG_QUALITY, 85]
    )
    if not encoded_ok or encoded_texture.nbytes > production.MAX_INPUT_BYTES:
        raise AssertionError("near-limit texture fixture does not fit the encoded input contract")
    decoded_working = production.decode_working_image(
        encoded_texture.tobytes(), near_limit_width, near_limit_height
    )
    decoded_height, decoded_width = decoded_working.shape[:2]
    if decoded_width * decoded_height > production.MAX_WORKING_PIXELS:
        raise AssertionError("reduced decode escaped the working pixel budget")
    if max(decoded_width, decoded_height) > production.MAX_WORKING_LONG_EDGE:
        raise AssertionError("reduced decode escaped the working long-edge budget")

    odd_height, odd_width = near_limit_height - 1, near_limit_width - 1
    encoded_ok, odd_png = cv2.imencode(".png", texture[:odd_height, :odd_width])
    if not encoded_ok or odd_png.nbytes > production.MAX_INPUT_BYTES:
        raise AssertionError("odd PNG fixture does not fit the encoded input contract")
    odd_decoded = production.decode_working_image(odd_png.tobytes(), odd_width, odd_height)
    odd_decoded_height, odd_decoded_width = odd_decoded.shape[:2]
    if odd_decoded_width * odd_decoded_height > production.MAX_WORKING_PIXELS:
        raise AssertionError("odd PNG reduced decode escaped the working pixel budget")
    if max(odd_decoded_width, odd_decoded_height) > production.MAX_WORKING_LONG_EDGE:
        raise AssertionError("odd PNG reduced decode escaped the working long-edge budget")

    diagnostics: dict[str, object] = {}
    texture_boxes = production.candidate_boxes(
        texture,
        32,
        near_limit_width,
        near_limit_height,
        diagnostics,
    )
    assert_valid(texture_boxes, near_limit_width, near_limit_height)
    if diagnostics["workingPixels"] > production.MAX_WORKING_PIXELS:
        raise AssertionError("high-texture work escaped the pixel budget")
    for round_diagnostic in diagnostics["rounds"]:
        if round_diagnostic["contoursScanned"] > production.MAX_CONTOURS_SCANNED_PER_ROUND:
            raise AssertionError("a contour round escaped its enumeration cap")
        if round_diagnostic["proposalsRetained"] > production.MAX_PROPOSALS_PER_ROUND:
            raise AssertionError("a proposal round escaped its enumeration cap")

    restored = production.original_box((100, 50, 200, 100), 1_000, 750, 4_000, 3_000)
    if restored != (400, 200, 800, 400):
        raise AssertionError("working-image coordinates were not restored to the original page")

    peak_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_rss_bytes = peak_rss if sys.platform == "darwin" else peak_rss * 1_024
    if peak_rss_bytes > production.DEFAULT_WORKING_ADDRESS_SPACE_BYTES:
        raise AssertionError("high-texture proposal work exceeded its resident-memory smoke boundary")
    print(
        "opencv smoke passed: "
        f"peak-rss-bytes={peak_rss_bytes} "
        f"address-space-baseline-bytes={production.ADDRESS_SPACE_BASELINE_BYTES} "
        f"working-address-space-bytes={working_address_space} "
        f"working-pixels={diagnostics['workingPixels']}"
    )


if __name__ == "__main__":
    main()
