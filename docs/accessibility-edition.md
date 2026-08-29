# Accessibility edition — `accessibility.extrablatt.app`

A second face of Extrablatt, designed from the reader outwards for people who
are blind or losing their sight. Not a theme bolted onto the Kindle reader: its
own pages, its own vocabulary, its own idea of what a first visit looks like.

## Who it is for

The design brief came from a user who is losing her vision. In her words, what
she needs is:

- **A black background, large text, and a little extra space between lines.**
  She prints newsletters out that way today because no reader will do it for her.
- **Only the highlights** — headings and bullet points. "Sometimes regular format
  is just way too complicated for me to follow along."
- **Bright, contrasting colours** so that different kinds of things (a heading, a
  quote, a link, a source name) can be told apart at a glance.
- **Topics, not URLs.** She wants to follow *blindness* and *clinical trials* and
  see articles from many sites in one place. She has "always had trouble
  understanding how to input certain websites into RSS" and is not sure she has
  ever used one to its full extent.
- **Bookmarks.** The one feature she names as a thing she loves.
- Everything else she has tried "feels so cluttered, and they don't offer
  accessibility for the blind."

Nothing in that list needs a Kindle. This edition drops the e-reader framing
entirely and keeps Send-to-Kindle only for accounts that have actually set a
Kindle address.

## Shape of the thing

One deployment, one database, one account — two front doors.

```
extrablatt.app                  static landing page          (marketing/)
reader.extrablatt.app           Kindle-first reader          (existing UI)
accessibility.extrablatt.app    accessibility-first reader   (this edition)
```

Both readers are served by the same Spring Boot process against the same
Postgres. That is deliberate:

- one account works on both hosts, with the same feeds, read state and bookmarks;
- feed polling, article extraction, mail and migrations are written once;
- nothing about the accessible edition can rot separately from the app it mirrors.

What is *not* shared is the view layer. The Kindle reader lays text out in fixed
pixel columns and turns pages with JavaScript — hostile to a screen reader,
worse to someone who triples their font size. The accessible edition has its own
templates, its own stylesheet and its own (optional) script.

### How a request finds its edition

`EditionResolver` decides per request, in order:

1. `?display=accessible` / `?display=standard` — explicit switch, remembered in a
   host-only cookie. This also lets someone on `reader.extrablatt.app` turn the
   accessible edition on without knowing the subdomain exists.
2. the `extrablatt-edition` cookie;
3. the request host matching `app.accessibility.domain` (`ACCESSIBILITY_DOMAIN`),
   or — when that is unset — any host whose first label is `accessibility`;
4. otherwise the standard edition.

The unconfigured fallback exists because the alternative fails silently. A
deployment that puts the subdomain in DNS but forgets the variable serves a
working site that answers on the right name with the wrong edition, and the one
reader who would notice is the one who cannot read it.

An interceptor then does two things:

- rewrites the view name (`login` → `accessible/login`) whenever an accessible
  template exists, so shared pages — login, registration, password reset, errors
  — are accessible too;
- redirects the Kindle edition's reading paths to their accessible equivalents
  (`/` → `/topics`, `/items` → `/list`, `/articles/{id}` → `/read/{id}`), so an
  old link or a bookmark still lands somewhere sensible.

The accessible pages live on their own paths (`/topics`, `/list`, `/read/{id}`,
`/saved`, `/display`, `/help`, `/sources`) and are reachable from either host, so
they can be developed and tested locally with no DNS at all.

## What the edition does differently

### 1. You choose topics, not feeds

The first page after signing in is **Topics**. An account with nothing in it is
offered a catalogue of ready-made topics — *Blindness and low vision*, *Clinical
trials and medical research*, *Eye health and research*, *Accessibility and
assistive technology*, *Health news*, *Science*, *World news*, *Technology* — each
one a short plain-language description and a handful of hand-picked sources.
Choosing a topic subscribes to all of its sources at once and files them under
that topic's name. No URL is ever typed.

Topics reuse the existing per-feed `category` column, so a topic in this edition
is a category in the other one, and both stay in sync.

For the case the catalogue does not cover, **Add a website** takes the plain
address of a site ("statnews.com") — no scheme, no "/feed", no XML — and uses the
existing autodiscovery to find its feed, then asks which topic to file it under.

### 2. Reading is the whole point

The article page is built for someone who has to work to read:

- **Key points first.** Headings, list items, emphasised sentences and the lead
  sentences of the article are extracted into a short bulleted summary at the
  top, before the full text. This is the "highlights of the headings or bullet
  points" she prints out by hand today. It is extractive, not generated — no
  model, no API, no cost, nothing invented. The points are not labelled by what
  kind of thing they were: most articles have too little structure to extract
  anything but lead sentences, so every label read "Opening" and told nobody
  anything. The colour of each point's bar still distinguishes them.
