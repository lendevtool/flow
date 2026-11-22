package me.lendev.flow.docgen.render;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static me.lendev.flow.docgen.render.RenderFailure.require;

/**
 * 在 POI 打开包之前限制嵌套解压开销，并验证实际部件与内部关系。
 */
final class OfficePackage {
    static final int MAX_FILE = 5 * 1024 * 1024;
    private static final int EXPANDED = 40 * 1024 * 1024;
    private static final int MAX_XML_ELEMENTS = 250_000;
    private static final int MAX_XML_ATTRIBUTES = 500_000;
    private static final String MAX_XML_DEPTH = "http://www.oracle.com/xml/jaxp/properties/maxElementDepth";
    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final Set<String> RELATIONS = Set.of("officeDocument", "core-properties", "extended-properties",
            "custom-properties", "thumbnail", "fontTable", "settings", "styles", "stylesWithEffects", "webSettings",
            "numbering", "theme", "header", "footer", "image", "chart", "package", "customXml", "customXmlProps",
            "worksheet", "stylesExtension", "sharedStrings", "table", "chartStyle", "chartColorStyle", "endnotes",
            "footnotes");
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String ROOT_RELATIONS = "_rels/.rels";
    private static final String WORKBOOK_SUFFIX = ".xlsx";
    private static final Set<String> PART_ROOTS = Set.of("word", "xl", "customXml", "docProps");
    private static final Set<String> PART_EXTENSIONS = Set.of("xml", "rels", "png", "jpeg", "jpg", "xlsx");
    private static final Pattern PART_SEGMENT = Pattern.compile("[A-Za-z0-9_. -]+");
    private static final String OFFICE = "application/vnd.openxmlformats-officedocument.";
    private static final Set<String> OFFICE_TYPES =
            Set.of("customXmlProperties", "extended-properties", "custom-properties", "drawingml.chart", "theme",
                    "wordprocessingml.document.main", "wordprocessingml.fontTable", "wordprocessingml.footer",
                    "wordprocessingml.header", "wordprocessingml.numbering", "wordprocessingml.settings",
                    "wordprocessingml.styles", "wordprocessingml.webSettings", "wordprocessingml.endnotes",
                    "wordprocessingml.footnotes", "spreadsheetml.sheet.main", "spreadsheetml.worksheet",
                    "spreadsheetml.styles", "spreadsheetml.sharedStrings", "spreadsheetml.table");
    private static final Set<String> OTHER_TYPES = Set.of("application/xml", "image/png", "image/jpeg",
            "application/vnd.openxmlformats-package.relationships+xml",
            "application/vnd.openxmlformats-package.core-properties+xml",
            "application/vnd.ms-word.stylesWithEffects+xml", "application/vnd.ms-office.chartstyle+xml",
            "application/vnd.ms-office.chartcolorstyle+xml", OFFICE + "spreadsheetml.sheet");
    private static final Set<String> UNSUPPORTED_MARKUP = Set.of("altChunk", "object", "OLEObject", "control",
            "binData", "imagedata", "attachedTemplate", "subDoc", "dataBinding");
    private static final Pattern WORD_FIELD_NAME = Pattern.compile("PAGE|NUMPAGES|(TOC)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOC_SWITCH = Pattern.compile(
            "\\s+(?:\\\\[hzu]|\\\\o\\s+\"[1-9]-[1-9]\")", Pattern.CASE_INSENSITIVE);
    private int entries;
    private int total;
    private final XmlBudget xmlBudget = new XmlBudget();
    private int images;
    private boolean hasThumbnail;
    private long imagePixels;

    Map<String, Document> inspect(byte[] data) {
        if (data == null || data.length == 0)
            throw unsupported();
        if (data.length > MAX_FILE)
            throw new RenderFailure("FILE_TOO_LARGE", "$", "DOCX 超过文件大小上限");
        try {
            return read(data, false);
        } catch (RenderFailure e) {
            throw e;
        } catch (Exception e) {
            throw unsupported();
        }
    }

    private static RenderFailure unsupported() {
        return new RenderFailure("UNSUPPORTED_FILE_TYPE", "$", "文件不是受支持的 DOCX 文档");
    }

    private Map<String, Document> read(byte[] data, boolean workbook)
            throws IOException, SAXException, ParserConfigurationException {
        var parts = readParts(data, workbook);
        String main = workbook ? "xl/workbook.xml" : "word/document.xml";
        validateMainPart(parts.xml(), main, workbook);
        validateContentTypes(parts.xml().get(CONTENT_TYPES), main, workbook);
        validateRelations(parts, main);
        return parts.xml();
    }

    private static DocumentBuilder newParser() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(MAX_XML_DEPTH, "128");
        factory.setFeature(DISALLOW_DOCTYPE, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    private static SAXParser newBudgetParser() throws ParserConfigurationException, SAXException {
        var factory = SAXParserFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE, true);
        var parser = factory.newSAXParser();
        parser.setProperty(MAX_XML_DEPTH, "128");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parser;
    }

