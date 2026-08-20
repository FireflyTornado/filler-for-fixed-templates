package com.firefly.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本文件读写工具：
 *   * 写入：UTF-8 带 BOM + 换行转成 CRLF，方便中文 Windows 记事本打开
 *   * 读取：UTF-8，自动去掉开头的 BOM
 */
public final class TextFileWriter {

    /** UTF-8 的 BOM 字节 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextFileWriter() {
    }

    /** 写文件：内容中的换行统一转成 CRLF，再以 UTF-8 带 BOM 写出。 */
    public static void writeText(Path path, String text) throws IOException {
        String normalized = text.replace("\r\n", "\n").replace("\n", "\r\n");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path))) {
            out.write(BOM);
            out.write(normalized.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 读文件：以 UTF-8 读取并去掉开头的 BOM。 */
    public static String readText(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        // 用 0xFEFF 判断 BOM，避免源码里出现 Unicode 转义歧义
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
            text = text.substring(1);
        }
        return text;
    }
}
