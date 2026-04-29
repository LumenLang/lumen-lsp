package dev.lumenlang.lumen.lsp.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In memory store of open document texts and the latest analysis for each URI.
 */
public final class DocumentStore {

    private final @NotNull ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
    private final @NotNull ConcurrentHashMap<String, AnalysisResult> analyses = new ConcurrentHashMap<>();

    /**
     * Saves the given text under the given URI, replacing any prior version.
     *
     * @param uri  the document URI
     * @param text the document text
     */
    public void text(@NotNull String uri, @NotNull String text) {
        texts.put(uri, text);
    }

    /**
     * Returns the saved text for the given URI, or {@code null} when the
     * document is not open.
     *
     * @param uri the document URI
     * @return the text, or {@code null}
     */
    public @Nullable String text(@NotNull String uri) {
        return texts.get(uri);
    }

    /**
     * Saves the given analysis under the given URI, replacing any prior result.
     *
     * @param uri    the document URI
     * @param result the analysis result
     */
    public void analysis(@NotNull String uri, @NotNull AnalysisResult result) {
        analyses.put(uri, result);
    }

    /**
     * Returns the saved analysis for the given URI, or {@code null} when no
     * analysis has been computed yet.
     *
     * @param uri the document URI
     * @return the analysis, or {@code null}
     */
    public @Nullable AnalysisResult analysis(@NotNull String uri) {
        return analyses.get(uri);
    }

    /**
     * Drops both the text and any cached analysis for the given URI.
     *
     * @param uri the document URI
     */
    public void close(@NotNull String uri) {
        texts.remove(uri);
        analyses.remove(uri);
    }
}
