package io.github.whitehatjoker.gmailalert;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Thin wrapper over the Gmail API: build from a refresh token, list unread/
 * non-alerted messages, fetch their content, and apply the "Alerted" label.
 *
 * <p>Requires the {@code gmail.modify} scope — adding a label is a write, so
 * {@code gmail.readonly} is NOT sufficient (it returns 403 on {@link #markAlerted}).
 */
public final class GmailClient {

    private static final String USER = "me";

    private final Gmail gmail;
    private final String query;
    private final String alertedLabelName;
    private final int maxBodyChars;
    private final int maxPerCycle;

    private String cachedAlertedLabelId;

    public GmailClient(Config config) throws GeneralSecurityException, IOException {
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(config.gmailClientId())
                .setClientSecret(config.gmailClientSecret())
                .setRefreshToken(config.gmailRefreshToken())
                .build();

        this.gmail = new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("gmail-notification-blocker")
                .build();

        this.query = config.gmailQuery();
        this.alertedLabelName = config.alertedLabel();
        this.maxBodyChars = config.maxBodyChars();
        this.maxPerCycle = config.maxPerCycle();
    }

    /** Lists matching messages and fetches each one's content. */
    public List<EmailMessage> fetchCandidates() throws IOException {
        ListMessagesResponse list = gmail.users().messages().list(USER)
                .setQ(query)
                .setMaxResults((long) maxPerCycle)
                .execute();

        List<EmailMessage> result = new ArrayList<>();
        if (list.getMessages() == null) {
            return result;
        }

        for (Message ref : list.getMessages()) {
            Message full = gmail.users().messages().get(USER, ref.getId())
                    .setFormat("full")
                    .execute();
            result.add(toEmail(full));
        }
        return result;
    }

    /** Adds the Alerted label (creating it on first use) so the message is not reprocessed. */
    public void markAlerted(String messageId) throws IOException {
        String labelId = alertedLabelId();
        gmail.users().messages().modify(USER, messageId,
                        new ModifyMessageRequest().setAddLabelIds(List.of(labelId)))
                .execute();
    }

    private synchronized String alertedLabelId() throws IOException {
        if (cachedAlertedLabelId != null) {
            return cachedAlertedLabelId;
        }
        ListLabelsResponse labels = gmail.users().labels().list(USER).execute();
        if (labels.getLabels() != null) {
            for (Label label : labels.getLabels()) {
                if (alertedLabelName.equalsIgnoreCase(label.getName())) {
                    cachedAlertedLabelId = label.getId();
                    return cachedAlertedLabelId;
                }
            }
        }
        Label created = gmail.users().labels().create(USER, new Label()
                        .setName(alertedLabelName)
                        .setLabelListVisibility("labelShow")
                        .setMessageListVisibility("show"))
                .execute();
        cachedAlertedLabelId = created.getId();
        return cachedAlertedLabelId;
    }

    private EmailMessage toEmail(Message msg) {
        String from = "";
        String subject = "";
        MessagePart payload = msg.getPayload();
        if (payload != null && payload.getHeaders() != null) {
            for (MessagePartHeader h : payload.getHeaders()) {
                if ("From".equalsIgnoreCase(h.getName())) from = h.getValue();
                else if ("Subject".equalsIgnoreCase(h.getName())) subject = h.getValue();
            }
        }
        String body = extractBody(payload);
        if (body.length() > maxBodyChars) {
            body = body.substring(0, maxBodyChars);
        }
        String snippet = msg.getSnippet() == null ? "" : msg.getSnippet();
        return new EmailMessage(msg.getId(), from, subject, snippet, body);
    }

    /** Depth-first search for a text/plain (falling back to text/html) part. */
    private static String extractBody(MessagePart part) {
        if (part == null) return "";

        String mime = part.getMimeType() == null ? "" : part.getMimeType();
        if (mime.startsWith("text/") && part.getBody() != null && part.getBody().getData() != null) {
            return decode(part.getBody().getData());
        }
        if (part.getParts() != null) {
            // Prefer text/plain, then anything else recursively.
            for (MessagePart sub : part.getParts()) {
                if ("text/plain".equalsIgnoreCase(sub.getMimeType())
                        && sub.getBody() != null && sub.getBody().getData() != null) {
                    return decode(sub.getBody().getData());
                }
            }
            for (MessagePart sub : part.getParts()) {
                String found = extractBody(sub);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url));
    }
}
