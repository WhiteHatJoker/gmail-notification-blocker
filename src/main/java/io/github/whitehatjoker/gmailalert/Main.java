package io.github.whitehatjoker.gmailalert;

import java.util.List;
import java.util.logging.Logger;

/**
 * Polling loop: list unread/non-alerted mail → apply sender rules → ask Claude
 * if urgent → alert Telegram if so → label as Alerted → sleep → repeat.
 *
 * <p>Per-message decision order:
 * <ol>
 *   <li>Sender in {@code IGNORE_SENDERS} → skip silently (still labelled, never reprocessed).</li>
 *   <li>Voicemail (sender in {@code VOICEMAIL_SENDERS}) with <b>no</b> transcript preview →
 *       alert unconditionally (Claude can't read the audio, so just notify).</li>
 *   <li>Sender in {@code FORCE_IMPORTANT_SENDERS} → alert without asking Claude.</li>
 *   <li>Otherwise (including voicemails that <b>do</b> carry a preview) → Claude classifies URGENT / OK.</li>
 * </ol>
 *
 * <p>The loop is deliberately crash-resistant: a failure in one cycle (or one
 * message) is logged and the loop continues. systemd restarts hard crashes.
 */
public final class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    /** Phrases that mark an email as a voicemail notification (case-insensitive). */
    private static final String[] VOICEMAIL_KEYWORDS = {"voice message", "voicemail"};
    /** Presence of this phrase means a transcript is included and Claude can judge it. */
    private static final String PREVIEW_MARKER = "voicemail preview";

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        GmailClient gmail = new GmailClient(config);
        ClaudeClient claude = new ClaudeClient(config);
        TelegramClient telegram = new TelegramClient(config);

        log.info("Started. model=" + config.anthropicModel()
                + " query=\"" + config.gmailQuery() + "\""
                + " poll=" + config.pollSeconds() + "s"
                + " ignore=" + config.ignoreSenders()
                + " forceImportant=" + config.forceImportantSenders()
                + " voicemail=" + config.voicemailSenders());

        while (true) {
            try {
                runCycle(config, gmail, claude, telegram);
            } catch (Exception e) {
                log.warning("Cycle failed: " + e);
            }
            Thread.sleep(config.pollSeconds() * 1000L);
        }
    }

    private static void runCycle(Config config, GmailClient gmail, ClaudeClient claude, TelegramClient telegram)
            throws Exception {
        List<EmailMessage> candidates = gmail.fetchCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Processing " + candidates.size() + " candidate message(s)");

        for (EmailMessage email : candidates) {
            try {
                if (senderMatches(email, config.ignoreSenders())) {
                    log.info("ignore [" + email.id() + "] " + email.subject() + " — sender on ignore list");
                    gmail.markAlerted(email.id());
                    continue;
                }

                ClaudeClient.Verdict verdict;
                if (isVoicemailWithoutPreview(email, config.voicemailSenders())) {
                    verdict = new ClaudeClient.Verdict(true, "Voicemail with no transcript, listen to the recording");
                } else if (senderMatches(email, config.forceImportantSenders())) {
                    verdict = new ClaudeClient.Verdict(true, "Forced-important sender");
                } else {
                    // Voicemails WITH a preview land here — Claude judges the transcript text.
                    verdict = claude.classify(email);
                }

                if (verdict.important()) {
                    telegram.sendAlert(email, verdict);
                    log.info("ALERT [" + email.id() + "] " + email.subject() + " — " + verdict.reason());
                } else {
                    log.info("skip  [" + email.id() + "] " + email.subject() + " — " + verdict.reason());
                }
                // Label only after successful handling, so a failure leaves it for retry next cycle.
                gmail.markAlerted(email.id());
            } catch (Exception e) {
                log.warning("Failed on message " + email.id() + " (" + email.subject() + "): " + e);
            }
        }
    }

    /**
     * True for a voicemail-notification email (from a {@code VOICEMAIL_SENDERS} address, mentioning
     * "voice message"/"voicemail") that does <b>not</b> include a transcript preview. Those carry no
     * text Claude could judge, so we alert unconditionally. Voicemails that DO carry a preview return
     * false here and fall through to Claude.
     */
    private static boolean isVoicemailWithoutPreview(EmailMessage email, List<String> voicemailSenders) {
        if (!senderMatches(email, voicemailSenders)) {
            return false;
        }
        String subject = email.subject() == null ? "" : email.subject().toLowerCase();
        String body = email.body() == null ? "" : email.body().toLowerCase();
        boolean isVoicemail = false;
        for (String kw : VOICEMAIL_KEYWORDS) {
            if (subject.contains(kw) || body.contains(kw)) {
                isVoicemail = true;
                break;
            }
        }
        if (!isVoicemail) {
            return false;
        }
        return !body.contains(PREVIEW_MARKER);
    }

    /** True if the email's From header contains any of the given addresses (case-insensitive). */
    private static boolean senderMatches(EmailMessage email, List<String> senders) {
        if (senders.isEmpty() || email.from() == null) {
            return false;
        }
        String from = email.from().toLowerCase();
        return senders.stream().anyMatch(from::contains);
    }

    private Main() {
    }
}
