package com.codegraph.app.portfolio;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** Source of the repositories to sync. {@link GitHubClient} in production; a fixed list in tests. */
public interface RepoLister {

    List<RemoteRepo> listRepos(String owner) throws IOException;

    /** One repository as the hosting service describes it. */
    record RemoteRepo(String name, String fullName, boolean isPrivate, boolean fork, boolean archived,
                      String cloneUrl, String htmlUrl, String defaultBranch, Instant pushedAt,
                      String description, long sizeKb) {}
}
