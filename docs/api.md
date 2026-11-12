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

## MCP server

Agents that speak the Model Context Protocol (MCP) can connect to Extrablatt directly,
with no glue code. The server is part of the same app, at `/mcp` (Streamable HTTP, no
sessions), and uses the same personal API tokens as the REST API: create one under
**Settings → API tokens** and send it as `Authorization: Bearer eb_...`. A missing, wrong
or revoked token gets a `401`. The 60 requests a minute per token limit applies here too.

Tools, all for your own account and with the same limits and checks as the REST API:

| Tool | What it does |
|---|---|
| `get_account` | Your e-mail, Kindle address and plan limits. |
| `list_feeds`, `list_categories` | Your feeds with unread counts; your categories. |
| `follow_feed` (`url`, `category`?) / `unfollow_feed` (`id`) | Follow or unfollow a site. |
| `list_items` (`unread`?, `feed`?, `category`?, `page`?, `pageSize`?) | Articles, newest first. |
| `list_unread` (`feed`?, `category`?, `page`?, `pageSize`?) | Same, unread only. |
| `get_article` (`id`, `images`?) | One article with its readable text as HTML. |
| `mark_read` (`id`, `read`?) | Mark one article read (default) or unread. |
| `mark_all_read` (`feed`?, `category`?) | Mark unread articles read, up to 1000 at a time. |
| `send_to_kindle` (`id`, `images`?) | Send an article to your Kindle. |
| `send_url_to_kindle` (`url`, `images`?) | Fetch a page and send it. |

A failed call returns a tool error with a short sentence, such as "Article not found".

### Client setup

Replace `https://your-extrablatt` with your server and `eb_...` with your token.

**Claude Desktop** (`claude_desktop_config.json`). It starts remote servers through the
`mcp-remote` helper:

```json
{
  "mcpServers": {
    "extrablatt": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://your-extrablatt/mcp",
               "--header", "Authorization:${EXTRABLATT_AUTH}"],
      "env": { "EXTRABLATT_AUTH": "Bearer eb_..." }
    }
  }
}
```

Claude Code can add it with one command:
`claude mcp add --transport http extrablatt https://your-extrablatt/mcp --header "Authorization: Bearer eb_..."`

**Cursor** (`~/.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "extrablatt": {
      "url": "https://your-extrablatt/mcp",
      "headers": { "Authorization": "Bearer eb_..." }
    }
  }
}
```

**opencode** (`opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "extrablatt": {
      "type": "remote",
      "url": "https://your-extrablatt/mcp",
      "headers": { "Authorization": "Bearer eb_..." }
    }
  }
}
```
