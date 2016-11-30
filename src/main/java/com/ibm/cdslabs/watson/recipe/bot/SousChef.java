package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Path;
import com.ibm.graph.client.Vertex;
import com.ibm.watson.developer_cloud.conversation.v1.ConversationService;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageRequest;
import com.ibm.watson.developer_cloud.conversation.v1.model.MessageResponse;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by markwatson on 11/11/16.
 */
public class SousChef {

    private GraphRecipeStore recipeStore;
    private String slackBotId;
    private String conversationWorkspaceId;
    private SlackSession slackSession;
    private RecipeClient recipeClient;
    private ConversationService conversationService;
    private HashMap<String, UserState> userStateMap = new HashMap<String, UserState>();

    private static Logger logger = LoggerFactory.getLogger(SousChef.class);

    public SousChef(GraphRecipeStore recipeStore, String slackToken, String slackBotId, String recipeClientApiKey, String conversationUsername, String conversationPassword, String conversationWorkspaceId) {
        this.recipeStore = recipeStore;
        this.slackBotId = slackBotId;
        this.slackSession = SlackSessionFactory.createWebSocketSlackSession(slackToken);
        this.recipeClient = new RecipeClient(recipeClientApiKey);
        this.conversationService = new ConversationService(ConversationService.VERSION_DATE_2016_07_11);
        this.conversationService.setUsernameAndPassword(conversationUsername, conversationPassword);
        this.conversationWorkspaceId = conversationWorkspaceId;
    }

