package com.ibm.cdslabs.watson.recipe.bot.util;

import com.ibm.cdslabs.watson.recipe.bot.GraphRecipeStore;
import com.ibm.graph.client.IBMGraphClient;

public class PurgeRecipeGraph {

    public static void main( String[] args ) throws Exception {
        GraphRecipeStore graphRecipeStore = new GraphRecipeStore(new IBMGraphClient());
        graphRecipeStore.deleteUsers(new String[]{"U2JBLUPL2"});
        graphRecipeStore.deleteIngredients(new String[]{"beef"});
        graphRecipeStore.deleteRecipes(new String[]{"163864"});
    }
}