- **Colour as structure.** Headings, quotes, list markers, links and source names
  each get their own bright hue against black, so kinds of content are
  distinguishable without reading them.
- **Listen.** A play/pause control reads the article aloud with the browser's own
  speech synthesis, highlighting each paragraph as it is spoken. Free, offline,
  and useful both to someone who cannot read the screen and to someone who can
  but tires quickly. Which voice does the reading is chosen rather than left to
  the browser — see below.
- **Print** keeps her format — dark background and large type, with
  `print-color-adjust: exact` — with an ink-saving alternative one click away.
- Images stay off by default and are announced by their alt text instead.

#### Choosing the voice, not accepting one

Left alone, `speechSynthesis.speak()` uses whichever voice the operating system
lists first. On Windows that is a 1990s formant synthesiser (*Microsoft David*),
on Linux it is eSpeak, and on iOS it is the compact copy of a voice rather than
the full one. All three sound like a robot, which is what a reader who tried the
button reported. The same machines almost always have a modern neural voice
behind the very same API: Edge's *… Online (Natural)* voices, Chrome's *Google*
network voices, Apple's *Premium*/*Enhanced* downloads, Android's Google TTS.

So `a11y.js` ranks the voices it can see, filtered to the language of the page:
neural and premium families score highest, network-served voices next, and
formant synthesisers, cut-down "compact"/"desktop" copies and Apple's novelty
voices (*Zarvox*, *Bad News*, …) are pushed to the bottom. The best one is
selected before the reader presses anything; the whole ranked list is offered in
a **Voice** menu beside the button, which speaks a sample when it changes and is
remembered per device in `localStorage`, along with the speed.

Two consequences of preferring network voices are handled explicitly:

- an utterance that fails (offline, or the voice service refusing) does not stop
  the reading — the best offline voice takes over from the same sentence, for
  that page only;
- Chrome and Safari cut a remote utterance off after about fifteen seconds, so
  paragraphs are queued a sentence or two at a time (≈180 characters), broken at
  punctuation. That also puts a natural pause between sentences. Highlighting
  stays at paragraph level, and the page is not re-scrolled between the pieces of
  one paragraph.

### 3. Display settings that actually change the display

A **Display** page (and `A−` / `A+` in the header of every page) controls:

| Setting | Choices |
|---|---|
| Theme | Black & bright (default), Black & yellow, Black & white, Light high-contrast |
| Text size | 5 steps, from large to very large |
| Line spacing | Normal, roomy, widest |
| Font | Sans-serif (default, prefers Atkinson Hyperlegible if installed), serif |
| Letter spacing | Normal, wider |
| Key points | Shown first (default) or off |

Preferences are stored in a cookie so that logged-out pages (the login form) are
already in the right format, and mirrored into the database so a second device
picks them up after signing in.

### 4. Bookmarks

Every article, in the list and while reading, has a **Save** button, and saved
articles get their own page. Backed by a new `saved_at` column on `articles`, so
they survive being marked read and are available in both editions.

### 5. Accessible by construction

Skip link, one `<h1>` per page, real landmarks, form labels tied to inputs,
`aria-live` for flash messages, `aria-pressed` on toggles, visible 4px focus
rings, targets no smaller than 3rem, and no keyboard trap anywhere. Every action
is an ordinary form post that works with JavaScript switched off; the script only
adds instant feedback and the read-aloud control.

Every page of the edition was checked with axe-core against WCAG 2.0/2.1/2.2 A
and AA plus its best-practice rules, signed in and with real feed content, and
reports no violations. That is a floor, not a finish line — an automated checker
cannot tell whether a page is *usable*, only whether it is malformed.

## Delivery

- `deploy/Caddyfile` gains a third site block for `$ACCESSIBILITY_DOMAIN`, proxying
  the same `app:8080`.
- `ACCESSIBILITY_DOMAIN` is passed to both Caddy (for TLS) and the app (so it can
  recognise its own host and link between editions).
- Unset, everything behaves exactly as before and the edition is reachable only
  through `?display=accessible`.
- The marketing page links to it, because a reader who needs it has no way to
  guess the subdomain.

## Deliberate omissions

- **No separate service, database or codebase.** A second deployment would double
  the operational surface and split her account in two.
- **No text-to-speech on the server.** Cloud voices (OpenAI, ElevenLabs, Azure
  Neural, Google, Polly) are better than the best browser voice, but they bill
  per character, need the article text sent to a third party, add several seconds
  before the first word, and want an audio cache with a size limit. Ranking the
  voices already on the device closes most of the gap for nothing. If it is ever
  added it belongs behind an optional API key, defaulting off, with the browser
  voices as the fallback — not as a replacement for them.
- **No summarising model.** The highlights are the publisher's own headings and
  bullets, not a paraphrase that could quietly get a clinical trial result wrong.
- **No new tracking.** Nothing about a reader's impairment is stored beyond the
  display settings they chose themselves.