    public void run() throws Exception {
        this.recipeStore.init();
        this.slackSession.connect();
        this.slackSession.addMessagePostedListener((event, session) -> {
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
                    // ignore messages from the bot (messages we sent)
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
        if (state.getConversationContext().containsKey("is_favorites") && Boolean.TRUE.equals(state.getConversationContext().get("is_favorites"))) {
            reply = this.handleFavoritesMessage(state);
        }
        else if (state.getConversationContext().containsKey("is_ingredients") && Boolean.TRUE.equals(state.getConversationContext().get("is_ingredients"))) {
            reply = this.handleIngredientsMessage(state, message);
        }
        else if (response.getEntities() != null && response.getEntities().size() > 0 && response.getEntities().get(0).getEntity().equalsIgnoreCase("cuisine")) {
            String cuisine = response.getEntities().get(0).getValue();
            reply = this.handleCuisineMessage(state, cuisine);
        }
        else if (state.getConversationContext().containsKey("is_selection") && Boolean.TRUE.equals(state.getConversationContext().get("is_selection"))) {
            int selection = -1;
            if (state.getConversationContext().containsKey("selection")) {
                try {
                    selection = Integer.parseInt(state.getConversationContext().get("selection").toString());
                }
                catch (Exception ex) {
                }
            }
            reply = this.handleSelectionMessage(state, selection);
        }
        else {
            reply = this.handleStartMessage(state, response);
        }
        this.slackSession.sendMessage(channel, reply);
    }

    // Messages from Bot

    private String handleStartMessage(UserState state, MessageResponse response) throws Exception {
        if (state.getUser() == null) {
            Vertex user = this.recipeStore.addUser(state.getUserId());
            state.setUser(user);
        }
        String reply = "";
        for (String text : ((ArrayList<String>) response.getOutput().get("text"))) {
            reply += text + "\n";
        }
        return reply;
    }

    private String handleFavoritesMessage(UserState state) throws Exception {
        JSONArray recipes = this.recipeStore.findFavoriteRecipesForUser(state.getUserId(), 5);
        // update state
        state.getConversationContext().put("recipes", recipes);
        state.setIngredientCuisine(null);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i = 0; i < recipes.length(); i++) {
            response += (i + 1) + ". " + recipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }


    private String handleIngredientsMessage(UserState state, String message) throws Exception {
        // we want to get a list of recipes based on the ingredients (message)
        // first we see if we already have the ingredients in our datastore
        JSONArray matchingRecipes;
        String ingredientsStr = message;
        Vertex ingredient = this.recipeStore.findIngredient(ingredientsStr);
        if (ingredient != null) {
            logger.debug(String.format("Ingredient exists for %s. Returning recipes from datastore.", ingredientsStr));
            matchingRecipes = new JSONArray(ingredient.getPropertyValue("detail").toString());
            // increment the count on the user-ingredient
            this.recipeStore.incrementIngredientForUser(ingredient, state.getUser());
        }
        else {
            // we don't have the ingredients in our datastore yet, so get list of recipes from Spoonacular
            logger.debug(String.format("Ingredient does not exist for %s. Querying Spoonacular for recipes.", ingredientsStr));
            matchingRecipes = this.recipeClient.findByIngredients(ingredientsStr);
            // add ingredient to datastore
            ingredient = this.recipeStore.addIngredient(ingredientsStr, matchingRecipes, state.getUser());
        }
        // update state
        state.getConversationContext().put("recipes", matchingRecipes);
        state.setIngredientCuisine(ingredient);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i = 0; i < matchingRecipes.length(); i++) {
            response += (i + 1) + ". " + matchingRecipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String handleCuisineMessage(UserState state, String message) throws Exception {
        // we want to get a list of recipes based on the cuisine (message)
        // first we see if we already have the cuisine in our datastore
        JSONArray matchingRecipes = null;
        String cuisineStr = message;
        Vertex cuisine = this.recipeStore.findCuisine(cuisineStr);
        if (cuisine != null) {
            logger.debug(String.format("Cuisine exists for %s. Returning recipes from datastore.", cuisineStr));
            matchingRecipes = new JSONArray(cuisine.getPropertyValue("detail").toString());
            // increment the count on the user-cuisine
            this.recipeStore.incrementCuisineForUser(cuisine, state.getUser());
        }
        else {
            // we don't have the cuisine in our datastore yet, so get list of recipes from Spoonacular
            logger.debug(String.format("Cuisine does not exist for %s. Querying Spoonacular for recipes.", cuisineStr));
            matchingRecipes = this.recipeClient.findByCuisine(cuisineStr);
            // add cuisine to datastore
            cuisine = this.recipeStore.addCuisine(cuisineStr, matchingRecipes, state.getUser());
        }
        // update state
        state.getConversationContext().put("recipes", matchingRecipes);
        state.setIngredientCuisine(cuisine);
        // return the response
        String response = "Let's see here...\nI've found these recipes: \n";
        for (int i = 0; i < matchingRecipes.length(); i++) {
            response += (i + 1) + ". " + matchingRecipes.getJSONObject(i).getString("title") + "\n";
        }
        response += "\nPlease enter the corresponding number of your choice.";
        return response;
    }

    private String handleSelectionMessage(UserState state, int selection) throws Exception {
        if (selection >= 1 && selection <= 5) {
            // we want to get a the recipe based on the selection
            // first we see if we already have the recipe in our datastore
            ArrayList recipes = (ArrayList)state.getConversationContext().get("recipes");
            String recipeId = String.valueOf((int)Double.parseDouble(((AbstractMap)recipes.get(selection-1)).get("id").toString()));
            String recipeDetail;
            Vertex recipe = this.recipeStore.findRecipe(recipeId);
            if (recipe != null) {
                logger.debug(String.format("Recipe exists for %s. Returning recipe steps from datastore.", recipeId));
                recipeDetail = recipe.getPropertyValue("detail").toString();
                // increment the count on the ingredient/cuisine-recipe and the user-recipe
                this.recipeStore.incrementRecipeForUser(recipe, state.getIngredientCuisine(), state.getUser());
            }
            else {
                logger.debug(String.format("Recipe does not exist for %s. Querying Spoonacular for details.", recipeId));
                JSONObject recipeInfo = this.recipeClient.getInfoById(recipeId);
                JSONArray recipeSteps = this.recipeClient.getStepsById(recipeId);
                recipeDetail = this.makeFormattedSteps(recipeInfo, recipeSteps);
                // add recipe to datastore
                this.recipeStore.addRecipe(recipeId, recipeInfo.getString("title"), recipeDetail, state.getIngredientCuisine(), state.getUser());
            }
            // clear out state
            state.setIngredientCuisine(null);
            state.setConversationContext(null);
            // return response
            return recipeDetail;
        }
        else {
            state.getConversationContext().put("selection_valid", Boolean.FALSE);
            return "Invalid selection! Say anything to see your choices again...";
        }
    }

    private String makeFormattedSteps(JSONObject recipeInfo, JSONArray recipeSteps) throws Exception {
        String response = "Ok, it takes *";
        response += recipeInfo.get("readyInMinutes").toString() + "* minutes to make *";
        response += recipeInfo.get("servings").toString() + "* servings of *";
        response += recipeInfo.getString("title") + "*. Here are the steps:\n\n";
        if (recipeSteps != null && recipeSteps.length() > 0) {
            for (int i=0; i<recipeSteps.length(); i++) {
                JSONObject step = recipeSteps.getJSONObject(i);
                String equipStr = "";
                JSONArray equipArray = step.getJSONArray("equipment");
                for (int j=0; j<equipArray.length(); j++) {
                    equipStr += String.format("%s,",equipArray.getJSONObject(j).getString("name"));
                }
                if (equipStr.length() == 0) {
                    equipStr = "None";
                }
                else {
                    equipStr = equipStr.substring(0, equipStr.length() - 1);
                }
                response += String.format("*Step %d*:\n",(i+1));
                response += String.format("_Equipment_: %s\n",equipStr);
                response += String.format("_Action_: %s\n\n",step.getString("step"));
            }
        }
        else {
            response += "_No instructions available for this recipe._\n\n";
        }
        response += "*Say anything to me to start over...*";
        return response;
    }
}
