"""Audit shared class exports in a paired optimized target/instrumentation APK.

DEX type references, not a guessed package whitelist, define the cross-APK
surface. AndroidTest excludes dependencies supplied by the target; R8 must not
remove or merge those referenced classes. Mapping and usage identify target
ownership. --rules emits exact class keeps to review into the release ABI file.
This checks class and method linkage; installed tests still prove behavior.
"""
import argparse
import re
import struct
import zipfile
from pathlib import Path


def uleb(data, offset):
    value, shift = 0, 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7
        if shift > 35:
            raise ValueError("Invalid DEX ULEB128")


def dex_types(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Expected standard APK DEX")
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    strings = []
    for index in range(strings_count):
        offset = struct.unpack_from("<I", data, strings_offset + index * 4)[0]
        _, offset = uleb(data, offset)
        end = data.index(0, offset)
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + i * 4)[0]] for i in range(count)]
    count, offset = struct.unpack_from("<II", data, 96)
    defined, parents, declared = set(), {}, set()
    method_count, method_offset = struct.unpack_from("<II", data, 88)
    proto_count, proto_offset = struct.unpack_from("<II", data, 72)
    protos = []
    for i in range(proto_count):
        _, result, args_offset = struct.unpack_from("<III", data, proto_offset + i * 12)
        args = ()
        if args_offset:
            size = struct.unpack_from("<I", data, args_offset)[0]
            args = tuple(types[struct.unpack_from("<H", data, args_offset + 4 + j * 2)[0]] for j in range(size))
        protos.append((types[result], args))
    methods = []
    for i in range(method_count):
        owner, proto, name = struct.unpack_from("<HHI", data, method_offset + i * 8)
        methods.append((types[owner], strings[name], *protos[proto]))
    for i in range(count):
        owner, _, superclass, interfaces, _, _, class_data, _ = struct.unpack_from("<8I", data, offset + i * 32)
        owner = types[owner]
        defined.add(owner)
        supers = [] if superclass == 0xffffffff else [types[superclass]]
        if interfaces:
            size = struct.unpack_from("<I", data, interfaces)[0]
            supers.extend(types[struct.unpack_from("<H", data, interfaces + 4 + j * 2)[0]] for j in range(size))
        parents[owner] = supers
        if class_data:
            sizes = []
            for _ in range(4):
                size, class_data = uleb(data, class_data)
                sizes.append(size)
            for _ in range(sizes[0] + sizes[1]):
                _, class_data = uleb(data, class_data)
                _, class_data = uleb(data, class_data)
            for size in sizes[2:]:
                method_index = 0
                for _ in range(size):
                    delta, class_data = uleb(data, class_data)
                    method_index += delta
                    _, class_data = uleb(data, class_data)
                    _, class_data = uleb(data, class_data)
                    declared.add(methods[method_index])
    return set(types), defined, set(methods), declared, parents


def apk_types(path):
    referenced, defined, methods, declared, parents = set(), set(), set(), set(), {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                refs, defs, calls, definitions, hierarchy = dex_types(apk.read(name))
                referenced.update(refs)
                defined.update(defs)
                methods.update(calls)
                declared.update(definitions)
                parents.update(hierarchy)
    return referenced, defined, methods, declared, parents


def method_exists(method, definitions, parents, visited=None):
    if method in definitions:
        return True
    owner, name, result, args = method
    if name in {"<init>", "<clinit>"}:
        return False
    visited = set() if visited is None else visited
    if owner in visited:
        return False
    visited.add(owner)
    if owner == "Ljava/lang/Object;":
        return (name, result, args) in {
            ("toString", "Ljava/lang/String;", ()), ("hashCode", "I", ()),
            ("equals", "Z", ("Ljava/lang/Object;",)), ("getClass", "Ljava/lang/Class;", ()),
            ("notify", "V", ()), ("notifyAll", "V", ()),
            ("wait", "V", ()), ("wait", "V", ("J",)), ("wait", "V", ("J", "I")),
        }
    if owner.startswith(("Ljava/", "Landroid/")):
        # Framework inherited methods are not supplied by either APK.
        return True
    return any(method_exists((parent, name, result, args), definitions, parents, visited)
               for parent in parents.get(owner, []))


def descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def vendor_jni_mapping_errors(mapping):
    """Native reflection cannot consume renamed SDK bean fields/classes."""
    errors, vendor = [], False
    for line in mapping.splitlines():
        header = re.fullmatch(r"(\S+) -> (\S+):", line)
        if header:
            original, residual = header.groups()
            vendor = original.startswith(("com.geniex.sdk.bean.", "com.geniex.sdk.jni."))
            if vendor and original != residual:
                errors.append(f"JNI class renamed: {original} -> {residual}")
        elif vendor and " -> " in line:
            source, residual = line.strip().split(" -> ")
            name = source.split("(", 1)[0].split()[-1].split(":")[-1].rsplit(".", 1)[-1]
            if name != residual:
                errors.append(f"JNI member renamed: {source} -> {residual}")
    return errors


def shared_exports(test_refs, test_defs, mapping, usage):
    aliases, originals = {}, set()
    for line in mapping.splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match:
            original, residual = match.groups()
            originals.add(descriptor(original))
            aliases[descriptor(residual)] = descriptor(original)
    for line in usage.splitlines():
        if line and not line[0].isspace():
            originals.add(descriptor(line.rstrip(":")))
    exports = set()
    for ref in test_refs - test_defs:
        ref = ref.lstrip("[")
        original = aliases.get(ref, ref)
        # Platform classes are supplied by Android, and annotation descriptors
        # are metadata rather than executable cross-APK calls.
        if original.startswith(("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/",
                                "Landroidx/annotation/", "Lorg/jetbrains/annotations/",
                                "Lcom/google/errorprone/annotations/")) or original in {
            "Lkotlin/jvm/JvmStatic;", "Lkotlin/jvm/internal/SourceDebugExtension;",
            "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        }:
            continue
        if original in originals:
            exports.add(original)
    return exports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--test", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--usage", required=True, type=Path)
    parser.add_argument("--rules", action="store_true")
    args = parser.parse_args()
    mapping = args.mapping.read_text()
    vendor_errors = vendor_jni_mapping_errors(mapping)
    if vendor_errors and not args.rules:
        raise SystemExit("Unstable vendor JNI ABI:\n" + "\n".join(vendor_errors))
    refs, defs, methods, _, _ = apk_types(args.test)
    exports = shared_exports(refs, defs, mapping, args.usage.read_text())
    if args.rules:
        for name in sorted(exports):
            print("-keep,includedescriptorclasses class " + name[1:-1].replace("/", ".") + " { public *; protected *; }")
        return
    _, target_defs, _, target_methods, target_parents = apk_types(args.target)
    missing = sorted(exports - target_defs)
    if missing:
        raise SystemExit("Unstable/missing shared class exports:\n" + "\n".join(missing))
    missing_methods = sorted(method for method in methods if method[0] in exports
                             and not method_exists(method, target_methods, target_parents))
    if missing_methods:
        raise SystemExit("Missing shared method exports:\n" + "\n".join(map(str, missing_methods)))
    print(f"Paired APK shared class exports verified: {len(exports)}")


if __name__ == "__main__":
    main()
