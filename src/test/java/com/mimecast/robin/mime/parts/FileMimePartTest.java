package com.mimecast.robin.mime.parts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FileMimePartTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8191, 8192, 8193, 16384, 131072})
    void repeatedReadsAndWritesBorrowSource(int length) throws Exception {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'A');
        Path source = Files.write(directory.resolve("payload.bin"), bytes);
        for (int constructor = 0; constructor < 3; constructor++) {
            FileMimePart part = switch (constructor) {
                case 0 -> new FileMimePart(source.toFile());
                case 1 -> new FileMimePart(source.toString());
                default -> new FileMimePart(source.toString(), 17);
            };
            try (part) {
                assertEquals(source.toFile(), part.getFile());
                assertArrayEquals(bytes, part.getBytes());
                assertArrayEquals(bytes, part.getBytes());
                ByteArrayOutputStream first = new ByteArrayOutputStream();
                ByteArrayOutputStream second = new ByteArrayOutputStream();
                part.writeTo(first);
                part.writeTo(second);
                assertArrayEquals(first.toByteArray(), second.toByteArray());
                assertArrayEquals(bytes, part.getBytes());
            }
            part.close();
            assertThrows(IOException.class, part::getBytes);
            assertArrayEquals(bytes, Files.readAllBytes(source));
        }
    }

    @Test
    void appendsFreshRandomBytesWithoutChangingSource() throws Exception {
        Path source = Files.write(directory.resolve("source.bin"), new byte[]{1, 2, 3});
        try (FileMimePart part = new FileMimePart(source.toString(), 1)) {
            part.setAppendRandomBytes(64);
            byte[] first = part.getBytes();
            byte[] second = part.getBytes();
            assertEquals(67, first.length);
            assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOf(first, 3));
            assertFalse(Arrays.equals(first, second));
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(source));
        assertThrows(IOException.class, () -> new FileMimePart(directory.resolve("absent").toFile()));
        assertThrows(IllegalArgumentException.class, () -> new FileMimePart(source.toString(), 0));
    }

    @Test
    void ownedDeletionCanBeRetriedWithoutResurrectingReads() throws Exception {
        Path path = Files.write(directory.resolve("owned.bin"), new byte[]{1, 2, 3});
        FileMimePart part = FileMimePart.temporary(path.toFile());
        Files.delete(path);
        Files.createDirectory(path);
        Path child = Files.write(path.resolve("blocker"), new byte[]{1});
        assertThrows(IOException.class, part::close);
        assertThrows(IOException.class, part::getBytes);
        Files.delete(child);
        part.close();
        part.close();
        assertFalse(Files.exists(path));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulCloseDoesNotDeleteRecreatedPath(boolean removedBeforeClose) throws Exception {
        Path path = Files.write(directory.resolve("owned.bin"), new byte[]{1, 2, 3});
        try (FileMimePart part = FileMimePart.temporary(path.toFile())) {
            if (removedBeforeClose) Files.delete(path);
            part.close();
            byte[] replacement = {4, 5, 6};
            Files.write(path, replacement);
            part.close();
            assertTrue(Files.exists(path));
            assertArrayEquals(replacement, Files.readAllBytes(path));
            assertThrows(IOException.class, part::getBytes);
        }
    }
}
