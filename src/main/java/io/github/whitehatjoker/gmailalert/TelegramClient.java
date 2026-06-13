package io.github.whitehatjoker.gmailalert;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Sends formatted alerts to a Telegram chat via the Bot API (HTML parse mode). */
public final class TelegramClient {

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String sendUrl;
    private final String chatId;

    public TelegramClient(Config config) {
        this.chatId = config.telegramChatId();
        this.sendUrl = "https://api.telegram.org/bot" + config.telegramToken() + "/sendMessage";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void sendAlert(EmailMessage email, ClaudeClient.Verdict verdict) throws IOException, InterruptedException {
        String text = """
                <b>📬 Important email</b>
                <b>From:</b> %s
                <b>Subject:</b> %s

                %s

                <i>Why:</i> %s""".formatted(
                escape(email.from()),
                escape(email.subject()),
                escape(truncate(email.snippet(), 300)),
                escape(verdict.reason()));
        send(text);
    }

    private void send(String text) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        body.addProperty("parse_mode", "HTML");
        body.addProperty("disable_web_page_preview", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sendUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Telegram API error " + response.statusCode() + ": " + response.body());
        }
    }

    /** Escape the five characters that matter for Telegram HTML parse mode. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
