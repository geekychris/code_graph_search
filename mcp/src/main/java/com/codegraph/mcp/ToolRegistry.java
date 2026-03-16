package com.codegraph.mcp;

import java.util.*;

/**
 * Registry holding all registered McpTool instances.
 * Used by McpServer to dispatch tool calls and return tool listings.
 */
public class ToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    /** Register a tool. If a tool with the same name already exists, it will be replaced. */
    public void register(McpTool tool) {
        tools.put(tool.getName(), tool);
    }

    /** Look up a tool by its MCP name. */
    public Optional<McpTool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Returns the list of tool definitions in MCP protocol format.
     * Each entry has "name", "description", and "inputSchema" keys.
     */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.getName());
            def.put("description", tool.getDescription());
            def.put("inputSchema", tool.getInputSchema());
            list.add(def);
        }
        return list;
    }

    /** Returns the number of registered tools. */
    public int size() {
        return tools.size();
    }
}
