package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.adapters.dart.DartModels.CorpCode;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * CORPCODE.xml 스트리밍 Reader (StAX). 약 10만 건을 메모리에 올리지 않고 &lt;list&gt; 단위로 읽습니다.
 * AbstractItemCountingItemStreamItemReader 가 읽은 건수를 ExecutionContext 에 저장하므로
 * 재시작 시 마지막 커밋 이후부터 이어 읽습니다.
 */
public class CorpCodeXmlReader extends AbstractItemCountingItemStreamItemReader<CorpCode> {
    private final Path file;
    private InputStream in;
    private XMLStreamReader xml;

    public CorpCodeXmlReader(Path file) {
        this.file = file;
        setName("corpCodeXmlReader");
    }

    @Override
    protected void doOpen() throws Exception {
        in = Files.newInputStream(file);
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml = f.createXMLStreamReader(in, "UTF-8");
    }

    @Override
    protected CorpCode doRead() throws Exception {
        while (xml.hasNext()) {
            int ev = xml.next();
            if (ev == XMLStreamConstants.START_ELEMENT && "list".equals(xml.getLocalName())) {
                Map<String, String> f = new HashMap<>();
                String current = null;
                StringBuilder text = new StringBuilder();
                while (xml.hasNext()) {
                    int e = xml.next();
                    if (e == XMLStreamConstants.START_ELEMENT) {
                        current = xml.getLocalName();
                        text.setLength(0);
                    } else if (e == XMLStreamConstants.CHARACTERS || e == XMLStreamConstants.CDATA) {
                        if (current != null) text.append(xml.getText());
                    } else if (e == XMLStreamConstants.END_ELEMENT) {
                        if ("list".equals(xml.getLocalName())) break;
                        if (current != null) f.put(current, text.toString().trim());
                        current = null;
                    }
                }
                return new CorpCode(f.get("corp_code"), f.get("corp_name"), blank(f.get("corp_eng_name")),
                        blank(f.get("stock_code")), blank(f.get("modify_date")));
            }
        }
        return null;
    }

    @Override
    protected void doClose() throws Exception {
        if (xml != null) xml.close();
        if (in != null) in.close();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
