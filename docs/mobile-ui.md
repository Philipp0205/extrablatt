# Mobile UI: one continuous, editorial reading list

Extrablatt used to read like an e-reader: the articles list was split into pages that
were turned one at a time, and turning a page marked the one left behind as read. On
a phone that felt slow and surprising. The list is now one scrolling column that grows
with a "Load more articles" button, and nothing is marked read unless the reader asks.

## Design

- **Look.** Warm off-white paper, near-black ink, a black accent (near-white in dark mode), hairline dividers.
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
- Perplexity, a continuous article body with generous paragraph spacing and
  restrained controls, used for the article reading column:
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
| `page`     | First batch on screen. A batch is `ARTICLE_PAGE_SIZE` articles (50 by default). |
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

An article is a plain scrolling page in a reading column. It is immersive: the brand
bar and the bottom tab bar are left off, and a slim row of Back, Send to Kindle and
More starts the page instead (Back is marked as the current place, Articles). Send to
Kindle is offered again where the article ends. Less frequent actions (Original,
Comments, show or hide images, Mark unread) are in a "More" menu, which opens in
its own row and pushes the headline down rather than covering it. Both send buttons
work as plain form posts without JavaScript.

## E-ink Kindles: pages to turn

Scrolling is slow and smeary on e-ink, so an e-ink Kindle gets the list and the
article as pages to turn. Phones, tablets and desktops get the scrolling UI above,
with markup byte for byte the same as before (`EinkLayoutTest`).

- **Detection** (`DeviceLayout`). E-ink means the User-Agent has the word `Kindle`
  and is not a Fire tablet. A Fire says `Silk`, `Kindle Fire`, or a model code like
  `KFTRWI` (`\bKF[A-Z]{2,6}\b`). `Android` alone is no sign of a Fire: the newer
  Chromium-based Kindle browser says `Android 10; K` and `Kindle/3.0+`.
- **Override** (`LayoutInterceptor`). `?layout=kindle` (or `eink`), `?layout=scroll`
  and `?layout=auto` on any GET set or clear the `layout` cookie (one year,
  SameSite=Lax, Secure over HTTPS, readable by script). Settings > Reading has the
  three links. The cookie beats the User-Agent both ways. Pages say
  `Vary: User-Agent, Cookie`.
- **Browser fallback** (`app.js`). A browser the server did not recognise, which
  matches `(update: slow)` or `(monochrome)` and has no `layout` cookie, gets
  `layout=eink-auto` and one reload, at most once per session. Doing it with a reload
  keeps the phone markup free of any e-ink hooks.
- **What the layout adds.** `<html class="eink">`, `eink.css` (pure black on white, no
  shadows, blur, transitions or sticky bars, 48px controls), `reader-boot.js` (hides
  `<main>` until the page is laid out, at most 4 seconds) and `reader.js`.
- **Paging** (`reader.js`, ES5 for old Kindle WebKit). On the list and the article it
  moves the content of `<main>` into a frame laid out in CSS columns one screen
  wide, with Previous, "3 / 12" and Next under it. Tap the left third to go back,
  the rest to go forward; swipe and the arrow and page keys work too. Links and
  buttons in the page work as usual. The page is kept in the address (`#p3`), so
  Back from an article returns to it. Previous on the first page of a later batch
  opens the newer batch at its last page (`#end`). If columns do not work or the
  screen is too small, the frame is taken off and the page scrolls.
- **Load more** stays a plain link on e-ink: the next batch opens as a page of its
  own. One repaint, no fetch on an old browser, and the list stays short.
- Turning pages marks nothing read, as scrolling does not.

## Assets and Content-Security-Policy

- One stylesheet, `static/css/app.css`, and one shared script, `static/js/app.js`. The
  inline stylesheet (`StylesheetAdvice`) and `filters.js` are gone. `eink.css`,
  `reader-boot.js` and `reader.js` load only on the e-ink layout (see above).
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
