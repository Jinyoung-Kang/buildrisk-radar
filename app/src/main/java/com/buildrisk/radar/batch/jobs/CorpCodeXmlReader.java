package com.buildrisk.radar.batch.jobs;

import com.buildrisk.radar.company.domain.CompanyModels.CorpCode;
import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * CORPCODE.xml 스트리밍 Reader (StAX). 약 10만 건을 메모리에 올리지 않고 &lt;list&gt; 단위로 읽습니다.
 * Job 구성은 읽은 위치를 저장하지 않으므로(saveState=false) 재시작하면 처음부터 — restart 때 파일을 다시 받을 수 있어서 (VERIFICATION 183)
 */
public class CorpCodeXmlReader extends AbstractItemCountingItemStreamItemReader<CorpCode> {
    private final Path file;
    private InputStream in;
    private XMLStreamReader xml;

    // @StepScope 프록시(CGLIB 하위 클래스)가 있어 final 로 막을 수 없음 — setName 은 이름 필드만 정하는 부모 메서드
    @SuppressWarnings("this-escape")
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
