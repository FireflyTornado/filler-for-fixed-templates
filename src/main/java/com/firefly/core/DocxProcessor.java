package com.firefly.core;

import com.firefly.TemplateConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Word（.docx）模板处理。
 * <p>
 * .docx 本质是 zip 包，正文/页眉页脚等部件是 OOXML 文档。替换时直接改写部件里 run 级文本
 * （{@code <w:t>}），因此：
 * <ul>
 *   <li>字段的文字格式（字体、字号、加粗、颜色…，即 w:rPr）原样保留；</li>
 *   <li>所在段落的格式（对齐、缩进、行距…，即 w:pPr）原样保留；</li>
 *   <li>占位符被 Word 拆到多个 run 时也能合并替换，替换结果沿用第一个 run 的格式；</li>
 *   <li>字符串值里的换行转成 {@code <w:br>}（保持同一段内格式），制表符转成 {@code <w:tab>}；</li>
 *   <li>正文、表格、页眉/页脚、脚注/尾注、批注、文本框中的占位符都会处理。</li>
 * </ul>
 * 零第三方依赖，纯 JDK（zip + DOM）。
 */
public final class DocxProcessor {

    /** OOXML 主命名空间 */
    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** xml 命名空间（用于 xml:space 属性） */
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private DocxProcessor() {
    }

    /** 文件名是否为 .docx（大小写不敏感）。 */
    public static boolean isDocxName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    /** 需要替换占位符的部件：正文 + 页眉/页脚 + 脚注/尾注 + 批注。 */
    private static boolean isTextPart(String name) {
        if ("word/document.xml".equals(name)
                || "word/footnotes.xml".equals(name)
                || "word/endnotes.xml".equals(name)
                || "word/comments.xml".equals(name)) {
            return true;
        }
        return (name.startsWith("word/header") || name.startsWith("word/footer"))
                && name.endsWith(".xml");
    }

    /** 部件在提取文本时的顺序：正文最先，其次页眉/页脚，最后脚注/尾注/批注。 */
    private static int partRank(String name) {
        if (name.startsWith("word/header")) {
            return 1;
        }
        if (name.startsWith("word/footer")) {
            return 2;
        }
        if (name.contains("footnotes")) {
            return 3;
        }
        if (name.contains("endnotes")) {
            return 4;
        }
        if (name.contains("comments")) {
            return 5;
        }
        return 0;
    }

    // ---------- 文本提取（用于解析占位符 / 界面只读预览） ----------

