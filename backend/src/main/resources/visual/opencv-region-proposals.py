#!/usr/bin/env python3
"""Emit bounded visual-region geometry; never persist or return recognized page text."""

from __future__ import annotations

import argparse
import json
import math
import os
import resource
import sys


MAX_INPUT_BYTES = 6 * 1024 * 1024
MAX_PAGE_PIXELS = 12_000_000
MAX_WORKING_PIXELS = 1_500_000
MAX_WORKING_LONG_EDGE = 1_600
MAX_CONTOURS_SCANNED_PER_ROUND = 4_096
MAX_PROPOSALS_PER_ROUND = 512
DEFAULT_WORKING_ADDRESS_SPACE_BYTES = 384 * 1024 * 1024
MAX_WORKING_ADDRESS_SPACE_BYTES = 512 * 1024 * 1024
MAX_CPU_LIMIT_SECONDS = 31


def bounded_environment_integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, str(default))
    if not raw.isascii() or not raw.isdecimal():
        raise ValueError(f"{name} is outside the bounded contract")
    value = int(raw)
    if value < minimum or value > maximum:
        raise ValueError(f"{name} is outside the bounded contract")
    return value


def lower_process_limit(kind: int, requested: int) -> int:
    _, hard = resource.getrlimit(kind)
    bounded = requested if hard == resource.RLIM_INFINITY else min(requested, hard)
    resource.setrlimit(kind, (bounded, bounded))
    return bounded


def apply_cpu_limit() -> None:
    cpu_limit = bounded_environment_integer(
        "RULEPILOT_OPENCV_CPU_LIMIT_SECONDS", 3, 1, MAX_CPU_LIMIT_SECONDS
    )
    lower_process_limit(resource.RLIMIT_CPU, cpu_limit)


def linux_virtual_memory_bytes() -> int:
    try:
        with open("/proc/self/statm", encoding="ascii") as process_memory:
            statm = process_memory.read().split()
        pages = int(statm[0])
        page_size = os.sysconf("SC_PAGE_SIZE")
    except (OSError, ValueError, IndexError) as failure:
        raise RuntimeError("Linux process address-space accounting is unavailable") from failure
    if pages < 1 or page_size < 1:
        raise RuntimeError("Linux process address-space accounting is invalid")
    return pages * page_size


def apply_working_address_space_limit() -> tuple[int, int]:
    working_allowance = bounded_environment_integer(
        "RULEPILOT_OPENCV_WORKING_ADDRESS_SPACE_BYTES",
        DEFAULT_WORKING_ADDRESS_SPACE_BYTES,
        256 * 1024 * 1024,
        MAX_WORKING_ADDRESS_SPACE_BYTES,
    )
    baseline = linux_virtual_memory_bytes()
    requested = baseline + working_allowance
    applied = lower_process_limit(resource.RLIMIT_AS, requested)
    if applied != requested:
        raise MemoryError("OpenCV process cannot reserve its bounded working address space")
    return baseline, applied


# CPU time is independent of shared-library mappings and can be bounded before native imports.
apply_cpu_limit()
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"

import cv2  # noqa: E402
import numpy as np  # noqa: E402

cv2.setNumThreads(1)
if hasattr(cv2, "ocl"):
    cv2.ocl.setUseOpenCL(False)

# File-backed native libraries can reserve a large virtual range while using little resident memory. Bound new
# working address space after those immutable mappings exist, so the limit constrains page work instead of making a
# valid OpenCV runtime fail nondeterministically during import.
ADDRESS_SPACE_BASELINE_BYTES, ADDRESS_SPACE_LIMIT_BYTES = apply_working_address_space_limit()


def intersection_over_union(first: tuple[int, int, int, int], second: tuple[int, int, int, int]) -> float:
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    left = max(ax, bx)
    top = max(ay, by)
    right = min(ax + aw, bx + bw)
    bottom = min(ay + ah, by + bh)
    if right <= left or bottom <= top:
        return 0.0
    overlap = (right - left) * (bottom - top)
    return overlap / float(aw * ah + bw * bh - overlap)


