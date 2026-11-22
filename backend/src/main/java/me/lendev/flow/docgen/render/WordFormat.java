package me.lendev.flow.docgen.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 统一模板和成品的 Word 格式。图片及图表工作簿直接沿用原包部件。
 */
final class WordFormat {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    private static final String FONT = "SimSun";
    private static final String EXTENSION_LIST = "extLst";
    private static final List<String> FONT_SCRIPTS = List.of("ascii", "hAnsi", "eastAsia", "cs");
    private static final List<String> FONT_THEMES = List.of("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme", "csTheme");
    private static final Set<String> SETTINGS_AFTER_COMPAT = Set.of("docVars", "rsids", "attachedSchema",
            "themeFontLang", "clrSchemeMapping", "doNotIncludeSubdocsInStats", "doNotAutoCompressPictures",
            "forceUpgrade", "captions", "readModeInkLockDown", "smartTagType", "doNotEmbedSmartTags",
            "decimalSymbol", "listSeparator");
    private static final Set<String> CHART_AFTER_TEXT =
            Set.of("externalData", "printSettings", "userShapes", EXTENSION_LIST);

    private WordFormat() {
    }

    static byte[] normalize(byte[] bytes) {
        Map<String, Document> xml = new OfficePackage().inspect(bytes);
        try (var pack = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            var main = pack.getPart(PackagingURIHelper.createPartName("/word/document.xml"));
            wordPart(pack, main, xml, XWPFRelation.SETTINGS, "settings");
            wordPart(pack, main, xml, XWPFRelation.STYLES, "styles");

            var factory = TransformerFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            for (var entry : xml.entrySet()) {
                var name = entry.getKey();
                var document = entry.getValue();
                if (!name.startsWith("word/") || !name.endsWith(".xml") || !format(document))
                    continue;
                var part = pack.getPart(PackagingURIHelper.createPartName("/" + name));
                try (var content = part.getOutputStream()) {
                    transformer.transform(new DOMSource(document), new StreamResult(content));
                }
            }
            var output = new ByteArrayOutputStream(bytes.length);
            pack.save(output);
            byte[] result = output.toByteArray();
            new OfficePackage().inspect(result);
            return result;
        } catch (RenderFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RenderFailure("UNSUPPORTED_FILE_TYPE", "$", "无法统一 DOCX 的 Word 兼容设置和字体");
        }
    }

    private static void wordPart(OPCPackage pack, PackagePart main, Map<String, Document> xml,
                                 XWPFRelation relation, String localName) throws InvalidFormatException {
        var links = main.getRelationshipsByType(relation.getRelation());
        if (links.size() > 0) return;
        var name = PackagingURIHelper.createPartName(relation.getDefaultFileName());
        if (!pack.containPart(name)) {
            pack.createPart(name, relation.getContentType());
            var implementation = xml.get("word/document.xml").getImplementation();
            xml.put(name.getName().substring(1), implementation.createDocument(WORD, "w:" + localName, null));
        }
        main.addRelationship(name, TargetMode.INTERNAL, relation.getRelation());
    }

    private static boolean format(Document document) {
        var root = document.getDocumentElement();
        boolean changed = false;
        if (named(root, WORD, "settings")) {
            compatibility(root);
            elements(root, WORD, "updateFields").forEach(field -> field.getParentNode().removeChild(field));
            changed = true;
        }
        if (named(root, WORD, "styles")) {
            var defaults = ensure(root, WORD, "w:docDefaults", Set.of("style", "latentStyles"));
            var runDefault = ensure(defaults, WORD, "w:rPrDefault", Set.of("pPrDefault"));
            var properties = ensure(runDefault, WORD, "w:rPr", Set.of());
            ensure(properties, WORD, "w:rFonts", Set.of("b", "i", "sz", "color", "lang"));
            changed = true;
        }
        if (named(root, WORD, "numbering")) {
            normalizeBullets(root);
            changed = true;
        }
        if (named(root, CHART, "chartSpace")) {
            ensureChartTextDefaults(root);
            changed = true;
        }
        for (var font : elements(root, WORD, "rFonts")) {
            for (String script : FONT_SCRIPTS) font.setAttributeNS(WORD, "w:" + script, FONT);
            for (String theme : FONT_THEMES) font.removeAttributeNS(WORD, theme);
            changed = true;
        }
        for (String script : List.of("latin", "ea", "cs", "font")) {
            for (var font : elements(root, DRAWING, script)) {
                font.setAttribute("typeface", FONT);
                changed = true;
            }
        }
        return removeDirtyFields(root) || changed;
    }

