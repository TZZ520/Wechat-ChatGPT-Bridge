package com.zhaozhan.wxchatgptbridge;

import java.io.StringReader;
import java.util.Locale;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** 只识别微信普通附件中的 PDF；不记录文件名或消息内容。 */
final class PdfMessage {
    private PdfMessage() {}

    static final class Metadata {
        final String extension;
        final long expectedBytes;
        final int appType;
        Metadata(String extension, long expectedBytes) {
            this(extension, expectedBytes, 0);
        }
        Metadata(String extension, long expectedBytes, int appType) {
            this.extension = extension; this.expectedBytes = expectedBytes; this.appType = appType;
        }
    }

    static boolean isPdf(String xml) { return "pdf".equals(extension(xml)); }

    static String extension(String xml) {
        return parse(xml).extension;
    }

    static Metadata parse(String xml) {
        Metadata empty = new Metadata("", -1);
        if (xml == null || xml.length() > 200_000) return empty;
        String lower = xml.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) return empty;
        int start = xml.indexOf("<msg");
        if (start < 0) return empty;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignored) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignored) {}
            Handler handler = new Handler();
            factory.newSAXParser().parse(new InputSource(new StringReader(xml.substring(start))), handler);
            String appType = handler.type.trim();
            // 当前微信在接收文件事件中先使用 74，稍后保存成 6；两者都必须有受支持的附件扩展名。
            if (!"6".equals(appType) && !"74".equals(appType)) return empty;
            String ext = FileRules.normalized(handler.extension);
            if (!FileRules.supported(ext)) return empty;
            long length = -1;
            try { length = Long.parseLong(handler.totalBytes.trim()); }
            catch (NumberFormatException ignored) { }
            return new Metadata(ext, length > 0 ? length : -1, Integer.parseInt(appType));
        } catch (Exception ignored) { return empty; }
    }

    private static final class Handler extends DefaultHandler {
        int depth, appDepth = -1, attachDepth = -1;
        String capture = "", type = "", extension = "", totalBytes = "";
        final StringBuilder value = new StringBuilder();
        @Override public void startElement(String uri, String local, String name, Attributes attributes) {
            depth++;
            if ("appmsg".equals(name) && appDepth < 0) appDepth = depth;
            if (appDepth > 0 && "appattach".equals(name) && depth == appDepth + 1) attachDepth = depth;
            if (appDepth > 0 && "type".equals(name) && depth == appDepth + 1) capture = "type";
            else if (attachDepth > 0 && "fileext".equals(name) && depth == attachDepth + 1) capture = "extension";
            else if (attachDepth > 0 && "totallen".equals(name) && depth == attachDepth + 1) capture = "size";
            if (!capture.isEmpty()) value.setLength(0);
        }
        @Override public void characters(char[] ch, int start, int length) {
            if (!capture.isEmpty() && value.length() + length <= 100) value.append(ch, start, length);
        }
        @Override public void endElement(String uri, String local, String name) {
            if ("type".equals(capture) && "type".equals(name)) { type = value.toString(); capture = ""; }
            if ("extension".equals(capture) && "fileext".equals(name)) { extension = value.toString(); capture = ""; }
            if ("size".equals(capture) && "totallen".equals(name)) { totalBytes = value.toString(); capture = ""; }
            if ("appattach".equals(name) && depth == attachDepth) attachDepth = -1;
            if ("appmsg".equals(name) && depth == appDepth) appDepth = -1;
            depth--;
        }
    }
}