def padded(box: tuple[int, int, int, int], page_width: int, page_height: int) -> tuple[int, int, int, int]:
    x, y, width, height = box
    padding = max(4, round(max(page_width, page_height) * 0.006))
    left = max(0, x - padding)
    top = max(0, y - padding)
    right = min(page_width, x + width + padding)
    bottom = min(page_height, y + height + padding)
    return left, top, right - left, bottom - top


def working_dimensions(width: int, height: int) -> tuple[int, int]:
    scale = min(
        1.0,
        MAX_WORKING_LONG_EDGE / max(width, height),
        math.sqrt(MAX_WORKING_PIXELS / (width * height)),
    )
    working_width = max(1, min(width, math.floor(width * scale)))
    working_height = max(1, min(height, math.floor(height * scale)))
    while working_width * working_height > MAX_WORKING_PIXELS:
        if working_width >= working_height and working_width > 1:
            working_width -= 1
        elif working_height > 1:
            working_height -= 1
        else:
            break
    return working_width, working_height


def bounded_working_image(source: np.ndarray) -> np.ndarray:
    height, width = source.shape[:2]
    target_width, target_height = working_dimensions(width, height)
    if (target_width, target_height) == (width, height):
        return source
    return cv2.resize(source, (target_width, target_height), interpolation=cv2.INTER_AREA)


def original_box(
    box: tuple[int, int, int, int],
    working_width: int,
    working_height: int,
    original_width: int,
    original_height: int,
) -> tuple[int, int, int, int]:
    x, y, width, height = box
    left = x * original_width // working_width
    top = y * original_height // working_height
    right = min(
        original_width,
        ((x + width) * original_width + working_width - 1) // working_width,
    )
    bottom = min(
        original_height,
        ((y + height) * original_height + working_height - 1) // working_height,
    )
    return left, top, max(1, right - left), max(1, bottom - top)


