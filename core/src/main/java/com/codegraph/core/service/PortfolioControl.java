package com.codegraph.core.service;

import java.util.Map;

/**
 * Operations on the GitHub portfolio sync, for the REST and MCP layers.
 * Implemented in the app module, which owns git and GitHub access.
 */
public interface PortfolioControl {

    /** Lists the owners' repos, updates the registry and queues index jobs. Returns a report. */
    Object syncNow();

    /** Queues a fetch and re-index of one tracked repo. False if the repo is unknown. */
    boolean enqueue(String repoId);

    /** Last sync report and queued/running jobs. */
    Map<String, Object> status();

    /** True if the body carries a valid GitHub webhook signature. */
    boolean verifySignature(byte[] body, String signatureHeader);

    /** Acts on a verified webhook and returns a short description of what it did. */
    String handleWebhook(String event, byte[] body);
}
