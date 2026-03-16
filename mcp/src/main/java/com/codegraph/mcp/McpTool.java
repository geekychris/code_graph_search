package com.codegraph.mcp;

import java.util.Map;

/**
 * Interface for all MCP tool implementations.
 * Each tool maps to one callable function exposed via the MCP protocol.
 */
public interface McpTool {

    /** The tool name as exposed in the MCP protocol (snake_case). */
    String getName();

    /** Human-readable description of what the tool does. Used by Claude to decide when to call it. */
    String getDescription();

    /**
     * JSON Schema as a Map describing the tool's input parameters.
     * Must include "type": "object" and "properties" at minimum.
     */
    Map<String, Object> getInputSchema();

    /**
     * Execute the tool with the given arguments.
     * The returned Object will be serialized to JSON for the MCP response content.
     *
     * @param args deserialized arguments from the JSON-RPC call
     * @return result object (typically a String formatted for LLM consumption)
     * @throws Exception on any error; the server will convert to a JSON-RPC error response
     */
    Object execute(Map<String, Object> args) throws Exception;
}
