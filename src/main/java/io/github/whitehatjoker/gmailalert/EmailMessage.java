package io.github.whitehatjoker.gmailalert;

/** A single Gmail message reduced to the fields we care about. */
public record EmailMessage(
        String id,
        String from,
        String subject,
        String snippet,
        String body) {
}
