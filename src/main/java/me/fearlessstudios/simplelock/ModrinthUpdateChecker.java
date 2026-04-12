package me.fearlessstudios.simplelock;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModrinthUpdateChecker {

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;

    public ModrinthUpdateChecker() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String fetchLatestVersion(String projectId, String[] loaders, String gameVersion) throws IOException, InterruptedException {
        String loaderJson = Arrays.stream(loaders)
                .map(loader -> "\"" + loader + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
        String encodedLoader = URLEncoder.encode(loaderJson, StandardCharsets.UTF_8);
        String encodedGameVersion = URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);
        URI uri = URI.create(
                "https://api.modrinth.com/v2/project/" + projectId + "/version"
                        + "?loaders=" + encodedLoader
                        + "&game_versions=" + encodedGameVersion
        );

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "SimpleLock/1.1.0 (fearlessstudios)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth returned HTTP " + response.statusCode());
        }

        Matcher matcher = VERSION_PATTERN.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("No version_number field was found in the Modrinth response.");
        }

        return matcher.group(1);
    }

    public static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[^A-Za-z0-9]+");
        String[] rightParts = right.split("[^A-Za-z0-9]+");
        int max = Math.max(leftParts.length, rightParts.length);

        for (int i = 0; i < max; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "0";
            String rightPart = i < rightParts.length ? rightParts[i] : "0";

            int comparison = compareVersionPart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private static int compareVersionPart(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);

        if (leftNumeric && rightNumeric) {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        }

        return left.compareToIgnoreCase(right);
    }
}
