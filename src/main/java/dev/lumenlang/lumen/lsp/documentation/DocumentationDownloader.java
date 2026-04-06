package dev.lumenlang.lumen.lsp.documentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads and caches Lumen documentation ({@code .ldoc}) from the Lumen website.
 */
public final class DocumentationDownloader {

    private static final String DOWNLOAD_URL = "https://lumenlang.dev/Lumen-documentation.ldoc";
    private static final String CACHE_FILENAME = "Lumen-documentation.ldoc";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private DocumentationDownloader() {
    }

    /**
     * Returns the path where the cached documentation file is stored.
     *
     * @param dataDir the LSP data directory
     * @return the path to the cached file
     */
    public static @NotNull Path cached(@NotNull Path dataDir) {
        return dataDir.resolve(CACHE_FILENAME);
    }

    /**
     * Loads documentation from the local cache if it exists.
     *
     * @param dataDir the LSP data directory
     * @return the parsed documentation data, or null if no cache exists or it cannot be read
     */
    public static @Nullable DocumentationData loadCached(@NotNull Path dataDir) {
        Path file = cached(dataDir);
        if (!Files.isRegularFile(file)) return null;
        try {
            String by = DocumentationLoader.addonName(file.getFileName().toString());
            return DocumentationLoader.load(file, by);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Downloads the documentation and writes it to the cache if the content has changed.
     *
     * @param dataDir the LSP data directory
     * @return true if a new version was written
     */
    public static boolean downloadAndUpdate(@NotNull Path dataDir) {
        try {
            byte[] downloaded = download();
            if (downloaded == null) return false;

            Files.createDirectories(dataDir);
            Path file = cached(dataDir);

            if (Files.isRegularFile(file)) {
                byte[] existing = Files.readAllBytes(file);
                if (sha256(existing).equals(sha256(downloaded))) {
                    return false;
                }
            }

            Path temp = dataDir.resolve(CACHE_FILENAME + ".tmp");
            Files.write(temp, downloaded);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Downloads the documentation from the Lumen website.
     *
     * @return the raw bytes, or null if the download failed
     */
    private static byte @Nullable [] download() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DOWNLOAD_URL))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Computes the SHA-256 hex digest of the given bytes.
     *
     * @param data the input bytes
     * @return the lowercase hex digest string
     */
    private static @NotNull String sha256(byte @NotNull [] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
