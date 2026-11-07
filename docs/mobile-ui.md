# Mobile UI: one continuous, editorial reading list

Extrablatt used to read like an e-reader: the articles list was split into pages that
were turned one at a time, and turning a page marked the one left behind as read. On
a phone that felt slow and surprising. The list is now one scrolling column that grows
with a "Load more articles" button, and nothing is marked read unless the reader asks.

## Design

- **Look.** Warm off-white paper, near-black ink, one teal accent, hairline dividers.
  Serif headlines, a sans-serif face for controls and body text. System font stacks
  only, no web fonts. Light and dark themes come from the same CSS variables and
  follow the device setting (`prefers-color-scheme`).
- **Navigation.** Three destinations: Articles, Feeds and Settings. On a phone they sit
  in a bottom tab bar within thumb reach. From 720px wide they move into the top bar
  and the tab bar goes away. The open destination carries `aria-current="page"`.
- **Touch and layout.** Every control is at least 44px tall (`--tap: 2.75rem`). The
  page respects safe-area insets (`viewport-fit=cover`), so the tab bar clears the home
  indicator.
- **Accessibility.** Every page starts with a skip link to `<main id="main">`. An
  unread row says so twice: a dot for the eye and an off-screen "Unread:" for a screen
  reader. Load-more announces what it added through a polite live region and moves
  focus to the first new row. Reduced motion and print are handled.

### References (Mobbin)

The patterns were taken from these screens:

- South China Morning Post, list rhythm and serif headlines:
  https://mobbin.com/screens/0de79387-016a-4861-809f-5901323f066a
- Particle, a source and time line above the headline, quiet row actions:
  https://mobbin.com/screens/547456ac-562e-448f-86ce-b0a63e44257e
- Perplexity, a sideways-scrolling chip row for filters:
  https://mobbin.com/screens/4fd04116-d956-4372-8016-c12e0c2c7960
- Bloomberg, grouped settings rows:
  https://mobbin.com/screens/2c81192d-5454-4619-af69-7676d3043117
- Yahoo Finance, a bottom tab bar with three or four destinations:
  https://mobbin.com/screens/218ad372-b95a-4d02-9e9e-937220c6825d

## The articles list

`GET /items` takes these parameters:

| Parameter  | Meaning |
|------------|---------|
| `feed`     | One feed. |
| `category` | One category. |
| `unread`   | Unread only. On by default. |
| `snapshot` | Time the unread list was taken, so rows read during a visit do not slide away. Added by a redirect when missing. |
| `page`     | First batch on screen (20 articles per batch). |
| `through`  | Last batch on screen. Load-more writes it into the address with `history.replaceState`, so a reload or the back button shows every loaded batch in place. Capped at 10 batches per address. |
| `batch`    | `true` answers with the rows of one batch only (`fragments/article-rows :: batch`), which `app.js` appends. Past the end it answers with an empty batch, not the last page again. A batch request does not start a feed refresh. |

Without JavaScript, "Load more articles" is a plain link to the next page and "Newer
articles" leads back, so the list still works.

Load-more is **click only**, never triggered by scrolling. An endless list would push
the site footer out of reach, and the footer holds the legal links, including the
cancellation link that German law (§ 312k BGB) requires to be easy to find.

### Marking as read

Loading and scrolling are not reading. Nothing on the list marks an article read except
the explicit **Mark all as read** button under it, and opening an article.

`POST /items/mark-read` takes the list's own parameters plus one `id` per row. On submit,
`app.js` rebuilds the ids from every row on screen, including the ones load-more added,
so an article the reader never saw stays unread. Ids are deduplicated and capped at
1000. Afterwards an unread list starts fresh from a new snapshot, and any other list
comes back to the same page and range.

Removed endpoints:

- `POST /items/advance` (turn a page and mark it read)
- `POST /items/read` (the JSON variant used by the paged reader)

### The preference

The `mark_read_on_next_page` column and the `markReadOnNextPage` field are kept, so no
migration was needed. Page turns no longer exist, so the setting is now worded for what
it still controls: **Keep track of unread articles**. When it is off, new articles
arrive already marked read. `FeedService` is unchanged.

## Articles

An article is a plain scrolling page in a reading column. Send to Kindle sits at the
top and again where the article ends. Less frequent actions (Original, Comments,
show or hide images, Mark unread) are in a "More" menu. Both send buttons work as
plain form posts without JavaScript.

## Assets and Content-Security-Policy

- One stylesheet, `static/css/app.css`, and one shared script, `static/js/app.js`. The
  inline stylesheet (`StylesheetAdvice`) and the reader scripts (`reader.js`,
  `reader-boot.js`, `filters.js`) are gone.
- Both are served under a content hash (`/css/app-<hash>.css`, `/js/app-<hash>.js`)
  with `Cache-Control: max-age=31536000, public, immutable`, and fetching them starts
  no session.
- No template carries a `style` attribute, a `<style>` element, an inline event
  handler or an inline `<script>`. Confirmation prompts use `data-confirm`.
  `TemplateCspReadinessTest` checks every template file and `PageStylingTest` checks
  the rendered pages.

The pages therefore already fit this policy:

```
default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' https: data:
```

This change does **not** send that header. Adding it belongs with the security work.

### Coordination

- `SecurityConfig.staticAssetFilterChain` now matches `/css/**` as well as `/js/**`.
  It is a one-line change, but it is in security code and should be merged with the
  security branch in mind.
- Billing and ops code was not touched beyond moving their templates onto the shared
  layout and stylesheet.
