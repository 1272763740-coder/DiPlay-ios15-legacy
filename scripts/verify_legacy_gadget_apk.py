#!/usr/bin/env python3
"""Verify that the packaged APK can run the root USB gadget helper.

The helper is an executable renamed to libdiplay-gadget.so. Android only allows executing
files that the installer extracted into nativeLibraryDir, which requires
android:extractNativeLibs="true". aapt2 prints that boolean differently across build-tools
releases, so read the binary manifest directly.
"""
import struct
import sys
import zipfile

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
HELPER = "libdiplay-gadget.so"


def parse_string_pool(blob, pos, header_size):
    count = read_u32(blob, pos + 8)
    flags = read_u32(blob, pos + 16)
    start = read_u32(blob, pos + 20)
    strings = []
    for index in range(count):
        at = pos + start + read_u32(blob, pos + header_size + 4 * index)
        if flags & 0x100:
            length = read_u16(blob, at)
            at += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | read_u16(blob, at)
                at += 2
            strings.append(blob[at:at + length].decode("utf-8", "replace"))
        else:
            length = read_u16(blob, at)
            at += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | read_u16(blob, at)
                at += 2
            strings.append(blob[at:at + length * 2].decode("utf-16-le", "replace"))
    return strings


def read_u16(blob, offset):
    return struct.unpack_from("<H", blob, offset)[0]


def read_u32(blob, offset):
    return struct.unpack_from("<I", blob, offset)[0]


def application_attributes(blob):
    strings = []
    pos = 8
    while pos + 8 <= len(blob):
        chunk_type = read_u16(blob, pos)
        header_size = read_u16(blob, pos + 2)
        chunk_size = read_u32(blob, pos + 4)
        if chunk_size < 8:
            break
        if chunk_type == 0x0001:
            strings = parse_string_pool(blob, pos, header_size)
        elif chunk_type == 0x0102:
            name_index = read_u32(blob, pos + 20)
            name = strings[name_index] if name_index < len(strings) else ""
            if name == "application":
                attributes = {}
                attr_start = read_u16(blob, pos + 24)
                attr_count = read_u16(blob, pos + 28)
                base = pos + 16 + attr_start
                for index in range(attr_count):
                    attr = base + index * 20
                    name_index = read_u32(blob, attr + 4)
                    attr_name = strings[name_index] if name_index < len(strings) else ""
                    data_type = blob[attr + 15]
                    data = read_u32(blob, attr + 16)
                    attributes[attr_name] = (data_type, data)
                return attributes
        pos += chunk_size
    return {}


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} path/to/app.apk", file=sys.stderr)
        return 2
    apk = sys.argv[1]
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        missing = [abi for abi in ABIS if f"lib/{abi}/{HELPER}" not in names]
        if missing:
            print(f"missing {HELPER} for: {', '.join(missing)}", file=sys.stderr)
            return 1
        attributes = application_attributes(archive.read("AndroidManifest.xml"))

    data_type, data = attributes.get("extractNativeLibs", (0x12, 0))
    if data_type != 0x12 or data != 0xFFFFFFFF:
        print("android:extractNativeLibs is not true, so the installer would not extract the helper", file=sys.stderr)
        return 1
    print(f"{apk}: helper packaged for {', '.join(ABIS)} and extracted on install")
    return 0


if __name__ == "__main__":
    sys.exit(main())
