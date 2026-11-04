package com.zhaozhan.wxchatgptbridge;
import static org.junit.Assert.*;
import org.junit.Test;
public class PdfMessageTest {
    @Test public void acceptsOnlyPdfFileAttachments() {
        assertTrue(PdfMessage.isPdf("<msg><appmsg><type>6</type><appattach><fileext>PDF</fileext></appattach></appmsg></msg>"));
        assertFalse(PdfMessage.isPdf("<msg><appmsg><type>5</type><appattach><fileext>pdf</fileext></appattach></appmsg></msg>"));
        assertFalse(PdfMessage.isPdf("<msg><appmsg><type>6</type><appattach><fileext>docx</fileext></appattach></appmsg></msg>"));
        assertFalse(PdfMessage.isPdf("<!DOCTYPE msg [<!ENTITY x SYSTEM 'file:///private'>]><msg><appmsg><type>6</type><appattach><fileext>pdf</fileext></appattach></appmsg></msg>"));
    }
}
