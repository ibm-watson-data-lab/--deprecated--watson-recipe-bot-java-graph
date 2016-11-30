package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.*;
import com.ibm.graph.client.schema.*;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Created by markwatson on 11/15/16.
 */
public class GraphRecipeStore {

    private IBMGraphClient graphClient;

    private static Logger logger = LoggerFactory.getLogger(GraphRecipeStore.class);

    public GraphRecipeStore(IBMGraphClient graphClient) {
        this.graphClient = graphClient;
    }

    public void init() throws Exception {
        logger.debug("Getting Graph Schema...");
        Schema schema = this.graphClient.getSchema();
        boolean schemaExists = (schema != null && schema.getPropertyKeys() != null && schema.getPropertyKeys().length > 0);
        if (!schemaExists) {
            schema = new Schema(
                    new PropertyKey[]{
                            new PropertyKey("name", "String", "SINGLE"),
                            new PropertyKey("title", "String", "SINGLE"),
                            new PropertyKey("detail", "String", "SINGLE")
                    },
                    new VertexLabel[]{
                            new VertexLabel("person"),
                            new VertexLabel("ingredient"),
                            new VertexLabel("cuisine"),
                            new VertexLabel("recipe")
                    },
                    new EdgeLabel[]{
                            new EdgeLabel("selects")
                    },
                    new VertexIndex[]{
                            new VertexIndex("vertexByName", new String[]{"name"}, true, true)
                    },
                    new EdgeIndex[]{}
            );
            logger.debug("Creating Graph Schema...");
            this.graphClient.saveSchema(schema);
            logger.debug("Graph Schema created.");
        }
    }

    // User

    public Vertex addUser(final String userId) throws Exception {
        Vertex userVertex = new Vertex("person", new HashMap() {{
            put("name", userId);
        }});
        return this.addVertexIfNotExists(userVertex, "name");
    }

