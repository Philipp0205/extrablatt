package com.kindlerss.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The MCP tools served at {@code /mcp}, for the token's own account. Each one calls the
 * matching {@link ApiController} method, so follow, send, listing and the limits behind
 * them are the REST API's and the website's, not a second copy. A failure becomes a tool
 * error carrying only the sentence {@link UserMessages} allows.
 */
@Component
public class McpTools {

    private final ApiController api;
    private final ObjectMapper json;

    public McpTools(ApiController api, ObjectMapper json) {
        this.api = api;
        this.json = json;
    }

    @McpTool(name = "list_feeds", description = "List the feeds you follow, with id, title, url, category and unread count.")
    public CallToolResult listFeeds() {
        return run("Could not list feeds", api::feeds);
    }

    @McpTool(name = "list_categories", description = "List the categories your feeds are in.")
    public CallToolResult listCategories() {
        return run("Could not list categories", api::categories);
    }

    @McpTool(name = "follow_feed", description = "Follow a site or feed by URL. Optionally put it in a category.")
    public CallToolResult followFeed(
            @McpToolParam(description = "Address of the site or its feed") String url,
            @McpToolParam(description = "Category name", required = false) String category) {
        return run("Could not follow that site",
                () -> api.follow(new ApiController.FollowRequest(url, category)).getBody());
    }

    @McpTool(name = "unfollow_feed", description = "Stop following a feed, by feed id.")
    public CallToolResult unfollowFeed(@McpToolParam(description = "Feed id from list_feeds") long id) {
        return run("Could not unfollow that feed", () -> {
            api.unfollow(id);
            return "Unfollowed";
        });
    }

    @McpTool(name = "list_items", description = "List articles, newest first, optionally only unread, "
            + "or of one feed or category. Paginated: see hasMore.")
    public CallToolResult listItems(
            @McpToolParam(description = "Only unread articles (default false)", required = false) Boolean unread,
            @McpToolParam(description = "Feed id", required = false) Long feed,
            @McpToolParam(description = "Category name", required = false) String category,
            @McpToolParam(description = "Page number, from 1", required = false) Integer page,
            @McpToolParam(description = "Articles per page, capped by the site's page size", required = false)
            Integer pageSize) {
        return run("Could not list articles", () -> api.items(Boolean.TRUE.equals(unread), feed, category,
                page == null ? 1 : page, pageSize));
    }

    @McpTool(name = "list_unread", description = "List unread articles, newest first, optionally of one feed "
            + "or category. Paginated: see hasMore.")
    public CallToolResult listUnread(
            @McpToolParam(description = "Feed id", required = false) Long feed,
            @McpToolParam(description = "Category name", required = false) String category,
            @McpToolParam(description = "Page number, from 1", required = false) Integer page,
            @McpToolParam(description = "Articles per page, capped by the site's page size", required = false)
            Integer pageSize) {
        return listItems(true, feed, category, page, pageSize);
    }

    @McpTool(name = "get_article", description = "Get one article with its readable text as HTML.")
    public CallToolResult getArticle(
            @McpToolParam(description = "Article id from list_items") long id,
            @McpToolParam(description = "Keep images (default false)", required = false) Boolean images) {
        return run("Could not get that article", () -> api.article(id, Boolean.TRUE.equals(images)));
    }

    @McpTool(name = "mark_read", description = "Mark one article read, or unread with read=false.")
    public CallToolResult markRead(
            @McpToolParam(description = "Article id") long id,
            @McpToolParam(description = "true for read (default), false for unread", required = false) Boolean read) {
        return run("Could not change that article", () -> api.markRead(id, new ApiController.ReadRequest(read)));
    }

    @McpTool(name = "mark_all_read", description = "Mark unread articles read, optionally only of one feed or "
            + "category. Handles up to 1000 at a time; if hasMore is true, call again.")
    public CallToolResult markAllRead(
            @McpToolParam(description = "Feed id", required = false) Long feed,
            @McpToolParam(description = "Category name", required = false) String category) {
        return run("Could not mark articles read",
                () -> api.markAllRead(new ApiController.MarkReadRequest(feed, category)));
    }

    @McpTool(name = "send_to_kindle", description = "Send an article to your Kindle as an EPUB.")
    public CallToolResult sendToKindle(
            @McpToolParam(description = "Article id") long id,
            @McpToolParam(description = "Keep images (default false)", required = false) Boolean images) {
        return run("Could not send article",
                () -> api.send(id, new ApiController.SendRequest(Boolean.TRUE.equals(images))));
    }

    @McpTool(name = "send_url_to_kindle", description = "Fetch a web page by URL and send it to your Kindle.")
    public CallToolResult sendUrlToKindle(
            @McpToolParam(description = "Address of the article") String url,
            @McpToolParam(description = "Keep images (default false)", required = false) Boolean images) {
        return run("Could not send article",
                () -> api.sendFromUrl(new ApiController.FromUrlRequest(url, Boolean.TRUE.equals(images))));
    }

    @McpTool(name = "get_account", description = "Show your account: e-mail, Kindle address and plan limits.")
    public CallToolResult getAccount() {
        return run("Could not read the account", api::account);
    }

    private CallToolResult run(String fallback, Supplier<Object> action) {
        try {
            Object result = action.get();
            String text = result instanceof String s ? s : json.writeValueAsString(result);
            return CallToolResult.builder().addTextContent(text).isError(false).build();
        } catch (Exception e) {
            return CallToolResult.builder().addTextContent(UserMessages.of(e, fallback)).isError(true).build();
        }
    }
}
