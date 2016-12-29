package com.ibm.cdslabs.watson.recipe.bot.graph;

import com.ibm.graph.client.*;
import com.ibm.graph.client.response.ResultSet;
import com.ibm.graph.client.schema.*;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the storage and retrieval of Graph entities for the application, including
 * User, Ingredient, Cuisine, and Recipe vertices.
 */
public class GraphRecipeStore {

    private IBMGraphClient graphClient;

    private static Logger logger = LoggerFactory.getLogger(GraphRecipeStore.class);

    /**
     * Creates a new instance of GraphRecipeStore.
     * @param graphClient - The instance of the IBM Graph Client to use
     */
    public GraphRecipeStore(IBMGraphClient graphClient) {
        this.graphClient = graphClient;
    }

    /**
     * Creates and initializes the Graph schema.
     * @throws Exception
     */
    public void init() throws Exception {
        logger.debug("Getting Graph Schema...");
        Schema schema = this.graphClient.getSchema();
        boolean schemaExists = (schema != null && schema.getPropertyKeys() != null && schema.getPropertyKeys().length > 0);
        if (!schemaExists) {
            schema = new Schema(
                    new PropertyKey[]{
                            new PropertyKey("name", PropertyKey.PropertyKeyDataType.String, PropertyKey.PropertyKeyCardinality.SINGLE),
                            new PropertyKey("title", PropertyKey.PropertyKeyDataType.String, PropertyKey.PropertyKeyCardinality.SINGLE),
                            new PropertyKey("detail", PropertyKey.PropertyKeyDataType.String, PropertyKey.PropertyKeyCardinality.SINGLE)
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

    /**
     * Adds a new user to Graph if a user with the specified ID does not already exist.
     * @param userId - The ID of the user (typically the ID returned from Slack)
     * @return The user vertex that was created or returned from Graph
     * @throws Exception
     */
    public Vertex addUser(final String userId) throws Exception {
        Vertex userVertex = new Vertex("person", new HashMap() {{
            put("name", userId);
        }});
        return this.addVertexIfNotExists(userVertex, "name");
    }

    /**
     * Delete the user vertices with the specified IDs from Graph.
     * @param userIds - The IDs of the user vertices to delete
     * @throws Exception
     */
    public void deleteUsers(String[] userIds) throws Exception {
        for (String userId : userIds) {
            ResultSet resultSet = this.graphClient.executeGremlin("g.V().hasLabel(\"person\").has(\"name\", \"" + userId + "\")");
            Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
            while(iterator.hasNext()) {
                Vertex vertex = iterator.next();
                boolean success = this.graphClient.deleteVertex(vertex.getId());
                logger.debug(String.format("Deleted user %s = %s", userId, success));
            }
        }
    }

    // Ingredients

    /**
     * Gets the unique name for the ingredient to be stored in Graph.
     * @param ingredientsStr - The ingredient or comma-separated list of ingredients specified by the user
     * @return - the unique name based on ingredientsStr
     */
    private String getUniqueIngredientsName(final String ingredientsStr) {
        String[] ingredients = ingredientsStr.trim().toLowerCase().split(",");
        for (int i = 0; i < ingredients.length; i++) {
            ingredients[i] = ingredients[i].trim();
        }
        Arrays.sort(ingredients);
        return String.join(",", ingredients);
    }

    /**
     * Finds the ingredient based on the specified ingredientsStr in Graph.
     * @param ingredientsStr - The ingredient or comma-separated list of ingredients specified by the user
     * @return - The ingredient vertex
     * @throws Exception
     */
    public Vertex findIngredient(final String ingredientsStr) throws Exception {
        return findVertex("ingredient", "name", this.getUniqueIngredientsName(ingredientsStr));
    }

    /**
     * Adds a new ingredient to Graph if an ingredient based on the specified ingredientsStr does not already exist.
     * @param ingredientsStr - The ingredient or comma-separated list of ingredients specified by the user
     * @param matchingRecipes - The recipes that match the specified ingredientsStr
     * @param userVertex - The existing Graph vertex for the user
     * @return - The ingredient vertex
     * @throws Exception
     */
    public Vertex addIngredient(final String ingredientsStr, final JSONArray matchingRecipes, Vertex userVertex) throws Exception {
        Vertex ingredientVertex = new Vertex("ingredient", new HashMap() {{
            put("name", getUniqueIngredientsName(ingredientsStr));
            put("detail", matchingRecipes.toString());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.recordIngredientRequestForUser(ingredientVertex, userVertex);
        return ingredientVertex;
    }

    /**
     * Creates or updates an edge between the specified user and ingredient.
     * Stores the number of times the ingredient has been accessed by the user in the edge.
     * @param ingredientVertex - The existing Graph vertex for the ingredient
     * @param userVertex - The existing Graph vertex for the user
     * @throws Exception
     */
    public void recordIngredientRequestForUser(Vertex ingredientVertex, Vertex userVertex) throws Exception {
        Edge ingredientEdge = new Edge("selects", userVertex.getId(), ingredientVertex.getId(), new HashMap() {{
            put("count", new Integer(1));
        }});
        this.addUpdateEdge(ingredientEdge);
    }

    /**
     * Delete the ingredient vertices with the specified names from Graph.
     * @param ingredients - The unique names of the ingredient vertices to delete
     * @throws Exception
     */
    public void deleteIngredients(String[] ingredients) throws Exception {
        for (String ingredient : ingredients) {
            ResultSet resultSet = this.graphClient.executeGremlin("g.V().hasLabel(\"ingredient\").has(\"name\", \"" + ingredient + "\")");
            Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
            while(iterator.hasNext()) {
                Vertex vertex = iterator.next();
                boolean success = this.graphClient.deleteVertex(vertex.getId());
                logger.debug(String.format("Deleted ingredient %s = %s", ingredient, success));
            }
        }
    }

    // Cuisine

    /**
     * Gets the unique name for the cuisine to be stored in Graph.
     * @param cuisine - The cuisine specified by the user
     * @return - The unique cuisine name
     */
    private String getUniqueCuisineName(final String cuisine) {
        return cuisine.trim().toLowerCase();
    }

    /**
     * Finds the cuisine with the specified name in Graph.
     * @param cuisine - The cuisine specified by the user
     * @return - The cuisine vertex
     * @throws Exception
     */
    public Vertex findCuisine(final String cuisine) throws Exception {
        return findVertex("cuisine", "name", this.getUniqueCuisineName(cuisine));
    }

    /**
     * Adds a new cuisine to Graph if a cuisine with the specified name does not already exist.
     * @param cuisine - The cuisine specified by the user
     * @param matchingRecipes - The recipes that match the specified cuisine
     * @param userVertex - The existing Graph vertex for the user
     * @return - The cuisine vertex
     * @throws Exception
     */
    public Vertex addCuisine(final String cuisine, final JSONArray matchingRecipes, Vertex userVertex) throws Exception {
        Vertex cuisineVertex = new Vertex("cuisine", new HashMap() {{
            put("name", getUniqueCuisineName(cuisine));
            put("detail", matchingRecipes.toString());
        }});
        cuisineVertex = this.addVertexIfNotExists(cuisineVertex, "name");
        this.recordCuisineRequestForUser(cuisineVertex, userVertex);
        return cuisineVertex;
    }

    /**
     * Delete the cuisine vertices with the specified names from Graph.
     * @param cuisines - The unique names of the cuisine vertices to delete
     * @throws Exception
     */
    public void deleteCuisines(String[] cuisines) throws Exception {
        for (String cuisine : cuisines) {
            ResultSet resultSet = this.graphClient.executeGremlin("g.V().hasLabel(\"cuisine\").has(\"name\", \"" + cuisine + "\")");
            Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
            while(iterator.hasNext()) {
                Vertex vertex = iterator.next();
                boolean success = this.graphClient.deleteVertex(vertex.getId());
                logger.debug(String.format("Deleted cuisine %s = %s", cuisine, success));
            }
        }
    }

    /**
     * Creates or updates an edge between the specified user and cuisine.
     * Stores the number of times the cuisine has been accessed by the user in the edge.
     * @param cuisineVertex - The existing Graph vertex for the cuisine
     * @param userVertex - The existing Graph vertex for the user
     * @throws Exception
     */
    public void recordCuisineRequestForUser(Vertex cuisineVertex, Vertex userVertex) throws Exception {
        Edge cuisineEdge = new Edge("selects", userVertex.getId(), cuisineVertex.getId(), new HashMap() {{
            put("count", new Integer(1));
        }});
        this.addUpdateEdge(cuisineEdge);
    }

    // Recipe

    /**
     * Gets the unique name for the recipe to be stored in Graph.
     * @param recipeId - The ID of the recipe (typically the ID of the recipe returned from Spoonacular)
     * @return - The unique recipe name
     */
    private String getUniqueRecipeName(final String recipeId) {
        return recipeId.trim().toLowerCase();
    }

    /**
     * Finds the recipe with the specified ID in Graph.
     * @param recipeId - The ID of the recipe (typically the ID of the recipe returned from Spoonacular)
     * @return - The recipe vertex
     * @throws Exception
     */
    public Vertex findRecipe(final String recipeId) throws Exception {
        return findVertex("recipe", "name", getUniqueRecipeName(recipeId));
    }

    /**
     * Finds the user's favorite recipes in Graph.
     * @param userVertex - The existing Graph vertex for the user
     * @param count - The max number of recipes to return
     * @return - A JSONArray of recipes
     * @throws Exception
     */
    public JSONArray findFavoriteRecipesForUser(Vertex userVertex, int count) throws Exception {
        List<Path> pathList = new ArrayList<Path>();
        String query = String.format("g.V().hasLabel(\"person\").has(\"name\", \"%s\").outE().order().by(\"count\", decr).inV().hasLabel(\"recipe\").limit(%d).path()", userVertex.getPropertyValue("name"), count);
        ResultSet resultSet = this.graphClient.executeGremlin(query);
        Iterator<JSONObject> iterator = resultSet.getJSONObjectResultIterator();
        while(iterator.hasNext()) {
            Path path = Path.fromJSONObject(iterator.next());
            pathList.add(path);
        }
        JSONArray recipes = new JSONArray();
        Path[] paths = pathList.toArray(new Path[0]);
        if (paths.length > 0) {
            for (Path path : paths) {
                JSONObject recipe = new JSONObject();
                recipe.put("id", path.getObjects()[2].getPropertyValue("name"));
                recipe.put("title", path.getObjects()[2].getPropertyValue("title"));
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    /**
     * Adds a new recipe to Graph if a recipe with the specified name does not already exist.
     * @param recipeId - The ID of the recipe (typically the ID of the recipe returned from Spoonacular)
     * @param recipeTitle - The title of the recipe
     * @param recipeDetail - The detailed instructions for making the recipe
     * @param ingredientCuisineVertex - The existing Graph vertex for either the ingredient or cuisine selected before the recipe
     * @param userVertex - The existing Graph vertex for the user
     * @throws Exception
     */
    public void addRecipe(final String recipeId, final String recipeTitle, final String recipeDetail, Vertex ingredientCuisineVertex, Vertex userVertex) throws Exception {
        Vertex recipeVertex = new Vertex("recipe", new HashMap() {{
            put("name", getUniqueRecipeName(recipeId));
            put("title", recipeTitle.trim());
            put("detail", recipeDetail);
        }});
        recipeVertex = this.addVertexIfNotExists(recipeVertex, "name");
        this.recordRecipeRequestForUser(recipeVertex, ingredientCuisineVertex, userVertex);
    }

    /**
     * Creates or updates an edge between the specified user and recipe.
     * Stores the number of times the recipe has been accessed by the user in the edge.
     * Creates or updates an edge between the specified ingredient/cuisine (if not None) and recipe.
     * Stores the number of times the recipe has been accessed by the ingredient/cuisine in the edge.
     * @param recipeVertex - The existing Graph vertex for the recipe
     * @param ingredientCuisineVertex - The existing Graph vertex for either the ingredient or cuisine selected before the recipe
     * @param userVertex - The existing Graph vertex for the user
     * @throws Exception
     */
    public void recordRecipeRequestForUser(Vertex recipeVertex, Vertex ingredientCuisineVertex, Vertex userVertex) throws Exception {
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

    /**
     * Delete the recipe vertices with the specified names from Graph.
     * @param recipes - The unique IDs of the recipe vertices to delete
     * @throws Exception
     */
    public void deleteRecipes(String[] recipes) throws Exception {
        for (String recipe : recipes) {
            ResultSet resultSet = this.graphClient.executeGremlin("g.V().hasLabel(\"recipe\").has(\"name\", \"" + recipe + "\")");
            Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
            while(iterator.hasNext()) {
                Vertex vertex = iterator.next();
                boolean success = this.graphClient.deleteVertex(vertex.getId());
                logger.debug(String.format("Deleted recipe %s = %s", recipe, success));
            }
        }
    }

    // Graph Helper Methods

    /**
     * Finds a vertex based on the specified label, propertyName, and propertyValue.
     * @param label - The label value of the vertex stored in Graph
     * @param propertyName - The property name to search for
     * @param propertyValue - The value that should match for the specified property name
     * @return - The first matching vertex, if found
     * @throws Exception
     */
    private Vertex findVertex(String label, String propertyName, String propertyValue) throws Exception {
        String query = "g.V().hasLabel(\"" + label + "\").has(\"" + propertyName + "\", \"" + propertyValue + "\")";
        ResultSet resultSet = this.graphClient.executeGremlin(query);
        Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        else {
            return null;
        }
    }

    /**
     * Adds a new vertex to Graph if a vertex with the same value for uniquePropertyName does not exist.
     * @param vertex - The vertex to add
     * @param uniquePropertyName - The name of the property used to search for an existing vertex (the value will be extracted from the vertex provided)
     * @return - The vertex that was added or returned from Graph
     * @throws Exception
     */
    private Vertex addVertexIfNotExists(Vertex vertex, String uniquePropertyName) throws Exception {
        String propertyValue = vertex.getProperties().get(uniquePropertyName).toString();
        String query = "g.V().hasLabel(\"" + vertex.getLabel() + "\").has(\"" + uniquePropertyName + "\", \"" + propertyValue + "\")";
        ResultSet resultSet = this.graphClient.executeGremlin(query);
        Iterator<Vertex> iterator = resultSet.getVertexResultIterator();
        if (! iterator.hasNext()) {
            logger.debug(String.format("Adding %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            return this.graphClient.addVertex(vertex);
        }
        else {
            logger.debug(String.format("Returning %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            vertex = iterator.next();
            return vertex;
        }
    }

    /**
     * Adds a new edge to Graph if an edge with the same out_v and in_v does not exist.
     * Increments the count property on the edge.
     * @param edge - The edge to add
     * @return - The edge that was added or updated
     * @throws Exception
     */
    private Edge addUpdateEdge(Edge edge) throws Exception {
        String query = "g.V(" + edge.getOutV() + ").outE().inV().hasId(" + edge.getInV() + ").path()";
        ResultSet resultSet = this.graphClient.executeGremlin(query);
        Iterator<JSONObject> iterator = resultSet.getJSONObjectResultIterator();
        if (! iterator.hasNext()) {
            return this.graphClient.addEdge(edge);
        }
        else {
            Path path = Path.fromJSONObject(iterator.next());
            edge = (Edge)path.getObjects()[1];
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
