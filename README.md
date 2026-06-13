# Gmail Notification Blocker

A small Java service that watches a Gmail inbox, asks Claude whether each new email is urgent, and sends a Telegram alert only for the ones that are. You can turn off email notifications on your phone and still never miss the message that matters.

I built it for my own on-call support work, to replace a phone-based Tasker automation, and ran it as a systemd service on a small Linux server.

![Java](https://img.shields.io/badge/Java-21-orange) ![Maven](https://img.shields.io/badge/Build-Maven%20(shaded%20jar)-c71a36) ![Claude](https://img.shields.io/badge/AI-Claude%20API-d97757) ![Gmail](https://img.shields.io/badge/Gmail-API%20%2B%20OAuth2-ea4335) ![Telegram](https://img.shields.io/badge/Alerts-Telegram%20Bot-26a5e4) ![systemd](https://img.shields.io/badge/Deploy-systemd-555)

## How it works

No database. State is kept in Gmail itself with a label (`Alerted`), so every processed message is marked and never handled twice.

```
every 90 s:
  Gmail: list  is:unread -label:Alerted
     │
     ▼
  sender rules ──► ignore list       → label, skip
              ├──► force-important   → alert
              └──► voicemail, no transcript → alert
     │
     ▼
  Claude: "URGENT or OK?"  (one-word answer, tiny max_tokens)
     │
     ├── URGENT → Telegram alert (sender, subject, snippet, reason)
     ▼
  Gmail: add label Alerted
```

**Design choices:**
- **Plain Java 21, no framework.** Claude and Telegram are called with the JDK's built-in `HttpClient`; Gmail uses the official Google client library. The whole app is six small classes.
- **Cheap triage.** The model answers with a single word, and the body is capped at 4,000 characters, so each email costs a fraction of a cent on Claude Haiku.
- **Crash-resistant loop.** A failure on one message or one cycle is logged and skipped. A message is only labelled after it was handled, so failures are retried next cycle. systemd restarts the process on a hard crash.
- **Config only through environment variables.** Required secrets fail fast at startup; everything else has a default.

## Sender rules

These run before Claude, matched case-insensitively against the `From` header:

| Rule | Env var | Behaviour |
|------|---------|-----------|
| Ignore | `IGNORE_SENDERS` | Labelled and dropped, never sent to Claude or Telegram. For noisy automated mail. |
| Force-important | `FORCE_IMPORTANT_SENDERS` | Always alerted, without asking Claude. |
| Voicemail | `VOICEMAIL_SENDERS` | For phone-system voicemail emails (e.g. `notify@ringcentral.com`). With a "Voicemail Preview" transcript, Claude judges the transcript like any email. Without one, it's alerted unconditionally, because Claude can't listen to audio. |

## Configuration

Required:

| Var | Description |
|-----|-------------|
| `GMAIL_CLIENT_ID` | Google OAuth client ID |
| `GMAIL_CLIENT_SECRET` | Google OAuth client secret |
| `GMAIL_REFRESH_TOKEN` | Refresh token for the mailbox (`gmail.modify` scope) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `TELEGRAM_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_CHAT_ID` | Chat to send alerts to |

Optional (defaults shown):

| Var | Default | Notes |
|-----|---------|-------|
| `ANTHROPIC_MODEL` | `claude-haiku-4-5` | Cheapest model; plenty for URGENT/OK |
| `TRIAGE_PROMPT` | built-in on-call prompt | Your own instructions; the model must reply URGENT or OK |
| `IGNORE_SENDERS` | *(empty)* | Comma-separated |
| `FORCE_IMPORTANT_SENDERS` | *(empty)* | Comma-separated |
| `VOICEMAIL_SENDERS` | *(empty)* | Comma-separated |
| `GMAIL_QUERY` | `is:unread -label:Alerted` | Any Gmail search syntax, e.g. add `newer_than:1d` |
| `ALERTED_LABEL` | `Alerted` | Created automatically if missing |
| `POLL_SECONDS` | `90` | Pause between cycles |
| `MAX_BODY_CHARS` | `4000` | Body characters sent to Claude |
| `MAX_PER_CYCLE` | `25` | Messages fetched per cycle |

A template with every option is in [`deploy/gmail-notification-blocker.env.example`](deploy/gmail-notification-blocker.env.example).

The built-in prompt is tuned for on-call support of a software product: outages, login problems, broken forms and anything time-sensitive are URGENT; questions, billing, newsletters and anything that can wait are OK. Set `TRIAGE_PROMPT` to describe what urgent means for you.

### Gmail OAuth: use the `gmail.modify` scope

Adding the `Alerted` label is a write operation, so the read-only scope fails with **403**. Create the refresh token with:

```
https://www.googleapis.com/auth/gmail.modify
```

## Run locally

The app reads plain environment variables; it does not load a `.env` file itself.

```bash
cp deploy/gmail-notification-blocker.env.example .env   # fill in real values (.env is gitignored)
mvn -q clean package

set -a; source .env; set +a
java -jar target/gmail-notification-blocker.jar
```

## Deploy as a systemd service (Ubuntu, no Docker)

```bash
# 1. Build
sudo git clone https://github.com/WhiteHatJoker/gmail-notification-blocker.git /opt/gmail-notification-blocker
cd /opt/gmail-notification-blocker && sudo mvn -q clean package

# 2. Unprivileged service user and a root-only secrets file
sudo useradd --system --no-create-home --shell /usr/sbin/nologin gmailalert
sudo cp deploy/gmail-notification-blocker.env.example /etc/gmail-notification-blocker.env
sudo nano /etc/gmail-notification-blocker.env
sudo chown root:root /etc/gmail-notification-blocker.env && sudo chmod 600 /etc/gmail-notification-blocker.env

# 3. Install and start
sudo cp deploy/gmail-notification-blocker.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now gmail-notification-blocker
sudo journalctl -u gmail-notification-blocker -f
```

The unit runs as the `gmailalert` user with `NoNewPrivileges`, `ProtectSystem=full` and `ProtectHome`, and restarts automatically (`Restart=always`). systemd reads the secrets file as root and passes the values in, so the file stays `600 root:root`.

> **First start:** the default query has no date filter, so the service works through your whole unread backlog, 25 messages per cycle. Add `newer_than:1d` to `GMAIL_QUERY`, or mark old mail as read first.

To update: `git pull`, `mvn -q clean package`, `systemctl restart gmail-notification-blocker`.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| 403 when adding the label | Refresh token has `gmail.readonly`; create a new one with `gmail.modify` |
| `invalid_grant` on startup | Refresh token revoked or expired; redo the OAuth consent |
| `Missing required env var: ...` | Env file not loaded or a variable name is misspelled |
| Anthropic 404 / model not found | `ANTHROPIC_MODEL` is not a current model id |
| Anthropic 401 | `ANTHROPIC_API_KEY` missing or invalid |
| Mail arrives but no alerts | Check the logs: Claude may be answering OK, or the sender is on the ignore list |

To re-test a message, remove its `Alerted` label in Gmail; it's picked up again on the next cycle.

## Project structure

```
src/main/java/io/github/whitehatjoker/gmailalert/
  Main.java            Polling loop and sender rules
  Config.java          Environment-variable configuration
  GmailClient.java     List, read and label messages (Gmail API, OAuth2 refresh token)
  ClaudeClient.java    URGENT/OK classification (Anthropic Messages API)
  TelegramClient.java  Alert formatting and delivery (Telegram Bot API)
  EmailMessage.java    Email record
deploy/
  gmail-notification-blocker.service    systemd unit
  gmail-notification-blocker.env.example
```

---

Author: Ravshan Khudoyberdiev.
