package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Vertex;

import java.util.Map;

/**
 * Created by markwatson on 11/14/16.
 */
public class UserState {

    private String userId;
    private Vertex user;
    private Vertex ingredientCuisine;
    private Map<String,Object> conversationContext;

    public UserState(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public Vertex getUser() {
        return user;
    }

    public void setUser(Vertex user) {
        this.user = user;
    }

    public Vertex getIngredientCuisine() {
        return ingredientCuisine;
    }

    public void setIngredientCuisine(Vertex ingredientCuisine) {
        this.ingredientCuisine = ingredientCuisine;
    }

    public Map<String, Object> getConversationContext() {
        return conversationContext;
    }

    public void setConversationContext(Map<String, Object> conversationContext) {
        this.conversationContext = conversationContext;
    }
}
