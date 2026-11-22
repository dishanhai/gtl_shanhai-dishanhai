package com.dishanhai.gt_shanhai.integration.jei;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Tessellates the small, trusted set of bundled JEI outline icons once at registration. */
final class RecipeButtonSvgMesh {
    static final int VIEW_SIZE = 16;
    private static final int JOIN_SEGMENTS = 12;
    private static final String SVG_NS = "http://www.w3.org/2000/svg";

    record IconMesh(int rgb, List<Quad> quads) {}
    record Point(float x, float y) {}
    // Matches the winding of GuiGraphics.fill. A triangle repeats its first vertex as d.
    record Quad(Point a, Point b, Point c, Point d) {}

    private RecipeButtonSvgMesh() {}

    static IconMesh load(String name) {
        String path = "/assets/gt_shanhai/textures/gui/jei/" + name + ".svg";
        try (InputStream svg = Objects.requireNonNull(RecipeButtonSvgMesh.class.getResourceAsStream(path), path)) {
            return parse(svg);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Invalid built-in JEI icon: " + path, e);
        }
    }

    static IconMesh parse(InputStream svg)
            throws IOException, SAXException, ParserConfigurationException {
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var root = factory.newDocumentBuilder().parse(svg).getDocumentElement();
        if (!SVG_NS.equals(root.getNamespaceURI()) || !"svg".equals(root.getLocalName())
                || !"0 0 16 16".equals(root.getAttribute("viewBox"))
                || !"none".equals(root.getAttribute("fill"))
                || !"round".equals(root.getAttribute("stroke-linecap"))
                || !"round".equals(root.getAttribute("stroke-linejoin"))) {
            throw new IOException("Expected a 16-unit unfilled SVG with round strokes");
        }
        String stroke = root.getAttribute("stroke");
        if (!stroke.matches("#[0-9a-fA-F]{6}")) {
            throw new IOException("Expected an opaque SVG stroke color");
        }
        int rgb = Integer.parseInt(stroke.substring(1), 16);
        float radius = Float.parseFloat(root.getAttribute("stroke-width")) * 0.5f;
        if (!Float.isFinite(radius) || radius <= 0 || radius >= VIEW_SIZE / 2f) {
            throw new IOException("Invalid SVG stroke width");
        }
        List<Quad> quads = new ArrayList<>();
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element line)) continue;
            if (!SVG_NS.equals(line.getNamespaceURI()) || !"polyline".equals(line.getLocalName())
                    || line.getAttributes().getLength() != 1 || !line.hasAttribute("points")) {
                throw new IOException("JEI icons support only direct outlined polylines");
            }
            String[] values = line.getAttribute("points").trim().split("[,\\s]+");
            if (values.length < 4 || values.length % 2 != 0) {
                throw new IOException("Expected at least two x,y coordinate pairs");
            }
            Point previous = null;
            for (int i = 0; i < values.length; i += 2) {
                Point point = new Point(Float.parseFloat(values[i]), Float.parseFloat(values[i + 1]));
                if (!withinView(point.x(), radius) || !withinView(point.y(), radius)) {
                    throw new IOException("SVG stroke crosses the icon bounds");
                }
                if (point.equals(previous)) continue;
                if (previous != null) {
                    appendSegment(quads, previous, point, radius);
                }
                appendRoundJoin(quads, point, radius);
                previous = point;
            }
        }
        if (quads.isEmpty()) throw new IOException("Empty SVG icon");
        return new IconMesh(rgb, List.copyOf(quads));
    }

    private static boolean withinView(float coordinate, float radius) {
        return coordinate >= radius && coordinate <= VIEW_SIZE - radius;
    }

    private static void appendSegment(List<Quad> quads, Point a, Point b, float radius) {
        float dx = b.x() - a.x();
        float dy = b.y() - a.y();
        float scale = radius / (float) Math.hypot(dx, dy);
        float nx = -dy * scale;
        float ny = dx * scale;
        quads.add(new Quad(
                new Point(a.x() - nx, a.y() - ny), new Point(a.x() + nx, a.y() + ny),
                new Point(b.x() + nx, b.y() + ny), new Point(b.x() - nx, b.y() - ny)));
    }

    private static void appendRoundJoin(List<Quad> quads, Point center, float radius) {
        for (int i = 0; i < JOIN_SEGMENTS; i++) {
            quads.add(new Quad(center, circlePoint(center, radius, i + 1), circlePoint(center, radius, i), center));
        }
    }

    private static Point circlePoint(Point center, float radius, int index) {
        double angle = 2 * Math.PI * (index % JOIN_SEGMENTS) / JOIN_SEGMENTS;
        return new Point(center.x() + radius * (float) Math.cos(angle),
                center.y() + radius * (float) Math.sin(angle));
    }
}
