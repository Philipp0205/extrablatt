# REST API

A small JSON API so scripts and agents can use Extrablatt. It does what the website
does, for your account only, and nothing else.

## Get a token

1. Sign in and open **Settings → API tokens**.
2. Give the token a name (for example "my agent") and press **Create token**.
3. Copy the token now. It starts with `eb_` and is shown once. Extrablatt keeps only
   a hash of it, so a lost token cannot be shown again: revoke it and make a new one.

Press **Revoke** on the same page to stop a token working. It stops at once.

## Use it

Send the token in the `Authorization` header with every request:

```
curl -H "Authorization: Bearer eb_..." https://your-extrablatt/api/v1/account
```

Bodies are JSON (`Content-Type: application/json`). Tokens work only under `/api/v1/`;
they do not sign you in to the website, and token requests set no cookies.

## Browser session

The web client does not use a token. It calls the same `/api/v1/` endpoints from the
page, without an `Authorization` header, and is authenticated by the normal
signed-in session cookie. The API never starts a session; with no session the answer
is `401`.

Because the browser sends that cookie by itself, changing requests (`POST`, `PUT`,
`DELETE`) must also prove they come from the page. Every API response sets an
`XSRF-TOKEN` cookie that scripts can read; send its value back in an `X-XSRF-TOKEN`
header. Without it, or with a different value, the answer is `403` and
`{"error": "..."}`. Reads need no header.

A request that has an `Authorization` header is always a token request: the session
is ignored and no CSRF header is needed, even if a session cookie is sent along.
Bearer tokens work exactly as described above, except on the session-only endpoints below.

## Session-only endpoints

A few endpoints change things a stolen token must not be able to change: where sends
go, which tokens exist, and how you can sign in. They work only from the signed-in
browser session (with the CSRF header above). With an API token, valid or not, the
answer is `403`:

```
{"error": "This needs a signed-in browser session, not an API token"}
```

An `Authorization` header of any kind makes the request a token request, so sending a
token along with a session cookie does not help. The session-only endpoints are marked
**Session-only** in the table: `PUT /api/v1/account/kindle-email`,
`POST /api/v1/account/recovery-code`, `POST /api/v1/account/trial-ended/ack`, `POST /api/v1/account/changelog/ack`, and everything under `/api/v1/tokens`.

## Endpoints

