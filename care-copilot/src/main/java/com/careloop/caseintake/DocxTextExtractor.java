package com.careloop.caseintake;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 轻量提取 .docx 正文（不解依赖 Apache POI）：docx 即 zip，读 word/document.xml 去标签。
 */
@Component
public class DocxTextExtractor {

    public String extract(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "";
        }
        // ZIP magic
        if (bytes[0] != 'P' || bytes[1] != 'K') {
            return "";
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    return xmlToPlain(xml);
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    public String extract(InputStream in) {
        try {
            return extract(in.readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }

    private String xmlToPlain(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        String s = xml
                .replaceAll("(?i)</w:p>", "\n")
                .replaceAll("(?i)</w:tr>", "\n")
                .replaceAll("(?i)<w:tab[^/]*/>", "\t")
                .replaceAll("(?i)<w:br[^/]*/>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#xa;", "\n")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return s;
    }
}
