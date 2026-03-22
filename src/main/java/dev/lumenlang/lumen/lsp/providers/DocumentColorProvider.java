package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.pipeline.minicolorize.tag.ColorTag;
import org.eclipse.lsp4j.Color;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.ColorPresentation;
import org.eclipse.lsp4j.ColorPresentationParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides inline color swatches and a color picker for MiniColorize
 * color tags inside strings. Supports both named colors and hex colors.
 */
public final class DocumentColorProvider {

    private static final Pattern TAG_PATTERN = Pattern.compile("<(/?)([^<>]+)>");

    private static final Map<String, int[]> COLOR_MAP = Map.ofEntries(
            Map.entry("black", new int[]{0x00, 0x00, 0x00}),
            Map.entry("dark_blue", new int[]{0x00, 0x00, 0xAA}),
            Map.entry("dark_green", new int[]{0x00, 0xAA, 0x00}),
            Map.entry("dark_aqua", new int[]{0x00, 0xAA, 0xAA}),
            Map.entry("dark_red", new int[]{0xAA, 0x00, 0x00}),
            Map.entry("dark_purple", new int[]{0xAA, 0x00, 0xAA}),
            Map.entry("gold", new int[]{0xFF, 0xAA, 0x00}),
            Map.entry("gray", new int[]{0xAA, 0xAA, 0xAA}),
            Map.entry("dark_gray", new int[]{0x55, 0x55, 0x55}),
            Map.entry("blue", new int[]{0x55, 0x55, 0xFF}),
            Map.entry("green", new int[]{0x55, 0xFF, 0x55}),
            Map.entry("aqua", new int[]{0x55, 0xFF, 0xFF}),
            Map.entry("red", new int[]{0xFF, 0x55, 0x55}),
            Map.entry("light_purple", new int[]{0xFF, 0x55, 0xFF}),
            Map.entry("yellow", new int[]{0xFF, 0xFF, 0x55}),
            Map.entry("white", new int[]{0xFF, 0xFF, 0xFF})
    );

    /**
     * Finds all color tags in strings and returns color information for each.
     *
     * @param source the document source
     * @return list of color swatches to display
     */
    public @NotNull List<ColorInformation> colors(@Nullable String source) {
        if (source == null) return List.of();

        List<ColorInformation> results = new ArrayList<>();
        String[] lines = source.split("\\r?\\n", -1);

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);

                // skip escaped characters
                if (c == '\\' && i + 1 < line.length()) {
                    i++;
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (!inString) continue;

                if (c != '<') continue;

                int close = line.indexOf('>', i + 1);
                if (close < 0) continue;

                // try to match the tag content as a color
                String full = line.substring(i, close + 1);
                Matcher m = TAG_PATTERN.matcher(full);
                if (!m.matches()) continue;

                String inner = m.group(2);
                Color color = resolve(inner);
                if (color == null) continue;

                Range range = new Range(
                        new Position(lineIdx, i),
                        new Position(lineIdx, close + 1)
                );
                results.add(new ColorInformation(range, color));
                i = close;
            }
        }

        return results;
    }

    /**
     * Converts a color picker selection back into a MiniColorize tag string.
     *
     * @param params the color presentation parameters
     * @return the list of color presentations
     */
    public @NotNull List<ColorPresentation> presentations(@NotNull ColorPresentationParams params) {
        Color c = params.getColor();
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        String hex = String.format("#%02X%02X%02X", r, g, b);

        List<ColorPresentation> result = new ArrayList<>();

        String named = closestNamed(r, g, b);
        if (named != null) {
            ColorPresentation namedPres = new ColorPresentation("<" + named + ">");
            namedPres.setTextEdit(new TextEdit(params.getRange(), "<" + named + ">"));
            result.add(namedPres);
        }

        ColorPresentation hexPres = new ColorPresentation("<" + hex + ">");
        hexPres.setTextEdit(new TextEdit(params.getRange(), "<" + hex + ">"));
        result.add(hexPres);

        return result;
    }

    /**
     * Resolves a MiniColorize tag's inner text to an LSP {@link Color}, supporting both
     * named colors and hex codes.
     *
     * @param inner the tag content string (without angle brackets)
     * @return the resolved color, or null if unrecognized
     */
    private @Nullable Color resolve(@NotNull String inner) {
        String lower = inner.toLowerCase(Locale.ROOT);

        if (lower.startsWith("#") && ColorTag.isValidHex(lower.substring(1))) {
            return hexToColor(lower.substring(1));
        }

        int[] rgb = COLOR_MAP.get(lower);
        if (rgb != null) {
            return new Color(rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0, 1.0);
        }

        for (String prefix : new String[]{"color:", "colour:", "c:"}) {
            if (lower.startsWith(prefix)) {
                String arg = lower.substring(prefix.length());
                if (arg.startsWith("#") && ColorTag.isValidHex(arg.substring(1))) {
                    return hexToColor(arg.substring(1));
                }
                int[] namedRgb = COLOR_MAP.get(arg);
                if (namedRgb != null) {
                    return new Color(namedRgb[0] / 255.0, namedRgb[1] / 255.0, namedRgb[2] / 255.0, 1.0);
                }
            }
        }

        return null;
    }

    /**
     * Converts a 6-digit hex color string to an LSP {@link Color} with full opacity.
     *
     * @param hex a 6-character hex string without the leading hash
     * @return the corresponding LSP color
     */
    private @NotNull Color hexToColor(@NotNull String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new Color(r / 255.0, g / 255.0, b / 255.0, 1.0);
    }

    /**
     * Finds the closest named color to the given RGB values within a fixed distance threshold.
     *
     * @param r the red component 0 to 255
     * @param g the green component 0 to 255
     * @param b the blue component 0 to 255
     * @return the closest named color key, or null if none is close enough
     */
    private @Nullable String closestNamed(int r, int g, int b) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (var entry : COLOR_MAP.entrySet()) {
            int[] c = entry.getValue();
            int dr = r - c[0];
            int dg = g - c[1];
            int db = b - c[2];
            // squared euclidean distance, no sqrt needed for comparison
            int dist = dr * dr + dg * dg + db * db;
            if (dist == 0) return entry.getKey();
            if (dist < bestDist) {
                bestDist = dist;
                best = entry.getKey();
            }
        }
        // threshold of 400 roughly corresponds to ~20 units per channel
        if (bestDist < 400) return best;
        return null;
    }
}
