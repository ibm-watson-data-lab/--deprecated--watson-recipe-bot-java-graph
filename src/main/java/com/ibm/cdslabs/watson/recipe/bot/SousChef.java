package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Edge;
import com.ibm.graph.client.Element;
import com.ibm.graph.client.IBMGraphClient;
import com.ibm.graph.client.Vertex;
import com.ibm.graph.client.schema.*;
import com.ibm.watson.developer_cloud.conversation.v1.ConversationService;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageRequest;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageResponse;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by markwatson on 11/11/16.
 */
public class SousChef {

    private RecipeGraph recipeGraph;
    private String slackBotId;
    private String conversationWorkspaceId;
    private SlackSession slackSession;
    private RecipeClient recipeClient;
    private ConversationService conversationService;
    private HashMap<String, UserState> userStateMap = new HashMap<String, UserState>();

    private static Logger logger = LoggerFactory.getLogger(SousChef.class);

    public SousChef(RecipeGraph recipeGraph, String slackToken, String slackBotId, String recipeClientApiKey, String conversationUsername, String conversationPassword, String conversationWorkspaceId) {
        this.recipeGraph = recipeGraph;
        this.slackBotId = slackBotId;
        this.slackSession = SlackSessionFactory.createWebSocketSlackSession(slackToken);
        this.recipeClient = new RecipeClient(recipeClientApiKey);
        this.conversationService = new ConversationService(ConversationService.VERSION_DATE_2016_07_11);
        this.conversationService.setUsernameAndPassword(conversationUsername, conversationPassword);
        this.conversationWorkspaceId = conversationWorkspaceId;
    }