    private PackageParts readParts(byte[] data, boolean workbook)
            throws IOException, SAXException, ParserConfigurationException {
        var parts = new PackageParts(new HashMap<>(), new HashSet<>());
        var parser = newParser();
        var budgetParser = newBudgetParser();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                require(++entries <= 1200, "压缩包条目过多");
                if (entry.isDirectory()) {
                    require(zip.read() == -1, "目录条目不可包含内容");
                    continue;
                }
                String name = entry.getName();
                require(allowedPartName(name) && parts.names().add(name), "不允许的包部件");
                byte[] bytes = readEntry(zip, entry);
                inspectPart(name, bytes, workbook, parts.xml(), parser, budgetParser);
            }
        }
        return parts;
    }

    private static boolean allowedPartName(String name) {
        if (name.length() > 256 || name.contains("..")) {
            return false;
        }
        if (CONTENT_TYPES.equals(name) || ROOT_RELATIONS.equals(name)) {
            return true;
        }
        String[] segments = name.split("/", -1);
        if (segments.length < 2 || !PART_ROOTS.contains(segments[0])) {
            return false;
        }
        for (int index = 1; index < segments.length; index++) {
            if (!PART_SEGMENT.matcher(segments[index]).matches()) {
                return false;
            }
        }
        String filename = segments[segments.length - 1];
        int extensionStart = filename.lastIndexOf('.');
        return extensionStart > 0 && PART_EXTENSIONS.contains(filename.substring(extensionStart + 1));
    }

    private byte[] readEntry(ZipInputStream zip, ZipEntry entry) throws IOException {
        int maxPartBytes = 12 * 1024 * 1024;
        byte[] bytes = zip.readNBytes(Math.min(EXPANDED - total, maxPartBytes) + 1);
        total += bytes.length;
        require(total <= EXPANDED && bytes.length <= maxPartBytes, "压缩包解压体积过大");
        require(bytes.length < 102400 || entry.getCompressedSize() * 150L >= bytes.length, "压缩比过高");
        return bytes;
    }

    private void inspectPart(String name, byte[] bytes, boolean workbook, Map<String, Document> xml,
                             DocumentBuilder parser, SAXParser budgetParser)
            throws IOException, SAXException, ParserConfigurationException {
        if (name.endsWith(".rels") || name.endsWith(".xml")) {
            budgetParser.parse(new ByteArrayInputStream(bytes), xmlBudget);
            var document = parser.parse(new ByteArrayInputStream(bytes));
            inspectMarkup(document, workbook);
            xml.put(name, document);
        } else if (name.endsWith(WORKBOOK_SUFFIX)) {
            require(!workbook && name.startsWith("word/embeddings/"), "仅允许图表内嵌工作簿");
            read(bytes, true);
        } else {
            inspectImage(name, bytes);
        }
    }

    private void inspectImage(String name, byte[] bytes) {
        boolean media = name.startsWith("word/media/");
        require(media || name.startsWith("docProps/thumbnail"), "图片部件位置非法");
        require(bytes.length <= 1024 * 1024, "单张图片超过大小限制");
        if (media) {
            require(++images <= 10, "模板图片数量超过限制");
        } else {
            require(!hasThumbnail, "模板只允许一个包缩略图");
            hasThumbnail = true;
        }
        Media.validatePicture(bytes, name.endsWith(".png") ? "image/png" : "image/jpeg", pixels -> {
            if (media) {
                imagePixels += pixels;
                require(imagePixels <= 24_000_000, "模板图片像素超过限制");
            }
        });
    }

    private static void validateMainPart(Map<String, Document> xml, String main, boolean workbook) {
        if (!xml.containsKey(main) || !xml.containsKey(CONTENT_TYPES) || !xml.containsKey(ROOT_RELATIONS)) {
            throw unsupported();
        }
        var body = xml.get(main).getDocumentElement();
        String namespace =
                "http://schemas.openxmlformats.org/" + (workbook ? "spreadsheetml" : "wordprocessingml") + "/2006/main";
        require(namespace.equals(body.getNamespaceURI())
                        && (workbook ? "workbook" : "document").equals(body.getLocalName()),
                "包主部件与文件类型不一致");
    }

    private static void validateContentTypes(Document contentTypes, String main, boolean workbook) {
        var manifest = contentTypes.getDocumentElement();
        require("http://schemas.openxmlformats.org/package/2006/content-types".equals(manifest.getNamespaceURI())
                        && "Types".equals(manifest.getLocalName()),
                "包的类型清单格式不正确");
        var types = manifest.getChildNodes();
        boolean mainDeclared = false;
        for (int i = 0; i < types.getLength(); i++) {
            if (!(types.item(i) instanceof Element part))
                continue;
            String type = part.getAttribute("ContentType");
            require(allowedType(type), "不允许的部件类型");
            if (part.getAttribute("PartName").equals("/" + main)) {
                mainDeclared = type.equals(OFFICE
                        + (workbook ? "spreadsheetml.sheet" : "wordprocessingml.document") + ".main+xml");
            }
        }
        if (!mainDeclared)
            throw unsupported();
    }

    private static void validateRelations(PackageParts parts, String main) {
        var embedded = new HashSet<String>();
        for (var part : parts.xml().entrySet()) {
            if (part.getKey().endsWith(".rels"))
                checkRelations(part.getKey(), part.getValue(), parts.names(), main, embedded);
        }
        require(parts.names().stream().filter(name -> name.endsWith(WORKBOOK_SUFFIX)).allMatch(embedded::contains),
                "内嵌工作簿必须属于图表");
    }

    private static boolean allowedType(String type) {
        return OTHER_TYPES.contains(type)
                || type.startsWith(OFFICE) && type.endsWith("+xml")
                && OFFICE_TYPES.contains(type.substring(OFFICE.length(), type.length() - 4));
    }

    private static void inspectMarkup(Document doc, boolean workbook) {
        var nodes = doc.getElementsByTagName("*");
        int count = nodes.getLength();
        for (int i = 0; i < count; i++) {
            var element = (Element) nodes.item(i);
            String name = element.getLocalName();
            require(!UNSUPPORTED_MARKUP.contains(name), "文档包含未支持的可执行或外部内容");
            if (workbook) {
                require(!Set.of("f", "externalReference", "definedName", "connection").contains(name),
                        "内嵌工作簿仅允许数据");
            } else if ("instrText".equals(name) || "fldSimple".equals(name)) {
                String instruction = "instrText".equals(name)
                        ? element.getTextContent()
                        : element.getAttributeNS(element.getNamespaceURI(), "instr");
                require(instruction.length() <= 256 && allowedWordField(instruction.strip()),
                        "仅支持目录和页码域");
            }
        }
    }

    private static boolean allowedWordField(String instruction) {
        var name = WORD_FIELD_NAME.matcher(instruction);
        if (!name.lookingAt()) {
            return false;
        }
        int offset = name.end();
        if (name.group(1) == null) {
            return offset == instruction.length();
        }
        var tocSwitch = TOC_SWITCH.matcher(instruction);
        while (offset < instruction.length()) {
            if (!tocSwitch.region(offset, instruction.length()).lookingAt()) {
                return false;
            }
            offset = tocSwitch.end();
        }
        return true;
    }

    private static void checkRelations(
            String name, Document doc, Set<String> names, String main, Set<String> embedded) {
        String ns = "http://schemas.openxmlformats.org/package/2006/relationships";
        require(ns.equals(doc.getDocumentElement().getNamespaceURI()), "关系部件命名空间不正确");
        var relations = doc.getElementsByTagNameNS(ns, "Relationship");
        int mainRelations = 0;
        String base = "/" + name.substring(0, name.indexOf("_rels/"));
        for (int i = 0; i < relations.getLength(); i++) {
            var relation = (Element) relations.item(i);
            var target = URI.create(relation.getAttribute("Target"));
            var type = relation.getAttribute("Type");
            var kind = type.substring(type.lastIndexOf('/') + 1);
            require(!"External".equalsIgnoreCase(relation.getAttribute("TargetMode")) && !target.isAbsolute()
                            && target.getAuthority() == null && target.getQuery() == null && RELATIONS.contains(kind),
                    "文档包含未允许的关系");
            String resolved = URI.create(base).resolve(target).normalize().getPath();
            require(resolved.startsWith("/") && names.contains(resolved.substring(1)), "内部关系未指向文档部件");
            if (kind.equals("officeDocument")) {
                require(name.equals(ROOT_RELATIONS) && resolved.equals("/" + main), "包主关系未指向正确部件");
                mainRelations++;
            }
            if (kind.equals("package"))
                require(name.startsWith("word/charts/_rels/") && resolved.endsWith(WORKBOOK_SUFFIX)
                                && embedded.add(resolved.substring(1)),
                        "只允许图表绑定内嵌工作簿");
        }
        if (name.equals(ROOT_RELATIONS))
            require(mainRelations == 1, "包须有唯一主文档关系");
    }

    /**
     * 在构建 DOM 前检查包及其内嵌工作簿的累计结构开销。
     */
    private static final class XmlBudget extends DefaultHandler {
        private int elements;
        private int attributes;

        @Override
        public void startElement(String uri, String localName, String qualifiedName, Attributes values) {
            attributes += values.getLength();
            require(++elements <= MAX_XML_ELEMENTS && attributes <= MAX_XML_ATTRIBUTES, "文档结构过于复杂");
        }
    }

    private record PackageParts(Map<String, Document> xml, Set<String> names) {
    }
}
