package com.ibm.cdslabs.watson.recipe.bot.util;

import com.ibm.cdslabs.watson.recipe.bot.RecipeGraph;
import com.ibm.graph.client.IBMGraphClient;

public class PurgeRecipeGraph {

    public static void main( String[] args ) throws Exception {
        RecipeGraph recipeGraph = new RecipeGraph(new IBMGraphClient());
        recipeGraph.deleteUsers(new String[]{"U2JBLUPL2"});
        recipeGraph.deleteIngredients(new String[]{"beef"});
        recipeGraph.deleteRecipes(new String[]{"163864"});
    }

}