    public void run() throws Exception {
        this.recipeGraph.initGraph();
        this.slackSession.connect();
        this.slackSession.addMessagePostedListener(new SlackMessagePostedListener() {
            public void onEvent(SlackMessagePosted event, SlackSession session) {
                SlackChannel channel = event.getChannel();
                String messageContent = event.getMessageContent();
                SlackUser messageSender = event.getSender();
                if (channel.getType() == SlackChannel.SlackChannelType.INSTANT_MESSAGING) {
                    if (!messageSender.getId().equals(slackBotId)) {
                        try {
                            processSlackMessage(messageSender.getId(), messageContent, channel);
                        }
                        catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    else {
                        logger.debug("Received my message.");
                    }
                }
            }
        });
    }

    public void stop() throws Exception {
        this.slackSession.disconnect();
    }

    private void processSlackMessage(String messageSender, String message, SlackChannel channel) throws Exception {
        UserState state = userStateMap.get(messageSender);
        if (state == null) {
            state = new UserState(messageSender);
            userStateMap.put(messageSender, state);
        }
        MessageRequest request = new MessageRequest.Builder().inputText(message).context(state.getConversationContext()).build();
        MessageResponse response = this.conversationService.message(this.conversationWorkspaceId, request).execute();
        state.setConversationContext(response.getContext());
        String reply;
        if (state.getConversationContext().containsKey("is_ingredients") && Boolean.TRUE.equals(state.getConversationContext().get("is_ingredients"))) {
            reply = this.handleIngredientsMessage(state, message);
        }
        else if (state.getConversationContext().containsKey("is_selection") && Boolean.TRUE.equals(state.getConversationContext().get("is_selection"))) {
            state.getConversationContext().put("selection_valid", Boolean.FALSE);
            reply = "Invalid selection! Say anything to see your choices again...";
            int selection = -1;
            if (state.getConversationContext().containsKey("selection")) {
                try {
                    selection = Integer.parseInt(state.getConversationContext().get("selection").toString());
                }
                catch (Exception ex) {
                }
            }
            if (selection >= 1 && selection <= 5) {
                state.getConversationContext().put("selection_valid", Boolean.FALSE);
                reply = this.handleSelectionMessage(state, selection);
            }
        }
        else if (response.getEntities() != null && response.getEntities().size() > 0 && response.getEntities().get(0).getEntity() == "cuisine") {
            String cuisine = response.getEntities().get(0).getValue();
            reply = this.handleCuisineMessage(state, cuisine);
        }
        else {
            reply = this.handleStartMessage(state, response);
        }
        this.slackSession.sendMessage(channel, reply);
    }

    // Messages from Bot

    private String handleStartMessage(UserState state, MessageResponse response) throws Exception {
        String reply = "";
        for (String text : ((ArrayList<String>) response.getOutput().get("text"))) {
            reply += text + "\n";
        }
        this.recipeGraph.addUserVertex(state);
        return reply;
    }

    private String handleIngredientsMessage(UserState state, String message) throws Exception {
        // we want to get a list of recipes based on the ingredients (message)
        // first we see if we already have the ingredients in our graph
        String ingredientsStr = message;
        JSONArray matchingRecipes = null;
        Vertex ingredientVertex = this.recipeGraph.findIngredientsVertex(ingredientsStr);
        if (ingredientVertex != null) {
            logger.debug(String.format("Ingredients vertex exists for %s. Returning recipes from vertex.", ingredientsStr));
            matchingRecipes = new JSONArray(ingredientVertex.getPropertyValue("detail").toString());
        }
        else {
            // we don't have the ingredients in our graph yet, so get list of recipes from Spoonacular
            logger.debug(String.format("Ingredients vertex does not exist for %s. Querying Spoonacular for recipes.", ingredientsStr));
            matchingRecipes = this.recipeClient.findByIngredients(ingredientsStr);
            // add vertex for the ingredients to our graph
            ingredientVertex = this.recipeGraph.addIngredientsVertex(state, ingredientsStr, matchingRecipes);
        }
        // update state
        state.getConversationContext().put("recipes", matchingRecipes);
        state.setLastGraphVertex(ingredientVertex);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i = 0; i < matchingRecipes.length(); i++) {
            response += (i + 1) + ". " + matchingRecipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String handleSelectionMessage(UserState state, int selection) throws Exception {
        // we want to get a the recipe based on the selection
        // first we see if we already have the recipe in our graph
        ArrayList recipes = (ArrayList) state.getConversationContext().get("recipes");
        String recipeId = ((Number) ((AbstractMap) recipes.get(selection - 1)).get("id")).intValue() + "";
        String recipeDetail = null;
        Vertex recipeVertex = this.recipeGraph.findRecipeVertex(recipeId);
        if (recipeVertex != null) {
            logger.debug(String.format("Recipe vertex exists for %s. Returning recipe steps from vertex.", recipeId));
            recipeDetail = recipeVertex.getPropertyValue("detail").toString();
        }
        else {
            logger.debug(String.format("Recipe vertex does not exist for %s. Querying Spoonacular for details.", recipeId));
            JSONObject recipeInfo = this.recipeClient.getInfoById(recipeId);
            JSONArray recipeSteps = this.recipeClient.getStepsById(recipeId);
            recipeDetail = this.makeFormattedSteps(recipeInfo, recipeSteps);
            // add vertex for recipe
            this.recipeGraph.addRecipeVertex(state, recipeId, recipeInfo.getString("title"), recipeDetail);
        }
        // clear out state
        state.setLastGraphVertex(null);
        state.setConversationContext(null);
        // return response
        return recipeDetail;
    }

    private String handleCuisineMessage(UserState state, String message) throws Exception {
        // we want to get a list of recipes based on the cuisine
        // first we see if we already have the cuisine in our graph
        String cuisine = message;
        JSONArray matchingRecipes = null;
        Vertex cuisineVertex = this.recipeGraph.findCuisineVertex(cuisine);
        if (cuisineVertex != null) {
            logger.debug(String.format("Cuisine vertex exists for %s. Returning recipes from vertex.", cuisine));
            matchingRecipes = new JSONArray(cuisineVertex.getPropertyValue("detail").toString());
        }
        else {
            // we don't have the cuisine in our graph yet, so get list of recipes from Spoonacular
            logger.debug(String.format("Cuisine vertex does not exist for %s. Querying Spoonacular for recipes.", cuisine));
            matchingRecipes = this.recipeClient.findByCuisine(cuisine);
            // add vertex for the cuisine to our graph
            cuisineVertex = this.recipeGraph.addCuisineVertex(state, cuisine, matchingRecipes);
        }
        // update state
        state.getConversationContext().put("recipes", matchingRecipes);
        state.setLastGraphVertex(cuisineVertex);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i = 0; i < matchingRecipes.length(); i++) {
            response += (i + 1) + ". " + matchingRecipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String makeFormattedSteps(JSONObject recipeInfo, JSONArray recipeSteps) throws Exception {
        String response = "Ok, it takes *";
        response += recipeInfo.get("readyInMinutes").toString() + "* minutes to make *";
        response += recipeInfo.get("servings").toString() + "* servings of *";
        response += recipeInfo.getString("title") + "*. Here are the steps:\n\n";
        if (recipeSteps != null && recipeSteps.length() > 0) {
            // mw:TODO - add steps
        }
        else {
            response += "_No instructions available for this recipe._\n\n";
        }
        return response;
    }
}
