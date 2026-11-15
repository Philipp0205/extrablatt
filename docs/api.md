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
they do not sign you in to the website, and the API sets no cookies.

## Endpoints

| Method and path | What it does |
|---|---|
| `GET /api/v1/account` | Your e-mail, whether it is confirmed, your Kindle address, the "mark read on next page" preference and your plan limits. |
| `GET /api/v1/categories` | The categories your feeds are in. |
| `GET /api/v1/feeds` | Your feeds, with unread counts. |
| `POST /api/v1/feeds` | Follow a site: `{"url": "https://example.com", "category": "Tech"}`. `category` is optional. Returns `201` and the feed. |
| `DELETE /api/v1/feeds/{id}` | Unfollow a feed. Returns `204`. |
| `GET /api/v1/items` | Articles, newest first. Query: `unread` (true/false, default false), `feed` (id), `category`, `page` (from 1), `pageSize` (at most the site's page size). |
| `GET /api/v1/articles/{id}` | One article with `contentHtml`, the readable text. Add `?images=true` to keep images. |
| `POST /api/v1/articles/{id}/read` | `{"read": true}` or `{"read": false}`. |
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
| 401 | Token missing, wrong or revoked, or the account is switched off or not confirmed. |
| 403 | Sending was refused (for example the daily send limit). |
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
