#!/usr/bin/env python3
"""Build official ORT 1.27.1 JNI against unchanged sherpa-onnx native cores.

No second ORT core is packaged, no binary is patched, and Java descriptors and
JNI exports must match the pinned official Java AAR. Stdlib-only host tooling.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import urllib.request
import zipfile

COMMIT = "df2ba1cf8108aa63627cf4cdf8f807880b938616"
SOURCE_SHA256 = "2bc333947de2983a4c2d711af4c56c6286565ce7c1202fdfbda56c3431b5d63d"
JAVA_AAR_SHA256 = "077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb"
SHERPA_AAR_SHA256 = "0012d9a28f15bd6fb966b62b70a75da3990512fdccce28b83098248ce4be1698"
NDK_REVISION = "27.1.12297006"
TARGETS = {"arm64-v8a": "aarch64-linux-android27", "armeabi-v7a": "armv7a-linux-androideabi27",
           "x86": "i686-linux-android27", "x86_64": "x86_64-linux-android27"}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(path: Path, expected: str) -> None:
    if digest(path) != expected:
        raise ValueError(f"embedding JNI source/input integrity mismatch: {path.name}")


def fetch_source(cache: Path) -> Path:
    archive = cache / f"onnxruntime-{COMMIT}.zip"
    cache.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        partial = archive.with_suffix(".partial")
        try:
            with urllib.request.urlopen(f"https://codeload.github.com/microsoft/onnxruntime/zip/{COMMIT}", timeout=120) as response, partial.open("wb") as out:
                while block := response.read(1024 * 1024):
                    out.write(block)
            verify(partial, SOURCE_SHA256)
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    verify(archive, SOURCE_SHA256)
    return archive


def selected_source(name: str) -> bool:
    return (name.startswith("java/src/main/java/") and name.endswith(".java") or
            name.startswith("java/src/main/android/") and name.endswith(".java") or
            name.startswith("java/src/main/native/") and name.endswith((".c", ".h")) or
            name.startswith("include/") and name.endswith(".h") or
            name.startswith("orttraining/orttraining/training_api/include/") and name.endswith(".h") or
            name == "LICENSE")


def extract_sources(archive: Path, directory: Path) -> dict:
    inventory = {}
    prefix = f"onnxruntime-{COMMIT}/"
    with zipfile.ZipFile(archive) as zip_file:
        for entry in zip_file.infolist():
            if not entry.filename.startswith(prefix):
                raise ValueError("unexpected upstream source archive root")
            relative = entry.filename[len(prefix):]
            path = PurePosixPath(relative)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("unsafe upstream source path")
            if selected_source(relative):
                payload = zip_file.read(entry)
                target = directory.joinpath(*path.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(payload)
                inventory[relative] = hashlib.sha256(payload).hexdigest()
    if not inventory or "LICENSE" not in inventory:
        raise ValueError("incomplete upstream source inventory")
    return inventory


def run(command: list[str | Path]) -> str:
    try:
        result = subprocess.run([os.fspath(item) for item in command], check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as failure:
        raise ValueError(f"embedding JNI build command failed: {Path(command[0]).name}\n{failure.stderr[:8000]}") from failure
    return result.stdout


def canonical_descriptors(value: str) -> str:
    # javac 8 emits extra non-native access$ bridges that Android's official
    # build compiler omits. They are not JNI entrypoints or source API methods.
    # All actual source field/method/constructor and native descriptors remain.
    lines = value.splitlines()
    result = []
    skip_descriptor = False
    for line in lines:
        if re.match(r"\s+static (?!.*\bnative\b).*\baccess\$[0-9]+\(", line):
            skip_descriptor = True
            continue
        if skip_descriptor and line.strip().startswith("descriptor:"):
            skip_descriptor = False
            continue
        if line.strip():
            result.append(line)
    return "\n".join(result)


def native_exports(readelf_output: str) -> set[str]:
    return set(re.findall(r"\b(?:Java_[A-Za-z0-9_]+|JNI_OnLoad)\b", readelf_output))


def check_linkage(readelf_output: str, reference_exports: set[str]) -> None:
    if native_exports(readelf_output) != reference_exports:
        raise ValueError("rebuilt JNI exports differ from official Java binding")
    for symbol in ("OrtGetApiBase", "OrtSessionOptionsAppendExecutionProvider_CPU", "OrtSessionOptionsAppendExecutionProvider_Nnapi"):
        if f"{symbol}@VERS_1.27.1" not in readelf_output:
            raise ValueError("JNI must link the unchanged sherpa ORT 1.27.1 symbol versions")
    if "VERS_1.27.0" in readelf_output or "[libonnxruntime.so]" not in readelf_output:
        raise ValueError("JNI dependency/version contract mismatch")
    loads = [line for line in readelf_output.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(line.split()[-1], 16) < 16384 for line in loads):
        raise ValueError("JNI load segments do not support 16 KiB pages")


def build(args) -> dict:
    verify(args.java_aar, JAVA_AAR_SHA256)
    verify(args.sherpa_aar, SHERPA_AAR_SHA256)
    properties = (args.ndk / "source.properties").read_text(encoding="utf-8")
    if not re.search(r"Pkg.Revision\s*=\s*" + re.escape(NDK_REVISION) + r"\b", properties):
        raise ValueError("embedding JNI requires pinned Android NDK 27.1.12297006")
    output = args.output.resolve()
    scratch = output / "source-build"
    scratch.mkdir(parents=True, exist_ok=True)
    source = scratch / "source"
    archive = fetch_source(args.cache.resolve())
    source_inventory = extract_sources(archive, source)
    generated = scratch / "headers"
    compiled = scratch / "classes"
    generated.mkdir(exist_ok=True)
    compiled.mkdir(exist_ok=True)
    java_home = args.java_home.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    javac, javap = java_home / "bin" / ("javac" + suffix), java_home / "bin" / ("javap" + suffix)
    sources = sorted(source.joinpath("java/src/main/java").rglob("*.java")) + sorted(source.joinpath("java/src/main/android").rglob("*.java"))
    run([javac, "--release", "8", "-h", generated, "-d", compiled, *sources])
    classes_jar = output / "classes.jar"
    with zipfile.ZipFile(args.java_aar) as aar:
        classes_jar.write_bytes(aar.read("classes.jar"))
        with zipfile.ZipFile(classes_jar) as jar:
            # BuildConfig is generated by Android, not an upstream JNI class.
            class_names = sorted(name[:-6].replace("/", ".") for name in jar.namelist() if name.startswith("ai/onnxruntime/") and name.endswith(".class") and name != "ai/onnxruntime/BuildConfig.class")
        original_java = canonical_descriptors(run([javap, "-p", "-s", "-classpath", classes_jar, *class_names]))
        built_java = canonical_descriptors(run([javap, "-p", "-s", "-classpath", compiled, *class_names]))
        if original_java != built_java:
            difference = "\n".join(list(difflib.unified_diff(original_java.splitlines(), built_java.splitlines()))[:80])
            raise ValueError("official 1.27.0 Java and pinned 1.27.1 source descriptors differ\n" + difference)
        references = {abi: aar.read(f"jni/{abi}/libonnxruntime4j_jni.so") for abi in TARGETS}
    (generated / "onnxruntime_config.h").write_text('#pragma once\n#define ORT_VERSION "1.27.1"\n', encoding="utf-8")
    prebuilt = args.ndk / "toolchains/llvm/prebuilt" / ("windows-x86_64" if os.name == "nt" else "linux-x86_64")
    clang, readelf, strip = (prebuilt / "bin" / (name + suffix) for name in ("clang", "llvm-readelf", "llvm-strip"))
    native = source / "java/src/main/native"
    report = {"schema_version": 1, "source_commit": COMMIT, "source_archive_sha256": SOURCE_SHA256,
              "source_files_sha256": source_inventory, "ndk_revision": NDK_REVISION,
              "java_aar_sha256": JAVA_AAR_SHA256, "sherpa_aar_sha256": SHERPA_AAR_SHA256,
              "java_descriptors_sha256": hashlib.sha256(original_java.encode()).hexdigest(),
              "official_classes_jar_sha256": digest(classes_jar),
              "javac_version": run([javac, "--version"]).strip(),
              "clang_version": run([clang, "--version"]).splitlines()[0],
              "java_source_binary_descriptors_equivalent": True, "second_core_packaged": False, "abis": {}}
    with zipfile.ZipFile(args.sherpa_aar) as sherpa:
        for abi, target in TARGETS.items():
            directory = scratch / abi
            directory.mkdir(exist_ok=True)
            core = directory / "libonnxruntime.so"
            core.write_bytes(sherpa.read(f"jni/{abi}/libonnxruntime.so"))
            core_symbols = run([readelf, "--wide", "--dyn-syms", "-d", core])
            if "OrtGetApiBase@@VERS_1.27.1" not in core_symbols or "[libonnxruntime.so]" not in core_symbols:
                raise ValueError("unchanged voice core does not expose the pinned ORT symbol/SONAME contract")
            reference = directory / "official-libonnxruntime4j_jni.so"
            reference.write_bytes(references[abi])
            reference_symbols = native_exports(run([readelf, "--wide", "--dyn-syms", reference]))
            target_file = output / "jni" / abi / "libonnxruntime4j_jni.so"
            target_file.parent.mkdir(parents=True, exist_ok=True)
            command = [clang, f"--target={target}", f"--sysroot={prebuilt / 'sysroot'}", "-shared", "-fPIC", "-O2", "-std=c11",
                       "-DUSE_NNAPI", "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384", "-Wl,-soname,libonnxruntime4j_jni.so",
                       "-I", source / "include", "-I", source / "include/onnxruntime/core/session",
                       "-I", source / "orttraining/orttraining/training_api/include", "-I", native,
                       "-I", generated, *sorted(native.glob("*.c")), core, "-lm", "-ldl", "-o", target_file]
            run(command)
            run([strip, "--strip-unneeded", target_file])
            elf = run([readelf, "--wide", "--dyn-syms", "--version-info", "-d", "-l", target_file])
            check_linkage(elf, reference_symbols)
            (directory / "rebuilt-jni.readelf.txt").write_text(elf, encoding="utf-8")
            report["abis"][abi] = {"unchanged_voice_core_sha256": digest(core), "jni_sha256": digest(target_file),
                                   "jni_exports": len(reference_symbols), "version_requires": "VERS_1.27.1", "page_alignment": 16384,
                                   "android_target": target, "core_export": "OrtGetApiBase@@VERS_1.27.1"}
    (output / "embedding-jni.provenance.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("java-aar", "sherpa-aar", "ndk", "java-home", "cache", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    arguments = parser.parse_args()
    print(json.dumps(build(arguments)["abis"], indent=2))
