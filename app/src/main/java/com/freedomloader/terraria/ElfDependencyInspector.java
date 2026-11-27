package com.freedomloader.terraria;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only ELF64 dependency inspector for the ARM64 libunity.so inside a user-selected APK.
 * It only detects a direct DT_NEEDED reference to libpairipcore.so; it does not modify any file.
 */
final class ElfDependencyInspector {
    static final int UNKNOWN = -1;
    static final int NOT_FOUND = 0;
    static final int FOUND = 1;

    private static final int MAX_LIBRARY_BYTES = 96 * 1024 * 1024;
    private static final int ELF_HEADER_SIZE = 64;
    private static final int PROGRAM_HEADER_MIN_SIZE = 56;
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_NEEDED = 1;
    private static final long DT_STRTAB = 5;
    private static final long DT_STRSZ = 10;
    private static final int EM_AARCH64 = 183;

    private static final class LoadSegment {
        final long fileOffset;
        final long virtualAddress;
        final long fileSize;

        LoadSegment(long fileOffset, long virtualAddress, long fileSize) {
            this.fileOffset = fileOffset;
            this.virtualAddress = virtualAddress;
            this.fileSize = fileSize;
        }
    }

    private ElfDependencyInspector() { }

    static int inspectPairIp(InputStream input) throws IOException {
        byte[] image = readBounded(input);
        if (image == null || image.length < ELF_HEADER_SIZE) return UNKNOWN;

        ByteBuffer elf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        if (elf.get(0) != 0x7f || elf.get(1) != 'E' ||
                elf.get(2) != 'L' || elf.get(3) != 'F') return UNKNOWN;
        if ((elf.get(4) & 0xff) != 2 || (elf.get(5) & 0xff) != 1) return UNKNOWN;
        if (Short.toUnsignedInt(elf.getShort(18)) != EM_AARCH64) return UNKNOWN;

        long programHeaderOffset = elf.getLong(32);
        int programHeaderSize = Short.toUnsignedInt(elf.getShort(54));
        int programHeaderCount = Short.toUnsignedInt(elf.getShort(56));
        if (programHeaderSize < PROGRAM_HEADER_MIN_SIZE ||
                programHeaderCount == 0 || programHeaderCount > 256 ||
                !rangeOk(programHeaderOffset,
                        (long) programHeaderSize * programHeaderCount, image.length)) {
            return UNKNOWN;
        }

        List<LoadSegment> loads = new ArrayList<>();
        long dynamicOffset = -1;
        long dynamicSize = 0;
        for (int i = 0; i < programHeaderCount; i++) {
            int position = (int) (programHeaderOffset + (long) i * programHeaderSize);
            long type = Integer.toUnsignedLong(elf.getInt(position));
            long fileOffset = elf.getLong(position + 8);
            long virtualAddress = elf.getLong(position + 16);
            long fileSize = elf.getLong(position + 32);
            if (type == PT_LOAD && rangeOk(fileOffset, fileSize, image.length)) {
                loads.add(new LoadSegment(fileOffset, virtualAddress, fileSize));
            } else if (type == PT_DYNAMIC && rangeOk(fileOffset, fileSize, image.length)) {
                dynamicOffset = fileOffset;
                dynamicSize = fileSize;
            }
        }
        if (dynamicOffset < 0 || dynamicSize < 16) return UNKNOWN;

        long stringTableAddress = -1;
        long stringTableSize = -1;
        List<Long> neededOffsets = new ArrayList<>();
        long dynamicEnd = dynamicOffset + dynamicSize;
        for (long position = dynamicOffset; position + 16 <= dynamicEnd; position += 16) {
            long tag = elf.getLong((int) position);
            long value = elf.getLong((int) position + 8);
            if (tag == DT_NULL) break;
            if (tag == DT_NEEDED) neededOffsets.add(value);
            else if (tag == DT_STRTAB) stringTableAddress = value;
            else if (tag == DT_STRSZ) stringTableSize = value;
        }
        if (neededOffsets.isEmpty()) return NOT_FOUND;
        if (stringTableAddress < 0 || stringTableSize <= 0) return UNKNOWN;

        long stringTableOffset = -1;
        for (LoadSegment load : loads) {
            if (stringTableAddress >= load.virtualAddress) {
                long delta = stringTableAddress - load.virtualAddress;
                if (delta >= 0 && delta < load.fileSize &&
                        rangeOk(load.fileOffset + delta, stringTableSize, image.length)) {
                    stringTableOffset = load.fileOffset + delta;
                    break;
                }
            }
        }
        if (stringTableOffset < 0) return UNKNOWN;

        for (long nameOffset : neededOffsets) {
            if (nameOffset < 0 || nameOffset >= stringTableSize) return UNKNOWN;
            long start = stringTableOffset + nameOffset;
            long maximumEnd = Math.min(stringTableOffset + stringTableSize, image.length);
            long end = start;
            while (end < maximumEnd && image[(int) end] != 0) end++;
            if (end == maximumEnd) return UNKNOWN;
            String name = new String(image, (int) start, (int) (end - start),
                    StandardCharsets.US_ASCII);
            if ("libpairipcore.so".equals(name)) return FOUND;
        }
        return NOT_FOUND;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            if (count > MAX_LIBRARY_BYTES - total) return null;
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static boolean rangeOk(long offset, long length, int total) {
        return offset >= 0 && length >= 0 && offset <= total && length <= (long) total - offset;
    }
}