    /**
     * 提取 docx 的全部可见文本：
     * 段落之间用换行分隔；run 内的换行（w:br/w:cr）与制表符（w:tab）也还原成 \n 与 \t。
     * 页眉/页脚/脚注/尾注/批注的文本也会包含在内（保证里面的占位符也能生成输入框）。
     */
    public static String extractText(Path docx) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zf = new ZipFile(docx.toFile(), StandardCharsets.UTF_8)) {
            List<String> parts = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (isTextPart(n)) {
                    parts.add(n);
                }
            }
            parts.sort(Comparator.comparingInt(DocxProcessor::partRank)
                    .thenComparing(Comparator.naturalOrder()));
            for (String p : parts) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                try (InputStream in = zf.getInputStream(zf.getEntry(p))) {
                    sb.append(partText(in));
                }
            }
        }
        return sb.toString();
    }

    /** 解析单个部件，把所有段落（含表格、文本框内的段落）的文本按顺序拼出。 */
    private static String partText(InputStream in) throws IOException {
        Document doc = parse(in);
        StringBuilder sb = new StringBuilder();
        NodeList ps = doc.getElementsByTagNameNS(W_NS, "p");
        for (int i = 0; i < ps.getLength(); i++) {
            appendTextContent((Element) ps.item(i), sb);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 递归收集元素内的可见文本：w:t 原文、w:br/w:cr 换行、w:tab 制表符；嵌套段落（文本框）跳过。 */
    private static void appendTextContent(Element parent, StringBuilder sb) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (!W_NS.equals(e.getNamespaceURI())) {
                continue;
            }
            String ln = e.getLocalName();
            if ("p".equals(ln)) {
                continue; // 嵌套段落由外层循环单独处理，避免重复
            }
            if ("t".equals(ln)) {
                sb.append(e.getTextContent());
            } else if ("br".equals(ln) || "cr".equals(ln)) {
                sb.append('\n');
            } else if ("tab".equals(ln)) {
                sb.append('\t');
            } else {
                appendTextContent(e, sb);
            }
        }
    }

    // ---------- 渲染（占位符替换，保留格式） ----------

    /**
     * 把 src 这个 docx 模板渲染成 dst：替换所有 {{变量}}/{{=表达式}}/{{日期}} 占位符。
     * 成功后返回渲染后文档的纯文本（供界面预览）；表达式出错时删除 dst 并返回错误信息。
     */
    public static TemplateRenderer.RenderResult render(Path src, Path dst,
                                                       Map<String, String> values,
                                                       Map<String, String> autoVals) throws IOException {
        try {
            try (ZipFile zin = new ZipFile(src.toFile(), StandardCharsets.UTF_8);
                 ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(dst), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> en = zin.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    ZipEntry out = new ZipEntry(e.getName());
                    out.setTime(e.getTime());
                    zout.putNextEntry(out);
                    if (isTextPart(e.getName())) {
                        byte[] bytes;
                        try (InputStream in = zin.getInputStream(e)) {
                            bytes = in.readAllBytes();
                        }
                        zout.write(processPart(bytes, values, autoVals));
                    } else {
                        try (InputStream in = zin.getInputStream(e)) {
                            in.transferTo(zout);
                        }
                    }
                    zout.closeEntry();
                }
            }
        } catch (ExpressionEvaluator.EvalException ex) {
            Files.deleteIfExists(dst);
            return new TemplateRenderer.RenderResult(null, ex.getMessage());
        }
        return new TemplateRenderer.RenderResult(extractText(dst), null);
    }

    /** 统一变量入口。 */
    public static TemplateRenderer.RenderResult renderUnified(Path src, Path dst,
                                                               Map<String, String> values,
                                                               Map<String, String> autoVals)
            throws IOException {
        return render(src, dst, values, autoVals);
    }

    /** 将 Word 各文本部件中的 [[变量]] 改成 {{变量}}，包括跨 run 的占位符。 */
    public static void migrateLegacyPlaceholders(Path src, Path dst) throws IOException {
        try (ZipFile zin = new ZipFile(src.toFile(), StandardCharsets.UTF_8);
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(dst), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                ZipEntry output = new ZipEntry(entry.getName());
                output.setTime(entry.getTime());
                zout.putNextEntry(output);
                try (InputStream in = zin.getInputStream(entry)) {
                    if (isTextPart(entry.getName())) {
                        zout.write(migrateLegacyPart(in.readAllBytes()));
                    } else {
                        in.transferTo(zout);
                    }
                }
                zout.closeEntry();
            }
        }
    }

    private static byte[] migrateLegacyPart(byte[] xml) throws IOException {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (SAXException e) {
            throw new IOException("无法解析 Word 文档部件：", e);
        }
        NodeList ps = doc.getElementsByTagNameNS(W_NS, "p");
        for (int i = 0; i < ps.getLength(); i++) migrateLegacyParagraph((Element) ps.item(i));
        return serialize(doc);
    }

    private static void migrateLegacyParagraph(Element paragraph) {
        List<Frag> frags = new ArrayList<>();
        collectFrags(paragraph, frags);
        StringBuilder text = new StringBuilder();
        for (Frag frag : frags) {
            frag.start = text.length();
            text.append(frag.text);
            frag.end = text.length();
        }
        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        Matcher matcher = TemplateConstants.LEGACY_PLACEHOLDER_RE.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).trim().isEmpty()) continue;
            ranges.add(new int[]{matcher.start(), matcher.end()});
            replacements.add("{{" + matcher.group(1) + "}}");
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            replaceRange(range[0], range[1], replacements.get(i), frags);
        }
    }

    /** 处理一个部件：解析 XML → 逐段替换 → 序列化回 XML 字节。 */
    private static byte[] processPart(byte[] xml, Map<String, String> values,
                                      Map<String, String> autoVals)
            throws IOException, ExpressionEvaluator.EvalException {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (SAXException e) {
            throw new IOException("无法解析 Word 文档部件：", e);
        }
        NodeList ps = doc.getElementsByTagNameNS(W_NS, "p");
        for (int i = 0; i < ps.getLength(); i++) {
            processParagraph((Element) ps.item(i), values, autoVals);
        }
        return serialize(doc);
    }

    /**
     * 处理一个段落：把段内文本拼成串，一次性找出所有占位符，
     * 再从后往前应用替换（前面的匹配偏移不受影响；替换值里即使出现 {{…}} 也不会被再次替换）。
     */
    private static void processParagraph(Element p, Map<String, String> values,
                                         Map<String, String> autoVals)
            throws ExpressionEvaluator.EvalException {
        List<Frag> frags = new ArrayList<>();
        collectFrags(p, frags);
        if (frags.isEmpty()) {
            return;
        }

        StringBuilder concat = new StringBuilder();
        for (Frag f : frags) {
            f.start = concat.length();
            concat.append(f.text);
            f.end = concat.length();
        }
        String text = concat.toString();

        List<Match> matches = new ArrayList<>();
        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(text);
        while (m.find()) {
            String whole = m.group();
            String content = m.group(1).trim();
            if (content.isEmpty()) {
                continue; // {{}} 原样保留
            }
            matches.add(new Match(m.start(), m.end(), whole, content));
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            applyMatch(matches.get(i), frags, values, autoVals);
        }
    }

    /** 一个占位符匹配（在段落拼接文本中的位置 + 内容）。 */
    private record Match(int start, int end, String whole, String content) {
    }

    /** 把单个占位符替换成值。 */
    private static void applyMatch(Match match, List<Frag> frags,
                                   Map<String, String> values, Map<String, String> autoVals)
            throws ExpressionEvaluator.EvalException {
        String value;
        try {
            value = TemplateRenderer.resolve(match.whole(), match.content(), values, autoVals);
        } catch (ExpressionEvaluator.EvalException e) {
            String expr = match.content().substring(1).trim();
            throw new ExpressionEvaluator.EvalException("表达式「" + expr + "」" + e.getMessage());
        }
        replaceRange(match.start(), match.end(), value, frags);
    }

    /** 替换段落拼接文本中的一个范围，支持范围跨越多个 Word run。 */
    private static void replaceRange(int start, int end, String value, List<Frag> frags) {
        int k = -1;
        int m = -1;
        for (int i = 0; i < frags.size(); i++) {
            Frag f = frags.get(i);
            if (f.text.isEmpty()) {
                continue;
            }
            if (k < 0 && f.start <= start && start < f.end) {
                k = i;
            }
            if (f.start < end && end <= f.end) {
                m = i;
            }
        }
        if (k < 0 || m < 0) {
            return; // 理论不可达：占位符必然落在某个片段内
        }

        Frag fk = frags.get(k);
        String prefix = fk.text.substring(0, start - fk.start);
        if (k == m) {
            String suffix = fk.text.substring(end - fk.start);
            replaceTextInRun(fk, prefix, suffix, value);
        } else {
            // 占位符跨了多个片段：值写入起始片段所在 run，中间片段吞掉，结束片段保留占位符之后的文本
            replaceTextInRun(fk, prefix, "", value);
            for (int i = k + 1; i < m; i++) {
                frags.get(i).consume();
            }
            Frag fm = frags.get(m);
            fm.setText(fm.text.substring(end - fm.start));
        }
    }

    /**
     * 把替换值写进「起始片段」所在的 run：
     * <ul>
     *   <li>首行并入起始 w:t（前面拼上占位符之前的文本 prefix）；</li>
     *   <li>后续行用 {@code <w:br>} 换行，制表符用 {@code <w:tab>}；</li>
     *   <li>最后一行之后拼上 suffix；</li>
     *   <li>全部沿用该 run 的字符格式（w:rPr 不动）。</li>
     * </ul>
     */
    private static void replaceTextInRun(Frag fk, String prefix, String suffix, String value) {
        Element firstT = fk.element;
        Element run = (Element) firstT.getParentNode();
        if (run == null) {
            // w:t 不在 run 内（极少见）：退化为纯文本直写
            fk.setText(prefix + value + suffix);
            return;
        }
        List<String> chunks = chunksOf(value);
        Element lastT = firstT;
        boolean first = true;
        for (String chunk : chunks) {
            if (first) {
                firstT.setTextContent(prefix + chunk);
                preserveSpace(firstT, prefix + chunk);
                lastT = firstT;
                first = false;
            } else if ("\n".equals(chunk)) {
                run.appendChild(newElement(run, "br"));
            } else if ("\t".equals(chunk)) {
                run.appendChild(newElement(run, "tab"));
            } else {
                Element t = newElement(run, "t");
                t.setTextContent(chunk);
                preserveSpace(t, chunk);
                run.appendChild(t);
                lastT = t;
            }
        }
        if (!suffix.isEmpty()) {
            lastT.setTextContent(lastT.getTextContent() + suffix);
            preserveSpace(lastT, lastT.getTextContent());
        }
        // 起始片段（firstT）的文本已变化：同步 Frag.text，保证后续更早位置的匹配用最新文本计算前后缀
        fk.text = firstT.getTextContent();
    }

    /** 把值拆成片段序列：文本 / "\n"（换行）/ "\t"（制表符）。 */
    private static List<String> chunksOf(String value) {
        String norm = value.replace("\r\n", "\n").replace("\r", "\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (c == '\n' || c == '\t') {
                chunks.add(cur.toString());
                cur.setLength(0);
                chunks.add(c == '\n' ? "\n" : "\t");
            } else {
                cur.append(c);
            }
        }
        chunks.add(cur.toString());
        return chunks;
    }

    /** 文本首尾有空白时加上 xml:space="preserve"，否则 Word 会吞掉首尾空格。 */
    private static void preserveSpace(Element t, String text) {
        if (!text.isEmpty()
                && (Character.isWhitespace(text.charAt(0))
                || Character.isWhitespace(text.charAt(text.length() - 1)))) {
            t.setAttributeNS(XML_NS, "xml:space", "preserve");
        }
    }

    private static Element newElement(Element run, String localName) {
        return run.getOwnerDocument().createElementNS(W_NS, "w:" + localName);
    }

    /** 收集段落内的文本片段：w:t（可写文本）或 w:br/w:cr/w:tab（换行/制表符）。 */
    private static void collectFrags(Element p, List<Frag> out) {
        NodeList children = p.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (!W_NS.equals(e.getNamespaceURI())) {
                continue;
            }
            String ln = e.getLocalName();
            if ("p".equals(ln)) {
                continue; // 嵌套段落（文本框）由外层循环单独处理
            }
            if ("t".equals(ln)) {
                out.add(new Frag(e, true, e.getTextContent()));
            } else if ("br".equals(ln) || "cr".equals(ln)) {
                out.add(new Frag(e, false, "\n"));
            } else if ("tab".equals(ln)) {
                out.add(new Frag(e, false, "\t"));
            } else {
                collectFrags(e, out);
            }
        }
    }

    /** 段落内的一个文本片段。 */
    private static final class Frag {
        final Element element;
        final boolean textNode;
        String text;   // 始终与 element 的当前文本保持一致（替换会修改它）
        int start;
        int end;

        Frag(Element element, boolean textNode, String text) {
            this.element = element;
            this.textNode = textNode;
            this.text = text;
        }

        /** 片段整体被占位符吞掉：w:t 清空文本；换行/制表符直接移除。 */
        void consume() {
            text = "";
            if (textNode) {
                element.setTextContent("");
            } else {
                Node parent = element.getParentNode();
                if (parent != null) {
                    parent.removeChild(element);
                }
            }
        }

        void setText(String s) {
            text = s;
            if (textNode) {
                element.setTextContent(s);
                preserveSpace(element, s);
            }
        }
    }

    // ---------- XML 工具 ----------

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // 禁止 DOCTYPE，防 XXE（Word 部件也不应包含 DTD）
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("无法创建 XML 解析器", e);
        }
    }

    private static Document parse(InputStream in) throws IOException {
        try {
            return newBuilder().parse(in);
        } catch (SAXException e) {
            throw new IOException("无法解析 Word 文档部件：", e);
        }
    }

    private static byte[] serialize(Document doc) throws IOException {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.METHOD, "xml");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(bos));
            return bos.toByteArray();
        } catch (TransformerException e) {
            throw new IOException("无法写回 Word 文档部件：", e);
        }
    }

    // ---------- 内置示例 Word 模板（与 example.txt 对应，首次启动时生成） ----------

    /** 最小 .docx 包需要的内容类型部件。 */
    private static final String EXAMPLE_CONTENT_TYPES_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;

    /** 最小 .docx 包的根关系部件。 */
    private static final String EXAMPLE_ROOT_RELS_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;

    /**
     * 示例文档正文：占位符与 example.txt 完全对应（{{今日年月日}}/{{编号}}/{{数量}}/{{单价}}/{{=数量*单价}}/{{备注}}），
     * 并演示 Word 的格式保留能力（居中加粗标题、带边框表格、单元格加粗表头）。
     */
    private static final String EXAMPLE_DOCUMENT_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr>
                    <w:jc w:val="center"/>
                    <w:spacing w:before="360" w:after="360"/>
                  </w:pPr>
                  <w:r>
                    <w:rPr>
                      <w:b/>
                      <w:sz w:val="32"/>
                    </w:rPr>
                    <w:t>销售对账单（示例）</w:t>
                  </w:r>
                </w:p>
                <w:p>
                  <w:r>
                    <w:t>日期：{{今日年月日}}</w:t>
                  </w:r>
                </w:p>
                <w:p>
                  <w:r>
                    <w:t>客户编号：{{编号}}</w:t>
                  </w:r>
                </w:p>
                <w:tbl>
                  <w:tblPr>
                    <w:tblW w:w="9000" w:type="dxa"/>
                    <w:tblBorders>
                      <w:top w:val="single" w:sz="4" w:color="auto"/>
                      <w:left w:val="single" w:sz="4" w:color="auto"/>
                      <w:bottom w:val="single" w:sz="4" w:color="auto"/>
                      <w:right w:val="single" w:sz="4" w:color="auto"/>
                      <w:insideH w:val="single" w:sz="4" w:color="auto"/>
                      <w:insideV w:val="single" w:sz="4" w:color="auto"/>
                    </w:tblBorders>
                  </w:tblPr>
                  <w:tr>
                    <w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>品名</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>数量</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>单价（元）</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>应付（元）</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>产品A</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>{{数量}}</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>{{单价}}</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p><w:r><w:t>{{=数量*单价}}</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p>
                  <w:pPr>
                    <w:spacing w:before="240"/>
                  </w:pPr>
                  <w:r>
                    <w:t>备注：{{备注}}</w:t>
                  </w:r>
                </w:p>
                <w:sectPr/>
              </w:body>
            </w:document>
            """;

    /** 用给定的 document.xml 部件内容生成一个最小可用的 .docx（内容类型 + 根关系 + 正文）。 */
    public static void createDocx(Path dst, String documentXml) throws IOException {
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(dst), StandardCharsets.UTF_8)) {
            writeZipEntry(zout, "[Content_Types].xml", EXAMPLE_CONTENT_TYPES_XML);
            writeZipEntry(zout, "_rels/.rels", EXAMPLE_ROOT_RELS_XML);
            writeZipEntry(zout, "word/document.xml", documentXml);
        }
    }

    /** 生成内置示例 Word 模板（与 example.txt 对应，展示 Word 的格式保留能力）。 */
    public static void createExampleDocx(Path dst) throws IOException {
        createDocx(dst, EXAMPLE_DOCUMENT_XML);
    }

    private static void writeZipEntry(ZipOutputStream zout, String name, String content) throws IOException {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }
}