    public void deleteUsers(String[] userIds) throws Exception {
        for (String userId : userIds) {
            Element[] elements = this.graphClient.runGremlinQuery("g.V().hasLabel(\"person\").has(\"name\", \"" + userId + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = this.graphClient.deleteVertex(((Vertex) element).getId());
                    logger.debug(String.format("Deleted user %s = %s", userId, success));
                }
            }
        }
    }

    // Ingredients

    private String getUniqueIngredientsName(final String ingredientsStr) {
        String[] ingredients = ingredientsStr.trim().toLowerCase().split(",");
        for (int i = 0; i < ingredients.length; i++) {
            ingredients[i] = ingredients[i].trim();
        }
        Arrays.sort(ingredients);
        return String.join(",", ingredients);
    }

    public Vertex findIngredient(final String ingredientsStr) throws Exception {
        return findVertex("ingredient", "name", this.getUniqueIngredientsName(ingredientsStr));
    }

    public Vertex addIngredient(final String ingredientsStr, final JSONArray matchingRecipes, Vertex userVertex) throws Exception {
        Vertex ingredientVertex = new Vertex("ingredient", new HashMap() {{
            put("name", getUniqueIngredientsName(ingredientsStr));
            put("detail", matchingRecipes.toString());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.incrementIngredientForUser(ingredientVertex, userVertex);
        return ingredientVertex;
    }

    public void incrementIngredientForUser(Vertex ingredientVertex, Vertex userVertex) throws Exception {
        Edge ingredientEdge = new Edge("selects", userVertex.getId(), ingredientVertex.getId(), new HashMap() {{
            put("count", new Integer(1));
        }});
        this.addUpdateEdge(ingredientEdge);
    }

    public void deleteIngredients(String[] ingredients) throws Exception {
        for (String ingredient : ingredients) {
            Element[] elements = this.graphClient.runGremlinQuery("g.V().hasLabel(\"ingredient\").has(\"name\", \"" + ingredient + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = this.graphClient.deleteVertex(((Vertex) element).getId());
                    logger.debug(String.format("Deleted ingredient %s = %s", ingredient, success));
                }
            }
        }
    }

    // Cuisine

    private String getUniqueCuisineName(final String cuisine) {
        return cuisine.trim().toLowerCase();
    }

    public Vertex findCuisine(final String cuisine) throws Exception {
        return findVertex("cuisine", "name", this.getUniqueCuisineName(cuisine));
    }

    public Vertex addCuisine(final String cuisine, final JSONArray matchingRecipes, Vertex userVertex) throws Exception {
        Vertex cuisineVertex = new Vertex("cuisine", new HashMap() {{
            put("name", getUniqueCuisineName(cuisine));
            put("detail", matchingRecipes.toString());
        }});
        cuisineVertex = this.addVertexIfNotExists(cuisineVertex, "name");
        this.incrementCuisineForUser(cuisineVertex, userVertex);
        return cuisineVertex;
    }

    public void deleteCuisines(String[] cuisines) throws Exception {
        for (String cuisine : cuisines) {
            Element[] elements = this.graphClient.runGremlinQuery("g.V().hasLabel(\"cuisine\").has(\"name\", \"" + cuisine + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = this.graphClient.deleteVertex(((Vertex) element).getId());
                    logger.debug(String.format("Deleted cuisine %s = %s", cuisine, success));
                }
            }
        }
    }

    public void incrementCuisineForUser(Vertex cuisineVertex, Vertex userVertex) throws Exception {
        Edge cuisineEdge = new Edge("selects", userVertex.getId(), cuisineVertex.getId(), new HashMap() {{
            put("count", new Integer(1));
        }});
        this.addUpdateEdge(cuisineEdge);
    }

    // Recipe

    private String getUniqueRecipeName(final String recipeId) {
        return recipeId.trim().toLowerCase();
    }

    public Vertex findRecipe(final String recipeId) throws Exception {
        return findVertex("recipe", "name", getUniqueRecipeName(recipeId));
    }

    public JSONArray findFavoriteRecipesForUser(Vertex userVertex, int count) throws Exception {
        List<Path> pathList = new ArrayList<Path>();
        String query = "g.V().hasLabel(\"person\").has(\"name\", \"" + userVertex.getPropertyValue("name") + "\").outE().inV().hasLabel(\"recipe\").path()";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length > 0) {
            for (Element element : elements) {
                pathList.add((Path)element);
            }
        }
        JSONArray recipes = new JSONArray();
        Path[] paths = pathList.toArray(new Path[0]);
        if (paths.length > 0) {
            Arrays.sort(paths, (path1, path2) -> {
                int count1 = 1;
                int count2 = 1;
                try {
                    count1 = (Integer)path1.getObjects()[1].getPropertyValue("count");
                }
                catch(Exception ex) {}
                try {
                    count2 = (Integer)path2.getObjects()[1].getPropertyValue("count");
                }
                catch(Exception ex) {}
                return Integer.compare(count2, count1); // reverse sort
            });
            int i = -1;
            for (Path path : paths) {
                ++i;
                if (i >= count) {
                    break;
                }
                JSONObject recipe = new JSONObject();
                recipe.put("id", path.getObjects()[2].getPropertyValue("name"));
                recipe.put("title", path.getObjects()[2].getPropertyValue("title"));
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    public void addRecipe(final String recipeId, final String recipeTitle, final String recipeDetail, Vertex ingredientCuisineVertex, Vertex userVertex) throws Exception {
        Vertex recipeVertex = new Vertex("recipe", new HashMap() {{
            put("name", getUniqueRecipeName(recipeId));
            put("title", recipeTitle.trim());
            put("detail", recipeDetail);
        }});
        recipeVertex = this.addVertexIfNotExists(recipeVertex, "name");
        this.incrementRecipeForUser(recipeVertex, ingredientCuisineVertex, userVertex);
    }

    public void incrementRecipeForUser(Vertex recipeVertex, Vertex ingredientCuisineVertex, Vertex userVertex) throws Exception {
        // add one edge from the user to the recipe (this will let us find a user's favorite recipes, etc)
        Edge userRecipeEdge = new Edge("selects", userVertex.getId(), recipeVertex.getId(), new HashMap() {{
            put("count", new Integer(1));
        }});
        this.addUpdateEdge(userRecipeEdge);
        // add one edge from the ingredient/cuisine to the recipe
        if (ingredientCuisineVertex != null) {
            Edge ingredientCusisineRecipeEdge = new Edge("selects", ingredientCuisineVertex.getId(), recipeVertex.getId(), new HashMap() {{
                put("count", new Integer(1));
            }});
            this.addUpdateEdge(ingredientCusisineRecipeEdge);
        }
    }

    public void deleteRecipes(String[] recipes) throws Exception {
        for (String recipe : recipes) {
            Element[] elements = this.graphClient.runGremlinQuery("g.V().hasLabel(\"recipe\").has(\"name\", \"" + recipe + "\")");
            if (elements.length > 0) {
                for (Element element : elements) {
                    boolean success = this.graphClient.deleteVertex(((Vertex) element).getId());
                    logger.debug(String.format("Deleted recipe %s = %s", recipe, success));
                }
            }
        }
    }

    // Graph Helper Methods

    private Vertex findVertex(String label, String propertyName, String propertyValue) throws Exception {
        String query = "g.V().hasLabel(\"" + label + "\").has(\"" + propertyName + "\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length > 0) {
            return (Vertex) elements[0];
        }
        else {
            return null;
        }
    }

    private Vertex addVertexIfNotExists(Vertex vertex, String uniquePropertyName) throws Exception {
        String propertyValue = vertex.getProperties().get(uniquePropertyName).toString();
        String query = "g.V().hasLabel(\"" + vertex.getLabel() + "\").has(\"" + uniquePropertyName + "\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            logger.debug(String.format("Adding %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            return this.graphClient.addVertex(vertex);
        }
        else {
            logger.debug(String.format("Returning %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            return (Vertex) elements[0];
        }
    }

    private Edge addUpdateEdge(Edge edge) throws Exception {
        String query = "g.V(" + edge.getOutV() + ").outE().inV().hasId(" + edge.getInV() + ").path()";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Path)) {
            return this.graphClient.addEdge(edge);
        }
        else {
            edge = (Edge)((Path)elements[0]).getObjects()[1];
            int count = 0;
            try {
                count = (Integer)edge.getPropertyValue("count");
            }
            catch(Exception ex) {}
            edge.setPropertyValue("count", count+1);
            return this.graphClient.updateEdge(edge);
        }
    }
}
