package com.kindlerss.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Ready-made topics a reader can follow without ever seeing a feed URL.
 *
 * <p>"I have always had trouble understanding how to input certain websites into
 * RSS" is the most common reason people bounce off a reader, and it is a much
 * bigger obstacle when reading the address bar is itself hard work. So the
 * accessible edition leads with subjects — blindness, clinical trials, eye
 * research — and quietly subscribes to a hand-picked set of sources behind each
 * one, filed under the topic's name.
 *
 * <p>A source is given as whatever address is most likely to keep working: the
 * feed itself where it is stable and published, otherwise the site's front page,
 * which {@link FeedService#addFeed} resolves through feed autodiscovery. Every
 * entry here was checked against the live site, and a source that later stops
 * publishing one is named in the message the reader gets rather than failing
 * quietly — the rest of the topic is still added.
 */
@Component
public class TopicCatalog {

    /** One subscribable subject: a name a reader recognises, and the sources behind it. */
    public record Topic(String key, String name, String description, List<Source> sources) {}

    /** One publication inside a topic. {@code url} may be a feed or a homepage. */
    public record Source(String title, String url, String description) {}

    private static final List<Topic> TOPICS = List.of(
            new Topic("blindness", "Blindness and low vision",
                    "News, technology and community for people who are blind or losing their sight.",
                    List.of(
                            new Source("AppleVis", "https://www.applevis.com/",
                                    "Blind and low-vision users reviewing phones, computers and apps."),
                            new Source("National Federation of the Blind", "https://nfb.org/",
                                    "Advocacy, rights and programmes from the largest US organisation of blind people."),
                            new Source("American Council of the Blind", "https://www.acb.org/",
                                    "Community news, audio description and everyday living."),
                            new Source("Perkins School for the Blind", "https://www.perkins.org/",
                                    "Practical guidance on living and learning without sight."),
                            new Source("American Foundation for the Blind", "https://www.afb.org/rss.xml",
                                    "Research, policy and practical guidance on living without sight."),
                            new Source("Blind Bargains", "https://www.blindbargains.com/",
                                    "Deals and news on assistive technology."))),

            new Topic("clinical-trials", "Clinical trials and medical research",
                    "New studies, trial results and the research behind them, in plain reporting.",
                    List.of(
                            new Source("The Lancet", "https://www.thelancet.com/rssfeed/lancet_online.xml",
                                    "Newly published studies from the Lancet."),
                            new Source("STAT News", "https://www.statnews.com/feed/",
                                    "Reporting on medicine, biotech and the business of health."),
                            new Source("US National Institutes of Health",
                                    "https://www.nih.gov/news-events/news-releases",
                                    "Research announcements straight from the NIH."),
                            new Source("Medical Xpress", "https://medicalxpress.com/rss-feed/",
                                    "Daily medical research news."),
                            new Source("The BMJ", "https://www.bmj.com/rss.xml",
                                    "Research and analysis from the British Medical Journal."))),

            new Topic("eye-health", "Eye health and sight research",
                    "Treatments, trials and research specifically about vision loss.",
                    List.of(
                            new Source("Clinical trials now recruiting: eye conditions",
                                    "https://clinicaltrials.gov/api/rss?cond=Eye+Diseases&aggFilters=status:rec",
                                    "Straight from ClinicalTrials.gov, as each one opens."),
                            new Source("Medical Xpress: eyes and vision",
                                    "https://medicalxpress.com/rss-feed/ophthalmology-news/",
                                    "Daily reporting on eye research and treatment."),
                            new Source("Prevent Blindness", "https://preventblindness.org/",
                                    "Patient-facing news on eye conditions and care."),
                            new Source("ScienceDaily: eye care",
                                    "https://www.sciencedaily.com/rss/health_medicine/eye_care.xml",
                                    "New findings on eyes and vision."))),

            new Topic("accessibility", "Accessibility and assistive technology",
                    "Screen readers, magnification, and making the rest of the world usable.",
                    List.of(
                            new Source("WebAIM", "https://webaim.org/blog/feed/",
                                    "Long-running, practical writing on web accessibility."),
                            new Source("TPGi", "https://www.tpgi.com/feed/",
                                    "Accessibility research and screen reader behaviour."),
                            new Source("Deque", "https://www.deque.com/blog/feed/",
                                    "Guides and news from an accessibility tooling team."),
                            new Source("AbilityNet", "https://abilitynet.org.uk/",
                                    "Technology advice for disabled people, UK-based."))),

            new Topic("health", "Health and medicine",
                    "Everyday health reporting, without the jargon.",
                    List.of(
                            new Source("BBC Health", "https://feeds.bbci.co.uk/news/health/rss.xml",
                                    "Health news from the BBC."),
                            new Source("NPR Health", "https://feeds.npr.org/1128/rss.xml",
                                    "Health stories from NPR."),
                            new Source("ScienceDaily: health",
                                    "https://www.sciencedaily.com/rss/health_medicine.xml",
                                    "Medical research summarised daily."),
                            new Source("The Guardian: health",
                                    "https://www.theguardian.com/society/health/rss",
                                    "Health reporting from the Guardian."))),

            new Topic("world-news", "World news",
                    "What is happening, from several newsrooms at once.",
                    List.of(
                            new Source("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml",
                                    "World news from the BBC."),
                            new Source("NPR News", "https://feeds.npr.org/1001/rss.xml",
                                    "US and world news from NPR."),
                            new Source("The Guardian: world",
                                    "https://www.theguardian.com/world/rss",
                                    "World coverage from the Guardian."),
                            new Source("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml",
                                    "International news from Al Jazeera."))),

            new Topic("science", "Science",
                    "Discoveries and research across the sciences.",
                    List.of(
                            new Source("ScienceDaily", "https://www.sciencedaily.com/rss/top/science.xml",
                                    "The day's research news in short pieces."),
                            new Source("Nature", "https://www.nature.com/nature.rss",
                                    "News from one of the main scientific journals."),
                            new Source("Phys.org", "https://phys.org/rss-feed/",
                                    "Physics, space and technology research."),
                            new Source("NASA", "https://www.nasa.gov/",
                                    "Missions, images and space science."))),

            new Topic("technology", "Technology",
                    "Phones, computers and the industry around them.",
                    List.of(
                            new Source("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index",
                                    "In-depth technology reporting."),
                            new Source("The Verge", "https://www.theverge.com/rss/index.xml",
                                    "Consumer technology news."),
                            new Source("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml",
                                    "Technology news from the BBC."),
                            new Source("Hacker News", "https://hnrss.org/frontpage",
                                    "What software developers are reading today."))),

            new Topic("books", "Books, reading and listening",
                    "Reviews, authors, and audiobooks you can listen to for free.",
                    List.of(
                            new Source("NPR Books", "https://feeds.npr.org/1032/rss.xml",
                                    "Book reviews and author interviews."),
                            new Source("The Guardian: books", "https://www.theguardian.com/books/rss",
                                    "Reviews and literary news."),
                            new Source("LibriVox", "https://librivox.org/rss/latest_releases",
                                    "New free public-domain audiobooks, read by volunteers."))),

            new Topic("good-news", "Good news",
                    "Reporting on things that went right, for the days that need it.",
                    List.of(
                            new Source("Positive News", "https://www.positive.news/",
                                    "Constructive journalism from the UK."),
                            new Source("Good News Network", "https://www.goodnewsnetwork.org/feed/",
                                    "Daily uplifting stories."),
                            new Source("Reasons to be Cheerful", "https://reasonstobecheerful.world/feed/",
                                    "Stories about solutions that are working."))));

    public List<Topic> topics() {
        return TOPICS;
    }

    public Optional<Topic> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String wanted = key.trim();
        return TOPICS.stream().filter(topic -> topic.key().equals(wanted)).findFirst();
    }

    /** The catalogue topic whose name a subscribed category came from, if any. */
    public Optional<Topic> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return TOPICS.stream().filter(topic -> topic.name().equalsIgnoreCase(wanted)).findFirst();
    }
}