def candidate_boxes(
    source: np.ndarray,
    maximum: int,
    original_width: int | None = None,
    original_height: int | None = None,
    diagnostics: dict[str, object] | None = None,
) -> list[tuple[int, int, int, int]]:
    if maximum < 1 or maximum > 64:
        raise ValueError("maximum regions is outside the bounded contract")
    source_height, source_width = source.shape[:2]
    original_width = source_width if original_width is None else original_width
    original_height = source_height if original_height is None else original_height
    if original_width < 1 or original_height < 1:
        raise ValueError("page dimensions are outside the bounded contract")

    working = bounded_working_image(source)
    page_height, page_width = working.shape[:2]
    page_area = page_width * page_height
    long_edge = max(page_width, page_height)
    minimum_width = max(8, math.ceil(40 * page_width / original_width))
    minimum_height = max(8, math.ceil(40 * page_height / original_height))
    gray = cv2.cvtColor(working, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 60, 160)

    if diagnostics is not None:
        diagnostics.update(
            {
                "workingWidth": page_width,
                "workingHeight": page_height,
                "workingPixels": page_area,
                "rounds": [],
            }
        )

    proposals: list[tuple[int, int, int, int]] = []
    proposal_cap = min(MAX_PROPOSALS_PER_ROUND, max(64, maximum * 8))
    # Two geometry scales keep compact component diagrams and larger reference panels in the same typed candidate
    # space. This is candidate generation only; the vision model still decides whether a region supports a claim.
    for ratio, iterations in ((0.006, 1), (0.012, 2)):
        kernel_size = max(5, round(long_edge * ratio))
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (kernel_size, kernel_size))
        connected = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel, iterations=iterations)
        contours, _ = cv2.findContours(connected, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        scanned = 0
        retained = 0
        for contour in contours:
            if scanned >= MAX_CONTOURS_SCANNED_PER_ROUND or retained >= proposal_cap:
                break
            scanned += 1
            x, y, width, height = cv2.boundingRect(contour)
            area_ratio = width * height / page_area
            aspect = max(width / max(height, 1), height / max(width, 1))
            if (
                0.004 <= area_ratio <= 0.60
                and width >= minimum_width
                and height >= minimum_height
                and aspect <= 8
            ):
                proposals.append(padded((x, y, width, height), page_width, page_height))
                retained += 1
        if diagnostics is not None:
            diagnostics["rounds"].append(
                {
                    "contoursReturned": len(contours),
                    "contoursScanned": scanned,
                    "proposalsRetained": retained,
                }
            )
        # Do not overlap one round's native contour graph with the next round's allocations.
        del contours, connected, kernel

    proposals.sort(key=lambda box: (-(box[2] * box[3]), box[1], box[0]))
    selected: list[tuple[int, int, int, int]] = []
    for proposal in proposals:
        if any(intersection_over_union(proposal, existing) >= 0.86 for existing in selected):
            continue
        selected.append(proposal)
        if len(selected) >= maximum:
            break

    mapped: list[tuple[int, int, int, int]] = []
    seen: set[tuple[int, int, int, int]] = set()
    for proposal in selected:
        restored = original_box(
            proposal,
            page_width,
            page_height,
            original_width,
            original_height,
        )
        if restored not in seen:
            seen.add(restored)
            mapped.append(restored)
    return mapped


def reduced_decode_factor(width: int, height: int) -> int:
    for factor in (1, 2, 4, 8):
        reduced_width = (width + factor - 1) // factor
        reduced_height = (height + factor - 1) // factor
        if (
            reduced_width * reduced_height <= MAX_WORKING_PIXELS
            and max(reduced_width, reduced_height) <= MAX_WORKING_LONG_EDGE
        ):
            return factor
    return 8


def decode_working_image(encoded: bytes, original_width: int, original_height: int) -> np.ndarray:
    factor = reduced_decode_factor(original_width, original_height)
    flags = {
        1: cv2.IMREAD_COLOR,
        2: cv2.IMREAD_REDUCED_COLOR_2,
        4: cv2.IMREAD_REDUCED_COLOR_4,
        8: cv2.IMREAD_REDUCED_COLOR_8,
    }
    decoded = cv2.imdecode(np.frombuffer(encoded, dtype=np.uint8), flags[factor])
    if decoded is None:
        raise ValueError("page image could not be decoded")
    decoded_height, decoded_width = decoded.shape[:2]
    # OpenCV's reduced JPEG decoder rounds odd dimensions up, while its PNG decoder rounds them down.
    minimum_width = max(1, original_width // factor)
    minimum_height = max(1, original_height // factor)
    maximum_width = (original_width + factor - 1) // factor
    maximum_height = (original_height + factor - 1) // factor
    if not (
        minimum_width <= decoded_width <= maximum_width
        and minimum_height <= decoded_height <= maximum_height
    ):
        raise ValueError("decoded page image dimensions do not match the bounded contract")
    return bounded_working_image(decoded)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-regions", type=int, required=True)
    parser.add_argument("--page-width", type=int, required=True)
    parser.add_argument("--page-height", type=int, required=True)
    args = parser.parse_args()
    if args.max_regions < 1 or args.max_regions > 64:
        raise ValueError("max-regions is outside the bounded contract")
    if (
        args.page_width < 1
        or args.page_height < 1
        or args.page_width * args.page_height > MAX_PAGE_PIXELS
    ):
        raise ValueError("decoded page image is outside the bounded contract")

    encoded = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
    if not encoded or len(encoded) > MAX_INPUT_BYTES:
        raise ValueError("page image is outside the bounded contract")
    source = decode_working_image(encoded, args.page_width, args.page_height)
    regions = [
        {"x": x, "y": y, "width": box_width, "height": box_height}
        for x, y, box_width, box_height in candidate_boxes(
            source,
            args.max_regions,
            args.page_width,
            args.page_height,
        )
    ]
    sys.stdout.write(
        json.dumps(
            {
                "schemaVersion": 1,
                "width": args.page_width,
                "height": args.page_height,
                "regions": regions,
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
