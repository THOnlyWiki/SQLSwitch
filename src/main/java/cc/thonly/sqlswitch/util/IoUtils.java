package cc.thonly.sqlswitch.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class IoUtils {
    private IoUtils() {
    }

    public record DetectedText(Charset charset, int bomLength, boolean validSample) {
        public String encodingName() {
            return charset.name();
        }
    }

    public static DetectedText detectCharset(Path path) throws IOException {
        byte[] head = new byte[65536];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new DetectedText(StandardCharsets.UTF_8, 3, true);
        }
        if (read >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new DetectedText(StandardCharsets.UTF_16LE, 2, true);
        }
        if (read >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new DetectedText(StandardCharsets.UTF_16BE, 2, true);
        }
        byte[] sample = Arrays.copyOf(head, read);
        if (isValidUtf8(sample)) {
            return new DetectedText(StandardCharsets.UTF_8, 0, true);
        }
        Charset gb18030 = Charset.isSupported("GB18030") ? Charset.forName("GB18030") : StandardCharsets.ISO_8859_1;
        return new DetectedText(gb18030, 0, false);
    }

    private static boolean isValidUtf8(byte[] bytes) {
        // The sample may end in the middle of a multi-byte character.  Try a
        // few trims before declaring the content non-UTF-8; otherwise every
        // UTF-8 file whose 64 KiB boundary happens to cut a Chinese character
        // would be misdetected as GB18030.
        for (int trim = 0; trim <= 3 && trim < bytes.length; trim++) {
            byte[] sample = trim == 0 ? bytes : Arrays.copyOf(bytes, bytes.length - trim);
            if (decodesAsUtf8(sample)) {
                return true;
            }
        }
        return false;
    }

    private static boolean decodesAsUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } finally {
            decoder.reset();
        }
    }

    public static Reader openReader(Path path) throws IOException {
        DetectedText detected = detectCharset(path);
        InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 20);
        int skipped = 0;
        while (skipped < detected.bomLength()) {
            long n = in.skip(detected.bomLength() - skipped);
            if (n <= 0) {
                break;
            }
            skipped += (int) n;
        }
        return new BufferedReader(new InputStreamReader(in, detected.charset()), 1 << 20);
    }

    public static String readAll(Path path) throws IOException {
        DetectedText detected = detectCharset(path);
        byte[] bytes = Files.readAllBytes(path);
        int offset = Math.min(detected.bomLength(), bytes.length);
        return new String(bytes, offset, bytes.length - offset, detected.charset());
    }

    public static void writeUtf8(Path path, String content, boolean bom) throws IOException {
        ensureParent(path);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            Files.write(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return;
        }
        byte[] all = new byte[body.length + 3];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        Files.write(path, all, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    public static void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public static byte[] firstBytes(Path path, int count) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(count);
        }
    }

    public static boolean isSqliteFile(Path path) throws IOException {
        byte[] head = firstBytes(path, 16);
        return head.length >= 16
                && head[0] == 'S' && head[1] == 'Q' && head[2] == 'L' && head[3] == 'i'
                && head[4] == 't' && head[5] == 'e' && head[6] == ' ' && head[7] == 'f'
                && head[8] == 'o' && head[9] == 'r' && head[10] == 'm' && head[11] == 'a'
                && head[12] == 't' && head[13] == ' ' && head[14] == '3' && head[15] == 0;
    }

    public static String decode(byte[] bytes, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, charset);
        }
    }
}
