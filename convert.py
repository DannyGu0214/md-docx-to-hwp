#!/usr/bin/env python3
"""Command-line interface for local Markdown/DOCX to binary HWP conversion."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "vendor/python"))


def java_home():
    candidates = [os.environ.get("HWP_CONVERTER_JAVA_HOME"), os.environ.get("JAVA_HOME"),
                  "/Users/dannygu/rea-workbench/tools/jdk-21.0.12.1+1/Contents/Home"]
    for candidate in candidates:
        if candidate and (Path(candidate) / "bin/javac").is_file():
            return Path(candidate)
    command = shutil.which("javac")
    if command:
        return Path(command).resolve().parents[1]
    raise RuntimeError("JDK 17 이상이 필요해요. HWP_CONVERTER_JAVA_HOME을 설정하세요.")


def bridge_command():
    home = java_home()
    source = ROOT / "java/HwpWriterBridge.java"
    classes = ROOT / "build/classes"
    jars = sorted((ROOT / "vendor").glob("*.jar"))
    if not jars:
        raise RuntimeError("HWP 작성 라이브러리가 없어요. vendor/를 확인하세요.")
    classpath = os.pathsep.join(str(j) for j in jars)
    target = classes / "HwpWriterBridge.class"
    if not target.exists() or target.stat().st_mtime < max(source.stat().st_mtime, *(j.stat().st_mtime for j in jars)):
        classes.mkdir(parents=True, exist_ok=True)
        compile_result = subprocess.run(
            [str(home / "bin/javac"), "-encoding", "UTF-8", "-Xlint:all", "-cp", classpath,
             "-d", str(classes), str(source)], capture_output=True, text=True, timeout=60)
        if compile_result.returncode:
            raise RuntimeError("HWP 작성기 컴파일 실패:\n" + compile_result.stderr)
    return [str(home / "bin/java"), "-Xmx1g", "-cp", str(classes) + os.pathsep + classpath, "HwpWriterBridge"]


def inspect_hwp(path):
    result = subprocess.run(bridge_command() + ["--inspect", str(Path(path).resolve())],
                            capture_output=True, text=True, encoding="utf-8", timeout=120)
    if result.returncode:
        raise RuntimeError("HWP 검증 실패:\n" + result.stderr)
    return json.loads(result.stdout)


def convert(source, output=None, *, force=False, strict=False, reference_doc=None):
    from converter.inputs import parse_input
    source = Path(source).expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"입력 파일이 없어요: {source}")
    if source.suffix.lower() not in (".md", ".markdown", ".docx"):
        raise ValueError("입력 형식은 .md, .markdown, .docx만 지원해요.")
    if reference_doc is not None:
        reference_doc = Path(reference_doc).expanduser().resolve()
        if not reference_doc.is_file() or reference_doc.suffix.lower() != ".hwp":
            raise ValueError("기준 문서는 존재하는 .hwp 파일이어야 해요.")
    output = Path(output).expanduser().absolute() if output else source.with_suffix(".hwp")
    if output.suffix.lower() != ".hwp":
        raise ValueError("출력 파일의 확장자는 .hwp여야 해요.")
    if output.exists() and not force:
        raise FileExistsError(f"출력 파일이 이미 있어요: {output} (--force로 덮어쓰기)")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hwp-convert-", dir=output.parent) as temp:
        work = Path(temp)
        model = parse_input(source, work / "assets")
        if reference_doc is not None:
            model["reference_doc"] = str(reference_doc)
        if strict and model["warnings"]:
            raise ValueError("지원 범위 경고가 있어 변환을 중단했어요:\n" + "\n".join(model["warnings"]))
        intermediate = work / "document.json"
        intermediate.write_text(json.dumps(model, ensure_ascii=False), encoding="utf-8")
        generated = work / "document.hwp"
        result = subprocess.run(bridge_command() + [str(intermediate), str(generated)],
                                capture_output=True, text=True, encoding="utf-8", timeout=120)
        if result.returncode:
            raise RuntimeError("HWP 작성 실패:\n" + result.stderr)
        if generated.read_bytes()[:8] != bytes.fromhex("d0cf11e0a1b11ae1"):
            raise RuntimeError("출력 파일이 HWP OLE 컨테이너 형식이 아니에요.")
        report = json.loads(result.stdout)
        report["warnings"] = model["warnings"] + report.get("warnings", [])
        if strict and report["warnings"]:
            raise ValueError("작성 경고가 있어 결과를 저장하지 않았어요.")
        if force:
            os.replace(generated, output)
        else:
            # Atomic, no-clobber publication, including a concurrently created file.
            os.link(generated, output)
        report.update(input=str(source), output=str(output), bytes=output.stat().st_size)
        return report


def main():
    parser = argparse.ArgumentParser(description="Markdown·DOCX → 실제 HWP 5 변환기")
    parser.add_argument("input", type=Path)
    parser.add_argument("-o", "--output", type=Path)
    parser.add_argument("--force", action="store_true", help="기존 출력 파일 덮어쓰기")
    parser.add_argument("--strict", action="store_true", help="지원 범위 경고가 있으면 저장하지 않기")
    parser.add_argument("--json", action="store_true", help="변환 결과를 JSON으로 출력")
    parser.add_argument("--reference-doc", type=Path, help="스타일·용지 설정을 가져올 HWP 기준 문서")
    args = parser.parse_args()
    try:
        result = convert(args.input, args.output, force=args.force, strict=args.strict,
                         reference_doc=args.reference_doc)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, ImportError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"변환 완료: {result['output']} ({result['bytes']:,} bytes)")
        print(f"문단 {result['paragraphs']} · 표 {result['tables']} · 이미지 {result['images']}")
        for warning in result["warnings"]:
            print(f"경고: {warning}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
