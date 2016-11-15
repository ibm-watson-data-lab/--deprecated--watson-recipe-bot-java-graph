package com.ibm.cdslabs.watson.recipe.bot;

import com.ibm.graph.client.Edge;
import com.ibm.graph.client.Element;
import com.ibm.graph.client.IBMGraphClient;
import com.ibm.graph.client.Vertex;
import com.ibm.graph.client.schema.*;
import org.apache.wink.json4j.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Created by markwatson on 11/15/16.
 */
public class RecipeGraph {

    private IBMGraphClient graphClient;

    private static Logger logger = LoggerFactory.getLogger(RecipeGraph.class);

    public RecipeGraph(IBMGraphClient graphClient) {
        this.graphClient = graphClient;
    }

    public void initGraph() throws Exception {
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

    public void addUserVertex(final UserState state) throws Exception {
        Vertex userVertex = new Vertex("person", new HashMap() {{
            put("name", state.getUserId());
        }});
        userVertex = this.addVertexIfNotExists(userVertex, "name");
        state.setLastGraphVertex(userVertex);
    }

    // Ingredients

    public String getUniqueIngredientsName(final String ingredientsStr) {
        String[] ingredients = ingredientsStr.trim().toLowerCase().split(",");
        for (int i = 0; i < ingredients.length; i++) {
            ingredients[i] = ingredients[i].trim();
        }
        Arrays.sort(ingredients);
        return String.join(",", ingredients);
    }

    public Vertex findIngredientsVertex(final String ingredientsStr) throws Exception {
        return findVertex("ingredient", "name", this.getUniqueIngredientsName(ingredientsStr));
    }

    public Vertex addIngredientsVertex(UserState state, final String ingredientsStr, final JSONArray matchingRecipes) throws Exception {
        Vertex ingredientVertex = new Vertex("ingredient", new HashMap() {{
            put("name", getUniqueIngredientsName(ingredientsStr));
            put("detail", matchingRecipes.toString());
        }});
        ingredientVertex = this.addVertexIfNotExists(ingredientVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), ingredientVertex.getId()));
        return ingredientVertex;
    }

    // Cuisine

    public String getUniqueCuisineName(final String cuisine) {
        return cuisine.trim().toLowerCase();
    }

    public Vertex findCuisineVertex(final String cuisine) throws Exception {
        return findVertex("cuisine", "name", this.getUniqueCuisineName(cuisine));
    }

    public Vertex addCuisineVertex(UserState state, final String cuisine, final JSONArray matchingRecipes) throws Exception {
        Vertex cuisineVertex = new Vertex("cuisine", new HashMap() {{
            put("name", getUniqueCuisineName(cuisine));
            put("detail", matchingRecipes.toString());
        }});
        cuisineVertex = this.addVertexIfNotExists(cuisineVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), cuisineVertex.getId()));
        return cuisineVertex;
    }

    // Recipe

    public String getUniqueRecipeName(final String recipeId) {
        return recipeId.trim().toLowerCase();
    }

    public Vertex findRecipeVertex(final String recipeId) throws Exception {
        return findVertex("recipe", "name", getUniqueRecipeName(recipeId));
    }

    public void addRecipeVertex(UserState state, final String recipeId, final String recipeTitle, final String recipeDetail) throws Exception {
        Vertex recipeVertex = new Vertex("recipe", new HashMap() {{
            put("name", getUniqueRecipeName(recipeId));
            put("title", recipeTitle.trim());
            put("detail", recipeDetail);
        }});
        recipeVertex = this.addVertexIfNotExists(recipeVertex, "name");
        this.addEdgeIfNotExists(new Edge("selects", state.getLastGraphVertex().getId(), recipeVertex.getId()));
        state.setLastGraphVertex(recipeVertex);
    }

    // Graph Helper Methods

    public Vertex findVertex(String label, String propertyName, String propertyValue) throws Exception {
        String query = "g.V().hasLabel(\"" + label + "\").has(\"" + propertyName + "\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length > 0) {
            return (Vertex) elements[0];
        }
        else {
            return null;
        }
    }

    public Vertex addVertexIfNotExists(Vertex vertex, String uniquePropertyName) throws Exception {
        String propertyValue = vertex.getProperties().get(uniquePropertyName).toString();
        String query = "g.V().hasLabel(\"" + vertex.getLabel() + "\").has(\"" + uniquePropertyName + "\", \"" + propertyValue + "\")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            logger.debug(String.format("Adding %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            return this.graphClient.addVertex(vertex);
        }
        else {
            logger.debug(String.format("Return %s vertex where %s=%s", vertex.getLabel(), uniquePropertyName, propertyValue));
            return (Vertex) elements[0];
        }
    }

    public void addEdgeIfNotExists(Edge edge) throws Exception {
        String query = "g.V(" + edge.getOutV() + ").outE().inV().hasId(" + edge.getInV() + ")";
        Element[] elements = this.graphClient.runGremlinQuery(query);
        if (elements.length == 0 || !(elements[0] instanceof Vertex)) {
            this.graphClient.addEdge(edge);
        }
    }

    // Delete

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

}
