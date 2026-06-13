package io.github.whitehatjoker.gmailalert;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks Claude whether an email is urgent enough to alert on, using the JDK
 * {@link HttpClient} against the Anthropic Messages API
 * ({@code POST https://api.anthropic.com/v1/messages}).
 *
 * <p>The model is instructed to reply with a single word — URGENT or OK — so the
 * call is cheap ({@code max_tokens} is tiny) and the parse is trivial.
 *
 * <p>Note: the Anthropic API accepts text, images, and PDFs — <b>not audio</b>.
 * Voicemail-notification emails whose audio has no text transcript cannot be judged by
 * Claude; those are alerted unconditionally by the voicemail rule in {@link Main} instead.
 */
public final class ClaudeClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /**
     * Default triage instructions, tuned for on-call support of a SaaS product.
     * Override with the {@code TRIAGE_PROMPT} environment variable.
     */
    public static final String DEFAULT_TRIAGE_PROMPT = """
            You are an on-call IT alert filter for a software company. Reply ONLY with the \
            word URGENT or OK. Mark as URGENT if the message suggests: software or website is \
            down or broken, users cannot login or access the system, forms are not submitting, \
            jobs cannot be closed or completed, anything described as critical, important, ASAP, \
            or time-sensitive, any indication that business operations are blocked or customers \
            are affected. Mark as OK for general questions, billing, feature requests, scheduled \
            meetings, newsletters, promotions, casual conversation, anything that can wait until \
            Monday. Use your judgment on tone — if someone sounds frustrated, panicked, or like \
            their work is completely blocked, mark it urgent even if they do not use specific keywords.""";

    /** Verdict returned for each email. */
    public record Verdict(boolean important, String reason) {
    }

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String triagePrompt;
    private final String model;

    public ClaudeClient(Config config) {
        this.apiKey = config.anthropicApiKey();
        this.triagePrompt = config.triagePrompt();
        this.model = config.anthropicModel();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public Verdict classify(EmailMessage email) throws IOException, InterruptedException {
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", buildUserContent(email));
        JsonArray messages = new JsonArray();
        messages.add(userMessage);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 10);
        body.addProperty("system", triagePrompt);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Anthropic API error " + response.statusCode() + ": " + response.body());
        }

        String text = extractText(response.body());
        boolean urgent = text.toUpperCase().contains("URGENT");
        return new Verdict(urgent, urgent ? "Flagged URGENT by AI filter" : "OK");
    }

    private String buildUserContent(EmailMessage email) {
        return """
                From: %s
                Subject: %s
                Body:
                %s""".formatted(
                safe(email.from()),
                safe(email.subject()),
                safe(email.body().isBlank() ? email.snippet() : email.body()));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Concatenate the text blocks from the Messages API response. */
    private String extractText(String responseBody) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray content = root.getAsJsonArray("content");
            if (content == null || content.isEmpty()) {
                throw new IOException("Claude returned no content: " + responseBody);
            }
            StringBuilder sb = new StringBuilder();
            for (var block : content) {
                JsonObject obj = block.getAsJsonObject();
                if ("text".equals(obj.has("type") ? obj.get("type").getAsString() : "") && obj.has("text")) {
                    sb.append(obj.get("text").getAsString());
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            throw new IOException("Unexpected Claude response shape: " + responseBody, e);
        }
    }
}
