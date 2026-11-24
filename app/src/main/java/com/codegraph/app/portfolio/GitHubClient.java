package com.codegraph.app.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the repositories owned by a GitHub user or organisation.
 *
 * <p>With a token for the owner itself, {@code /user/repos} also returns private repos.
 * For an organisation, {@code /orgs/{org}/repos?type=all} does. Without a token only
 * public repos are listed.
 */
public class GitHubClient implements RepoLister {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private final String apiUrl;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private String authenticatedLogin;

    public GitHubClient(String apiUrl, String token) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public List<RemoteRepo> listRepos(String owner) throws IOException {
        String first;
        if (token != null && owner.equalsIgnoreCase(login())) {
            first = apiUrl + "/user/repos?affiliation=owner&per_page=100";
        } else if (isOrg(owner)) {
            first = apiUrl + "/orgs/" + owner + "/repos?type=all&per_page=100";
        } else {
            first = apiUrl + "/users/" + owner + "/repos?type=owner&per_page=100";
        }

        List<RemoteRepo> repos = new ArrayList<>();
        String url = first;
        while (url != null) {
            HttpResponse<String> resp = get(url);
            for (JsonNode n : mapper.readTree(resp.body())) {
                if (!n.path("owner").path("login").asText().equalsIgnoreCase(owner)) continue;
                repos.add(new RemoteRepo(
                        n.path("name").asText(),
                        n.path("full_name").asText(),
                        n.path("private").asBoolean(true),
                        n.path("fork").asBoolean(false),
                        n.path("archived").asBoolean(false),
                        n.path("clone_url").asText(),
                        n.path("html_url").asText(),
                        n.path("default_branch").asText(null),
                        parseInstant(n.path("pushed_at").asText(null)),
                        n.path("description").isNull() ? null : n.path("description").asText(null),
                        n.path("size").asLong(0)));
            }
            url = nextLink(resp);
        }
        log.info("GitHub: {} repos for {}", repos.size(), owner);
        return repos;
    }

    private String login() throws IOException {
        if (authenticatedLogin == null && token != null) {
            authenticatedLogin = mapper.readTree(get(apiUrl + "/user").body()).path("login").asText("");
        }
        return authenticatedLogin;
    }

    private boolean isOrg(String owner) throws IOException {
        JsonNode user = mapper.readTree(get(apiUrl + "/users/" + owner).body());
        return "Organization".equals(user.path("type").asText());
    }

    private HttpResponse<String> get(String url) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (token != null) req.header("Authorization", "Bearer " + token);
        try {
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("GitHub " + resp.statusCode() + " for " + url);
            }
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted calling GitHub", e);
        }
    }

    private static String nextLink(HttpResponse<String> resp) {
        return resp.headers().firstValue("link").map(h -> {
            Matcher m = NEXT_LINK.matcher(h);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
    }

    private static Instant parseInstant(String s) {
        try { return s == null ? null : Instant.parse(s); } catch (Exception e) { return null; }
    }
}
