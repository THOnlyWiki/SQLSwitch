package cc.thonly.sqlswitch;

import cc.thonly.sqlswitch.util.IoUtils;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodingDetectionTest {
    @Test
    void utf8SampleCutInsideMultibyteCharacterStillDetectsUtf8() throws Exception {
        byte[] prefix = new byte[65535];
        Arrays.fill(prefix, (byte) 'a');
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(prefix, prefix.length + chinese.length);
        System.arraycopy(chinese, 0, all, prefix.length, chinese.length);
        Path temp = Files.createTempFile("sqlswitch-encoding-", ".sql");
        try {
            Files.write(temp, all);
            IoUtils.DetectedText detected = IoUtils.detectCharset(temp);
            assertEquals(StandardCharsets.UTF_8, detected.charset());
            assertTrue(IoUtils.readAll(temp).endsWith("中"));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void gb18030IsUsedWhenUtf8IsNotValid() throws Exception {
        Charset gb = Charset.forName("GB18030");
        byte[] prefix = new byte[1000];
        Arrays.fill(prefix, (byte) 'a');
        byte[] chinese = "中文数据库".getBytes(gb);
        byte[] all = Arrays.copyOf(prefix, prefix.length + chinese.length);
        System.arraycopy(chinese, 0, all, prefix.length, chinese.length);
        Path temp = Files.createTempFile("sqlswitch-encoding-", ".sql");
        try {
            Files.write(temp, all);
            IoUtils.DetectedText detected = IoUtils.detectCharset(temp);
            assertEquals(gb, detected.charset());
            assertTrue(IoUtils.readAll(temp).endsWith("中文数据库"));
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
