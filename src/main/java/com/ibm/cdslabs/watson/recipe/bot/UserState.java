package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Vertex;

import java.util.Map;

/**
 * Created by markwatson on 11/14/16.
 */
public class UserState {

    private String userId;
    private Vertex lastGraphVertex;
    private Map<String,Object> conversationContext;

    public UserState(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public Vertex getLastGraphVertex() {
        return lastGraphVertex;
    }

    public void setLastGraphVertex(Vertex lastGraphVertex) {
        this.lastGraphVertex = lastGraphVertex;
    }

    public Map<String, Object> getConversationContext() {
        return conversationContext;
    }

    public void setConversationContext(Map<String, Object> conversationContext) {
        this.conversationContext = conversationContext;
    }
}