| Method and path | What it does |
|---|---|
| `GET /api/v1/account` | Your e-mail, whether it is confirmed, your Kindle address, the "mark read on next page" preference, `readTracking` (`open`, `scroll` or `none`, the Settings, Reading choice) and your plan limits, including `onTrial` and `trialEndsOn` (for example "22 November 2026") while a trial runs. Also `senderAddress` (the address Extrablatt sends from, which you allow-list at Amazon; `null` when none is set), `billingEnabled` (whether this site has plans and billing) and `hasRecoveryCode` (whether you have made a recovery code; the code itself is never shown again). Also `trialEndedPrompt`: `true` only in a browser session, with billing on, after the free trial has ended and before the "Your trial has ended" notice was closed in this session; always `false` for a token. Also `whatsNew`: `null`, or `{"id", "date", "title", "items"}` for the newest release you have not acknowledged yet (the website's "What's new" notice; `items` are its popup lines). |
| `POST /api/v1/account/trial-ended/ack` | **Session-only.** Records that the "Your trial has ended" notice was closed, as the website's Close button does. `trialEndedPrompt` is `false` for the rest of the session; a new sign-in shows the notice again. Returns `204`. |
| `POST /api/v1/account/changelog/ack` | **Session-only.** Marks the latest release as seen, as the website's "Got it" does, so `whatsNew` is `null` until the next release. Returns `204`. |
| `PUT /api/v1/account/kindle-email` | **Session-only.** Saves your Kindle address: `{"kindleEmail": "you@kindle.com"}`. A blank or missing value clears it. Returns `kindleEmail` and `kindleConfigured`. |
| `PUT /api/v1/account/reading` | How articles are marked as read: `{"readTracking": "open"}`, `"scroll"` or `"none"`, the same choice as Settings, Reading. Returns `{"readTracking": "open"}`. `400` ("Choose how articles are marked as read") for anything else. Tokens may use it. |
| `POST /api/v1/account/recovery-code` | **Session-only.** Makes a recovery code, or replaces the one you have so the old one stops working. Returns `{"code": "ABCD-EFGH-JKLM"}`, once, with `Cache-Control: no-store`. Extrablatt keeps only a hash. |
| `GET /api/v1/tokens` | **Session-only.** Your active API tokens: `{"tokens": [{"id", "name", "tokenPrefix", "createdAt", "lastUsedAt"}]}`, newest first. Never the secret or its hash. |
| `POST /api/v1/tokens` | **Session-only.** Makes a token: `{"name": "my agent"}`. Returns `201` and `{"token": {"id", "name", "tokenPrefix", "createdAt", "lastUsedAt"}, "secret": "eb_..."}` with `Cache-Control: no-store`. The `secret` is in this answer only. `400` for a blank or too long name. |
| `DELETE /api/v1/tokens/{id}` | **Session-only.** Revokes a token at once. Returns `204`; `404` ("Token not found") if it is not yours or is already revoked. |
| `GET /api/v1/about` | The running build and the release notes: `{"version": {"number", "revision", "builtAt"}, "releases": [{"id", "date", "title", "highlights", "changes"}]}`, newest first. Nothing personal; tokens may use it. |
| `GET /api/v1/categories` | The categories your feeds are in. |
| `GET /api/v1/feeds` | Your feeds, with unread counts. Each feed has `newsletterSender`, the sender address of a leftover newsletter feed (show it as "From <sender>"), and `null` for every other feed. |
| `POST /api/v1/feeds` | Follow a site: `{"url": "https://example.com", "category": "Tech"}`. `category` is optional. Returns `201` and the feed. |
| `DELETE /api/v1/feeds/{id}` | Unfollow a feed. Returns `204`. |
| `PUT /api/v1/feeds/{id}/category` | Moves a feed to a category: `{"category": "Tech"}`. A blank or missing value means Uncategorized. Returns the updated feed; `404` if it is not yours. |
| `POST /api/v1/categories/rename` | Renames a category across all your feeds: `{"oldCategory": "Tech", "newCategory": "Technology"}`. Returns `updated` (feeds moved, 0 when unchanged), `unchanged` (true when both names are the same) and `message`. `400` for "Uncategorized" or a blank name. |
| `POST /api/v1/feeds/{id}/read` | Marks every unread article of one feed read. Returns `{"marked": 3}`; `404` if the feed is not yours. |
| `GET /api/v1/feeds/suggestions` | The quick-start sites you do not follow yet: `{"suggestions": [{"key": "bbc-world", "title": "BBC World News", "category": "News"}]}`. |
| `POST /api/v1/feeds/defaults` | Follows quick-start sites by key: `{"keys": ["bbc-world", "npr"]}`. Returns `added` and `errors`, a list of sentences for unknown keys and sites that could not be added; the others are still followed. `400` when `keys` is empty or missing. |
| `GET /api/v1/items` | Articles, newest first. Query: `unread` (true/false, default false), `feed` (id), `category`, `page` (from 1), `pageSize` (at most the site's page size). |
| `GET /api/v1/articles/{id}` | One article with `contentHtml`, the readable text, plus `originalUrl` and `commentsUrl` (each `null` when there is none). Add `?images=true` to keep images. |
| `POST /api/v1/articles/{id}/read` | `{"read": true}` or `{"read": false}`. |
| `POST /api/v1/articles/scroll-read` | Marks the articles a reader has scrolled past read: `{"ids": [12, 15]}`. Only for accounts whose `readTracking` is `scroll`; otherwise `409` with `{"error": "Marking articles read by scrolling is off", "marked": 0}` and nothing is marked. Duplicate and `null` ids are ignored, at most 1000 ids are used, and only your own articles are marked. Returns `{"marked": 2}`. Tokens may use it. |
| `POST /api/v1/items/mark-read` | Marks the unread articles read. Optional `{"feed": 3}` or `{"category": "Tech"}` narrows it. Handles up to 1000 at a time; `hasMore: true` means call it again. |
| `POST /api/v1/articles/{id}/send` | Sends the article to your Kindle. Optional `{"images": true}`. |
| `POST /api/v1/articles/from-url` | Fetches a page and sends it: `{"url": "https://...", "images": false}`. The article is kept even if the send fails. |

`GET /api/v1/items` answers with `items`, `page`, `pageSize`, `total`, `totalPages`
and `hasMore`.

## Errors

Every error is JSON: `{"error": "a sentence you can show"}`.

| Status | Meaning |
|---|---|
| 400 | The request was not valid, or the site could not be followed or fetched. |
| 401 | Token missing, wrong or revoked, or the account is switched off or not confirmed. Without a token: no signed-in session. |
| 403 | Sending was refused (for example the daily send limit), a session request had no valid `X-XSRF-TOKEN`, or an API token asked for a session-only endpoint. |
| 404 | No such feed or article for your account. Other people's items look the same. |
| 409 | No Kindle address saved yet. The body also has `"setup": true`. |
| 429 | Too many requests. |
| 502 | The e-mail to your Kindle could not be delivered. |

## Limits

- 60 requests a minute per token. Requests with a missing or bad token are counted
  per client address instead.
- Following a site and sending to Kindle use the same code as the website: the same
  limits on feeds and sends, the same checks that refuse addresses inside private
  networks, and the same Kindle setup and e-mail confirmation rules.