    private static void ensureChartTextDefaults(Element root) {
        var properties = ensure(root, CHART, "c:txPr", CHART_AFTER_TEXT);
        ensure(properties, DRAWING, "a:bodyPr", Set.of("lstStyle", "p"));
        ensure(properties, DRAWING, "a:lstStyle", Set.of("p"));
        var paragraph = ensure(properties, DRAWING, "a:p", Set.of());
        var paragraphProperties = ensure(paragraph, DRAWING, "a:pPr", Set.of("r", "br", "fld", "endParaRPr"));
        var defaults = ensure(paragraphProperties, DRAWING, "a:defRPr", Set.of(EXTENSION_LIST));
        for (String script : List.of("latin", "ea", "cs"))
            ensure(defaults, DRAWING, "a:" + script,
                    Set.of("sym", "hlinkClick", "hlinkMouseOver", "rtl", EXTENSION_LIST));
    }

    // 目录和页码域保持可编辑，移除打开时请求 Word 刷新的标记。
    private static boolean removeDirtyFields(Element root) {
        boolean changed = false;
        for (String fieldName : List.of("fldChar", "fldSimple")) {
            for (var field : elements(root, WORD, fieldName)) {
                if (!field.hasAttributeNS(WORD, "dirty")) continue;
                field.removeAttributeNS(WORD, "dirty");
                changed = true;
            }
        }
        return changed;
    }

    private static void compatibility(Element settings) {
        var compat = ensureCompatibility(settings);
        Element mode = null;
        for (var setting : elements(compat, WORD, "compatSetting")) {
            if (!"compatibilityMode".equals(setting.getAttributeNS(WORD, "name"))) continue;
            if (mode == null) mode = setting;
            else setting.getParentNode().removeChild(setting);
        }
        if (mode == null) {
            mode = settings.getOwnerDocument().createElementNS(WORD, "w:compatSetting");
            compat.appendChild(mode);
        }
        mode.setAttributeNS(WORD, "w:name", "compatibilityMode");
        mode.setAttributeNS(WORD, "w:uri", "http://schemas.microsoft.com/office/word");
        mode.setAttributeNS(WORD, "w:val", "15");
    }

    private static Element ensureCompatibility(Element settings) {
        var compat = child(settings, WORD, "compat");
        if (compat != null)
            return compat;
        var element = settings.getOwnerDocument().createElementNS(WORD, "w:compat");
        for (var node = settings.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element e
                    && (!WORD.equals(e.getNamespaceURI()) || SETTINGS_AFTER_COMPAT.contains(e.getLocalName()))) {
                settings.insertBefore(element, node);
                return element;
            }
        }
        settings.appendChild(element);
        return element;
    }

    private static void normalizeBullets(Element numbering) {
        for (var level : elements(numbering, WORD, "lvl")) {
            var format = child(level, WORD, "numFmt");
            var text = child(level, WORD, "lvlText");
            if (format != null && text != null && "bullet".equals(format.getAttributeNS(WORD, "val"))
                    && text.getAttributeNS(WORD, "val").codePoints().anyMatch(cp -> Character.getType(cp) == Character.PRIVATE_USE)) {
                // 旧符号字体项目符号使用私用区编码，换用宋体可显示的 Unicode 项目符号。
                text.setAttributeNS(WORD, "w:val", "•");
            }
        }
    }

    private static Element ensure(Element parent, String ns, String qualified, Set<String> successors) {
        String name = qualified.substring(qualified.indexOf(':') + 1);
        var present = child(parent, ns, name);
        if (present != null) return present;
        var element = parent.getOwnerDocument().createElementNS(ns, qualified);
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element e && successors.contains(e.getLocalName())) {
                parent.insertBefore(element, node);
                return element;
            }
        }
        parent.appendChild(element);
        return element;
    }

    private static Element child(Element parent, String ns, String local) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element e && named(e, ns, local)) return e;
        }
        return null;
    }

    private static boolean named(Element element, String ns, String local) {
        return ns.equals(element.getNamespaceURI()) && local.equals(element.getLocalName());
    }

    private static List<Element> elements(Element root, String ns, String name) {
        var children = root.getElementsByTagNameNS(ns, name);
        var result = new ArrayList<Element>(children.getLength());
        for (int i = 0; i < children.getLength(); i++) result.add((Element) children.item(i));
        return result;
    }
}
