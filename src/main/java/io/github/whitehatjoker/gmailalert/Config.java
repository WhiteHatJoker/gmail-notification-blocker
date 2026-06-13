package io.github.whitehatjoker.gmailalert;

import java.util.Arrays;
import java.util.List;

/**
 * All configuration comes from environment variables (see README / the systemd
 * EnvironmentFile). Required secrets fail fast at startup if missing; everything
 * else has a sensible default.
 */
public record Config(
        String gmailClientId,
        String gmailClientSecret,
        String gmailRefreshToken,
        String anthropicApiKey,
        String anthropicModel,
        String triagePrompt,
        String telegramToken,
        String telegramChatId,
        String gmailQuery,
        String alertedLabel,
        List<String> ignoreSenders,
        List<String> forceImportantSenders,
        List<String> voicemailSenders,
        int pollSeconds,
        int maxBodyChars,
        int maxPerCycle) {

    public static Config fromEnv() {
        return new Config(
                require("GMAIL_CLIENT_ID"),
                require("GMAIL_CLIENT_SECRET"),
                require("GMAIL_REFRESH_TOKEN"),
                require("ANTHROPIC_API_KEY"),
                // Haiku 4.5 is the cheapest model and plenty for URGENT/OK triage.
                // Override here without a rebuild if you want a different model.
                optional("ANTHROPIC_MODEL", "claude-haiku-4-5"),
                // Optional custom triage instructions; falls back to ClaudeClient.DEFAULT_TRIAGE_PROMPT.
                optional("TRIAGE_PROMPT", ClaudeClient.DEFAULT_TRIAGE_PROMPT),
                require("TELEGRAM_TOKEN"),
                require("TELEGRAM_CHAT_ID"),
                optional("GMAIL_QUERY", "is:unread -label:Alerted"),
                optional("ALERTED_LABEL", "Alerted"),
                // Senders to skip entirely (labelled Alerted, never sent to Claude/Telegram).
                csv("IGNORE_SENDERS", ""),
                // Senders always treated as important without asking Claude (regardless of content).
                csv("FORCE_IMPORTANT_SENDERS", ""),
                // Voicemail-notification senders. A voicemail WITH a "Voicemail Preview" transcript
                // goes through Claude normally (judged on the preview text); a voicemail WITHOUT a
                // transcript is alerted unconditionally (can't judge it — go listen).
                csv("VOICEMAIL_SENDERS", ""),
                optionalInt("POLL_SECONDS", 90),
                optionalInt("MAX_BODY_CHARS", 4000),
                optionalInt("MAX_PER_CYCLE", 25));
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return v.trim();
    }

    private static String optional(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    /** Comma-separated env var → lower-cased trimmed list (empty entries dropped). */
    private static List<String> csv(String name, String def) {
        String v = optional(name, def);
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase())
                .toList();
    }

    private static int optionalInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Env var " + name + " must be an integer, got: " + v);
        }
    }
}
