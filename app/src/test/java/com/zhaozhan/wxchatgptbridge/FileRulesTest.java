package com.zhaozhan.wxchatgptbridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FileRulesTest {
    @Test public void acceptsDocumentAndImageTypesWithoutAcceptingPaths() {
        for (String ext : new String[]{"PDF", "docx", "xls", "xlsx", "pptx", "txt", "csv", "md"})
            assertTrue(ext, FileRules.isDocument(ext));
        for (String ext : new String[]{"gif", "JPG", "png", "webp"}) assertTrue(FileRules.isImage(ext));
        for (String ext : new String[]{"apk", "zip", "exe", "../pdf", "pdf/../../txt"})
            assertFalse(ext, FileRules.supported(ext));
    }

    @Test public void sizeIsClampedAndEnforced() {
        assertEquals(1_000_000, FileRules.maxBytes(0));
        assertEquals(20_000_000, FileRules.maxBytes(21));
        assertTrue(FileRules.validDocument(new byte[]{1, 2}, "txt", 2));
        assertFalse(FileRules.validDocument(new byte[]{1, 2}, "txt", 1));
    }

    @Test public void validatesCommonBinaryHeaders() {
        assertTrue(FileRules.validDocument("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "pdf", 20));
        assertFalse(FileRules.validDocument("not-pdf".getBytes(StandardCharsets.US_ASCII), "pdf", 20));
        assertTrue(FileRules.validDocument(new byte[]{'P', 'K', 3, 4}, "docx", 20));
        assertFalse(FileRules.validDocument(new byte[]{'P', 'K', 3, 4}, "doc", 20));
        assertTrue(FileRules.validDocument(new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0}, "xls", 20));
    }

    @Test public void readsOnlyAllowedAttachmentExtensionsFromWechatXml() {
        String xml = "<msg><appmsg><type>6</type><appattach><fileext>DOCX</fileext></appattach></appmsg></msg>";
        assertEquals("docx", PdfMessage.extension(xml));
        assertEquals("docx", PdfMessage.extension(xml.replace("<type>6</type>", "<type>74</type>")));
        assertEquals("", PdfMessage.extension(xml.replace("DOCX", "exe")));
        assertEquals("", PdfMessage.extension(xml.replace("<type>6</type>", "<type>5</type>")));
        assertEquals("", PdfMessage.extension("<!DOCTYPE msg><msg/>"));
        String sized = xml.replace("</appattach>", "<totallen>12345</totallen></appattach>");
        assertEquals(12345, PdfMessage.parse(sized).expectedBytes);
        assertEquals(-1, PdfMessage.parse(xml).expectedBytes);
    }

    @Test public void findsStickerHashWithoutAcceptingPaths() {
        String hash = "0123456789abcdef0123456789abcdef";
        assertEquals(hash, EmojiLoader.identity(hash.toUpperCase(), ""));
        assertEquals(hash, EmojiLoader.identity("", "<msg><emoji md5=\"" + hash + "\"/></msg>"));
        assertEquals("", EmojiLoader.identity("../../" + hash, "<emoji md5=\"../bad\"/>"));
    }

    @Test public void acceptsWechatEncodedFileMessageTypes() {
        assertTrue(BridgeRules.isFileMessageType(49));
        assertTrue(BridgeRules.isFileMessageType(1_090_519_089));
        assertTrue(BridgeRules.isFileMessageType(754_974_769));
        assertFalse(BridgeRules.isFileMessageType(3));
        assertFalse(BridgeRules.isFileMessageType(34));
    }
}
