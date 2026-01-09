package com.openfga.client;

import com.openfga.client.model.StoreInfo;
import com.openfga.client.service.DslTransformService;
import com.openfga.client.service.OpenFGAService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App extends Application {

    private TextField apiUrlField;
    private TextField bearerTokenField;
    private ComboBox<StoreInfo> storeComboBox;
    private ObservableList<StoreInfo> storeList = FXCollections.observableArrayList();

    private TextArea dslTextArea;
    private TextArea lineNumberArea;
    private TextArea jsonPreviewArea;
    private Label modelIdLabel;

    // Tuple - Text format (default)
    private TextArea tupleTextArea;
    // Tuple - Fields format (collapsible)
    private TextField tupleUserField;
    private TextField tupleRelationField;
    private TextField tupleObjectField;
    private TextField tupleConditionNameField;
    private TextArea tupleConditionContextArea;
    private TitledPane tupleFieldsPane;

    // Check - Text format (default)
    private TextArea checkTextArea;
    // Check - Fields format (collapsible)
    private TextField checkUserField;
    private TextField checkRelationField;
    private TextField checkObjectField;
    private TextArea checkContextArea;
    private TitledPane checkFieldsPane;
    private Label checkResultLabel;

    private TextArea outputArea;

    // Visualization
    private Canvas graphCanvas;
    private ScrollPane graphScrollPane;
    private double nodeScale = 1.0;
    private int nodeWidth = 160;
    private List<TypeNode> currentTypes = new ArrayList<>();
    private List<ConditionNode> currentConditions = new ArrayList<>();
    private TypeNode draggedType = null;
    private ConditionNode draggedCondition = null;
    private double dragOffsetX, dragOffsetY;

    private OpenFGAService fgaService;
    private DslTransformService dslService;
    private ObjectMapper jsonMapper;
    private String currentAuthModelId;

    private static final String DEFAULT_DSL = """
model
  schema 1.1

type user

type organization
  relations
    define owner: [user]
    define admin: [user] or owner
    define member: [user] or admin

type folder
  relations
    define org: [organization]
    define owner: [user]
    define editor: [user, organization#member]
    define viewer: [user, organization#member] or editor or owner

type document
  relations
    define org: [organization]
    define parent: [folder]
    define owner: [user]
    define editor: [user, user with time_valid] or owner
    define viewer: [user, organization#member] or editor
    define commenter: [user] or viewer
    define parent_viewer: viewer from parent

condition time_valid(current_time: timestamp, expiry_time: timestamp) {
  current_time < expiry_time
}
""";

    private static final String DEFAULT_TUPLE_TEXT = """
user: user:alice
relation: editor
object: document:readme
---
user: user:bob
relation: viewer
object: document:readme
""";

    private static final String DEFAULT_CHECK_TEXT = """
user: user:alice
relation: viewer
object: document:readme
""";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        fgaService = new OpenFGAService();
        dslService = new DslTransformService();
        jsonMapper = new ObjectMapper();
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Top: Configuration Panel
        root.setTop(createConfigPanel());

        // Center: Tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(
                createStoresTab(),
                createModelTab(),
                createVisualizeTab(),
                createTuplesTab(),
                createCheckTab(),
                createQueryTab()
        );

        // Bottom: Output Area in adjustable SplitPane
        VBox outputPanel = createOutputPanel();

        SplitPane mainSplitPane = new SplitPane();
        mainSplitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        mainSplitPane.getItems().addAll(tabPane, outputPanel);
        mainSplitPane.setDividerPositions(0.85); // 85% tabs, 15% logs

        root.setCenter(mainSplitPane);

        Scene scene = new Scene(root, 1000, 800);
        primaryStage.setTitle("OpenFGA Desktop Client");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Check CLI availability on startup
        checkCliAvailability();
    }

    private VBox createConfigPanel() {
        VBox configBox = new VBox(10);
        configBox.setPadding(new Insets(10));
        configBox.setStyle("-fx-border-color: #ccc; -fx-border-radius: 5; -fx-background-color: #f9f9f9; -fx-background-radius: 5;");

        Label titleLabel = new Label("Configuration");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox urlBox = new HBox(10);
        urlBox.setAlignment(Pos.CENTER_LEFT);
        apiUrlField = new TextField("http://localhost:18080");
        apiUrlField.setPrefWidth(300);
        urlBox.getChildren().addAll(new Label("API URL:"), apiUrlField);

        HBox tokenBox = new HBox(10);
        tokenBox.setAlignment(Pos.CENTER_LEFT);
        bearerTokenField = new TextField();
        bearerTokenField.setPrefWidth(300);
        bearerTokenField.setPromptText("Optional Bearer Token");
        tokenBox.getChildren().addAll(new Label("Token:"), bearerTokenField);

        HBox storeBox = new HBox(10);
        storeBox.setAlignment(Pos.CENTER_LEFT);
        storeComboBox = new ComboBox<>(storeList);
        storeComboBox.setPrefWidth(300);
        storeComboBox.setPromptText("Select a store...");
        Button listStoresBtn = new Button("List Stores");
        listStoresBtn.setOnAction(e -> listStores());
        storeBox.getChildren().addAll(new Label("Store:"), storeComboBox, listStoresBtn);

        configBox.getChildren().addAll(titleLabel, urlBox, tokenBox, storeBox);
        return configBox;
    }

    private Tab createStoresTab() {
        Tab tab = new Tab("Stores");

        VBox content = new VBox(15);
        content.setPadding(new Insets(15));

        // Create Store Section
        TitledPane createPane = new TitledPane();
        createPane.setText("Create Store");
        createPane.setCollapsible(false);

        HBox createBox = new HBox(10);
        createBox.setAlignment(Pos.CENTER_LEFT);
        TextField storeNameField = new TextField();
        storeNameField.setPromptText("Store name");
        storeNameField.setPrefWidth(250);
        Button createBtn = new Button("Create Store");
        createBtn.setOnAction(e -> createStore(storeNameField.getText()));
        createBox.getChildren().addAll(new Label("Name:"), storeNameField, createBtn);
        createPane.setContent(createBox);

        // Delete Store Section
        TitledPane deletePane = new TitledPane();
        deletePane.setText("Delete Store");
        deletePane.setCollapsible(false);

        HBox deleteBox = new HBox(10);
        deleteBox.setAlignment(Pos.CENTER_LEFT);
        Button deleteBtn = new Button("Delete Selected Store");
        deleteBtn.setStyle("-fx-background-color: #ff6b6b; -fx-text-fill: white;");
        deleteBtn.setOnAction(e -> deleteSelectedStore());
        Label warningLabel = new Label("Warning: This permanently deletes the store and all its data!");
        warningLabel.setStyle("-fx-text-fill: #cc0000;");
        deleteBox.getChildren().addAll(deleteBtn, warningLabel);
        deletePane.setContent(deleteBox);

        content.getChildren().addAll(createPane, deletePane);
        tab.setContent(content);
        return tab;
    }

    private Tab createModelTab() {
        Tab tab = new Tab("Model");

        VBox content = new VBox(15);
        content.setPadding(new Insets(15));

        // DSL Input
        Label dslLabel = new Label("Authorization Model (DSL format):");
        dslLabel.setStyle("-fx-font-weight: bold;");

        // Line numbers area
        lineNumberArea = new TextArea("1");
        lineNumberArea.setEditable(false);
        lineNumberArea.setPrefWidth(45);
        lineNumberArea.setStyle("-fx-font-family: monospace; -fx-background-color: #f0f0f0; -fx-text-fill: #888;");

        // DSL text area
        dslTextArea = new TextArea(DEFAULT_DSL);
        dslTextArea.setPrefRowCount(20);
        dslTextArea.setStyle("-fx-font-family: monospace;");
        HBox.setHgrow(dslTextArea, Priority.ALWAYS);

        // Update line numbers when text changes
        dslTextArea.textProperty().addListener((obs, oldVal, newVal) -> updateLineNumbers());
        dslTextArea.scrollTopProperty().addListener((obs, oldVal, newVal) ->
            lineNumberArea.setScrollTop(newVal.doubleValue()));

        // Initialize line numbers
        updateLineNumbers();

        // Editor container with line numbers
        HBox editorBox = new HBox(0);
        editorBox.getChildren().addAll(lineNumberArea, dslTextArea);
        VBox.setVgrow(editorBox, Priority.ALWAYS);

        // Buttons
        HBox buttonBox = new HBox(10);
        Button formatBtn = new Button("Auto Format");
        formatBtn.setOnAction(e -> autoFormatDsl());
        Button validateBtn = new Button("Validate DSL");
        validateBtn.setOnAction(e -> validateDsl());
        Button applyBtn = new Button("Apply Model");
        applyBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        applyBtn.setOnAction(e -> applyModel());
        buttonBox.getChildren().addAll(formatBtn, validateBtn, applyBtn);

        // Model ID display
        HBox modelIdBox = new HBox(10);
        modelIdBox.setAlignment(Pos.CENTER_LEFT);
        modelIdLabel = new Label("No model applied yet");
        modelIdLabel.setStyle("-fx-font-style: italic;");
        modelIdBox.getChildren().addAll(new Label("Current Model ID:"), modelIdLabel);

        // JSON Preview (collapsible)
        TitledPane jsonPane = new TitledPane();
        jsonPane.setText("Generated JSON (Debug)");
        jsonPane.setExpanded(false);
        jsonPreviewArea = new TextArea();
        jsonPreviewArea.setEditable(false);
        jsonPreviewArea.setPrefRowCount(10);
        jsonPreviewArea.setStyle("-fx-font-family: monospace;");
        jsonPane.setContent(jsonPreviewArea);

        content.getChildren().addAll(dslLabel, editorBox, buttonBox, modelIdBox, jsonPane);
        tab.setContent(content);
        return tab;
    }

    private void updateLineNumbers() {
        String text = dslTextArea.getText();
        int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            sb.append(i);
            if (i < lineCount) sb.append("\n");
        }
        lineNumberArea.setText(sb.toString());
    }

    private void autoFormatDsl() {
        String text = dslTextArea.getText();
        String[] lines = text.split("\n", -1);

        // Separate into sections: model/schema, types, conditions
        StringBuilder modelSection = new StringBuilder();
        StringBuilder typeSection = new StringBuilder();
        StringBuilder conditionSection = new StringBuilder();

        StringBuilder currentSection = modelSection;
        boolean inCondition = false;
        boolean inRelations = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                currentSection.append("\n");
                if (!inCondition) inRelations = false;
                continue;
            }

            // Determine which section and indentation
            String indent = "";

            if (trimmed.startsWith("model")) {
                currentSection = modelSection;
                indent = "";
                inRelations = false;
            } else if (trimmed.startsWith("schema ")) {
                indent = "  ";
            } else if (trimmed.startsWith("type ")) {
                currentSection = typeSection;
                indent = "";
                inRelations = false;
            } else if (trimmed.startsWith("condition ")) {
                currentSection = conditionSection;
                indent = "";
                inCondition = true;
                inRelations = false;
            } else if (trimmed.startsWith("relations")) {
                indent = "  ";
                inRelations = true;
            } else if (trimmed.startsWith("define ")) {
                indent = "    ";
            } else if (trimmed.equals("}")) {
                indent = "";
                inCondition = false;
            } else if (inCondition) {
                indent = "  ";
            } else if (inRelations) {
                indent = "    ";
            }

            currentSection.append(indent).append(trimmed).append("\n");
        }

        // Combine: model, types, then conditions (at the end)
        StringBuilder result = new StringBuilder();
        result.append(modelSection.toString().trim());
        if (!typeSection.isEmpty()) {
            result.append("\n\n").append(typeSection.toString().trim());
        }
        if (!conditionSection.isEmpty()) {
            result.append("\n\n").append(conditionSection.toString().trim());
        }
        result.append("\n");

        dslTextArea.setText(result.toString());
        appendOutput("DSL auto-formatted (conditions moved to end)");
    }

    // ==================== Visualization Tab ====================

    private Tab createVisualizeTab() {
        Tab tab = new Tab("Visualize");

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));

        Label titleLabel = new Label("Model Graph Visualization");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox controlBox = new HBox(10);
        controlBox.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("Refresh Graph");
        refreshBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white;");
        refreshBtn.setOnAction(e -> renderGraph());

        // Node width slider
        Label widthLabel = new Label("Width:");
        Slider widthSlider = new Slider(120, 300, 160);
        widthSlider.setPrefWidth(100);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(60);
        widthSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            nodeWidth = newVal.intValue();
            redrawGraph();
        });

        // Node scale slider
        Label sizeLabel = new Label("Scale:");
        Slider sizeSlider = new Slider(0.5, 2.0, 1.0);
        sizeSlider.setPrefWidth(100);
        sizeSlider.setShowTickLabels(true);
        sizeSlider.setMajorTickUnit(0.5);
        sizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            nodeScale = newVal.doubleValue();
            redrawGraph();
        });

        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> {
            widthSlider.setValue(160);
            sizeSlider.setValue(1.0);
            renderGraph();
        });

        Label hint = new Label("(Drag to move)");
        hint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        controlBox.getChildren().addAll(refreshBtn, widthLabel, widthSlider, sizeLabel, sizeSlider, resetBtn, hint);

        // Canvas inside ScrollPane
        graphCanvas = new Canvas(1200, 800);
        graphScrollPane = new ScrollPane(graphCanvas);
        graphScrollPane.setPannable(false); // Disable panning to allow drag
        graphScrollPane.setStyle("-fx-background-color: white;");

        // Mouse events for dragging
        graphCanvas.setOnMousePressed(e -> {
            draggedType = findTypeAt(e.getX(), e.getY());
            if (draggedType != null) {
                dragOffsetX = e.getX() - draggedType.x;
                dragOffsetY = e.getY() - draggedType.y;
            } else {
                draggedCondition = findConditionAt(e.getX(), e.getY());
                if (draggedCondition != null) {
                    dragOffsetX = e.getX() - draggedCondition.x;
                    dragOffsetY = e.getY() - draggedCondition.y;
                }
            }
        });

        graphCanvas.setOnMouseDragged(e -> {
            if (draggedType != null) {
                draggedType.x = Math.max(0, e.getX() - dragOffsetX);
                draggedType.y = Math.max(0, e.getY() - dragOffsetY);
                redrawGraph();
            } else if (draggedCondition != null) {
                draggedCondition.x = Math.max(0, e.getX() - dragOffsetX);
                draggedCondition.y = Math.max(0, e.getY() - dragOffsetY);
                redrawGraph();
            }
        });

        graphCanvas.setOnMouseReleased(e -> {
            draggedType = null;
            draggedCondition = null;
        });

        // Legend
        HBox legend = createLegend();

        VBox.setVgrow(graphScrollPane, Priority.ALWAYS);

        content.getChildren().addAll(titleLabel, controlBox, graphScrollPane, legend);
        tab.setContent(content);
        return tab;
    }

    private TypeNode findTypeAt(double x, double y) {
        int scaledWidth = (int) (nodeWidth * nodeScale);
        int baseHeight = (int) (100 * nodeScale);
        for (TypeNode type : currentTypes) {
            int dynamicHeight = Math.max(baseHeight, (int) ((40 + type.relations.size() * 20) * nodeScale));
            if (x >= type.x && x <= type.x + scaledWidth &&
                y >= type.y && y <= type.y + dynamicHeight) {
                return type;
            }
        }
        return null;
    }

    private ConditionNode findConditionAt(double x, double y) {
        int scaledWidth = (int) (nodeWidth * nodeScale);
        int scaledHeight = (int) (90 * nodeScale);
        for (ConditionNode cond : currentConditions) {
            if (x >= cond.x && x <= cond.x + scaledWidth &&
                y >= cond.y && y <= cond.y + scaledHeight) {
                return cond;
            }
        }
        return null;
    }

    private HBox createLegend() {
        HBox legend = new HBox(20);
        legend.setPadding(new Insets(10, 0, 0, 0));
        legend.setAlignment(Pos.CENTER_LEFT);

        legend.getChildren().addAll(
            createLegendItem("#4285F4", "Type"),
            createLegendItem("#B71C1C", "Condition"),
            createLegendItem("#1B5E20", "Direct Relation"),
            createLegendItem("#E65100", "Computed Relation")
        );
        return legend;
    }

    private HBox createLegendItem(String color, String label) {
        HBox item = new HBox(5);
        item.setAlignment(Pos.CENTER_LEFT);
        Label colorBox = new Label("  ");
        colorBox.setStyle("-fx-background-color: " + color + "; -fx-min-width: 16; -fx-min-height: 16;");
        Label text = new Label(label);
        text.setStyle("-fx-font-size: 11px;");
        item.getChildren().addAll(colorBox, text);
        return item;
    }

    private void renderGraph() {
        String dsl = dslTextArea.getText();

        // Parse DSL and store
        currentTypes = parseDslForVisualization(dsl);
        currentConditions = parseConditionsForVisualization(dsl);

        if (currentTypes.isEmpty()) {
            GraphicsContext gc = graphCanvas.getGraphicsContext2D();
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, graphCanvas.getWidth(), graphCanvas.getHeight());
            gc.setFill(Color.GRAY);
            gc.setFont(Font.font("System", 14));
            gc.fillText("No types found. Enter a DSL model and click 'Refresh Graph'.", 50, 50);
            return;
        }

        // Calculate initial layout
        int totalNodes = currentTypes.size() + currentConditions.size();
        int cols = (int) Math.ceil(Math.sqrt(totalNodes));
        int spacingX = 200;
        int spacingY = 180;
        int startX = 50;
        int startY = 50;

        // Position types
        int index = 0;
        for (TypeNode type : currentTypes) {
            int col = index % cols;
            int row = index / cols;
            type.x = startX + col * spacingX;
            type.y = startY + row * spacingY;
            index++;
        }

        // Position conditions
        for (ConditionNode cond : currentConditions) {
            int col = index % cols;
            int row = index / cols;
            cond.x = startX + col * spacingX;
            cond.y = startY + row * spacingY;
            index++;
        }

        redrawGraph();
        appendOutput("Graph rendered: " + currentTypes.size() + " types, " + currentConditions.size() + " conditions");
    }

    private void redrawGraph() {
        if (currentTypes.isEmpty() && currentConditions.isEmpty()) return;

        GraphicsContext gc = graphCanvas.getGraphicsContext2D();
        int scaledWidth = (int) (nodeWidth * nodeScale);
        int nodeHeight = (int) (100 * nodeScale);

        // Calculate required canvas size
        double maxX = 0, maxY = 0;
        for (TypeNode type : currentTypes) {
            maxX = Math.max(maxX, type.x + scaledWidth + 50);
            maxY = Math.max(maxY, type.y + nodeHeight + 50);
        }
        for (ConditionNode cond : currentConditions) {
            maxX = Math.max(maxX, cond.x + scaledWidth + 50);
            maxY = Math.max(maxY, cond.y + (int)(90 * nodeScale) + 50);
        }

        // Resize canvas if needed
        if (maxX > graphCanvas.getWidth()) graphCanvas.setWidth(maxX);
        if (maxY > graphCanvas.getHeight()) graphCanvas.setHeight(maxY);

        // Clear canvas
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, graphCanvas.getWidth(), graphCanvas.getHeight());

        // Build positions map for edges
        Map<String, double[]> positions = new HashMap<>();
        for (TypeNode type : currentTypes) {
            int dynamicHeight = Math.max(nodeHeight, (int) ((40 + type.relations.size() * 20) * nodeScale));
            positions.put(type.name, new double[]{type.x + scaledWidth / 2.0, type.y + dynamicHeight / 2.0});
        }
        for (ConditionNode cond : currentConditions) {
            positions.put("condition:" + cond.name, new double[]{cond.x + scaledWidth / 2.0, cond.y + 45 * nodeScale});
        }

        // Draw edges first
        gc.setLineWidth(1.5 * nodeScale);
        for (TypeNode type : currentTypes) {
            double[] fromPos = positions.get(type.name);
            for (RelationInfo rel : type.relations) {
                for (String ref : rel.references) {
                    String targetType = extractTypeName(ref);
                    if (positions.containsKey(targetType)) {
                        double[] toPos = positions.get(targetType);
                        drawArrow(gc, fromPos[0], fromPos[1], toPos[0], toPos[1], rel.name, rel.isComputed);
                    }
                }
            }
        }

        // Draw type nodes
        for (TypeNode type : currentTypes) {
            drawTypeNode(gc, type, scaledWidth, nodeHeight);
        }

        // Draw condition nodes
        for (ConditionNode cond : currentConditions) {
            drawConditionNode(gc, cond, scaledWidth);
        }
    }

    private void drawTypeNode(GraphicsContext gc, TypeNode type, int width, int height) {
        // Calculate dynamic height based on relations
        int dynamicHeight = (int) Math.max(height, (40 + type.relations.size() * 20) * nodeScale);
        int headerHeight = (int) (32 * nodeScale);
        int cornerRadius = (int) (12 * nodeScale);

        // Shadow
        gc.setFill(Color.rgb(0, 0, 0, 0.15));
        gc.fillRoundRect(type.x + 4, type.y + 4, width, dynamicHeight, cornerRadius, cornerRadius);

        // Node background - light blue/white
        gc.setFill(Color.rgb(248, 250, 255));
        gc.fillRoundRect(type.x, type.y, width, dynamicHeight, cornerRadius, cornerRadius);

        // Border
        gc.setStroke(Color.rgb(66, 133, 244));
        gc.setLineWidth(2 * nodeScale);
        gc.strokeRoundRect(type.x, type.y, width, dynamicHeight, cornerRadius, cornerRadius);

        // Header background
        gc.setFill(Color.rgb(66, 133, 244));
        gc.fillRoundRect(type.x, type.y, width, headerHeight, cornerRadius, cornerRadius);
        gc.fillRect(type.x, type.y + headerHeight * 0.6, width, headerHeight * 0.4);

        // Type name (white on blue header)
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13 * nodeScale));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(type.name, type.x + width / 2.0, type.y + headerHeight * 0.65);

        // Relations - dark text on light background
        gc.setTextAlign(TextAlignment.LEFT);
        double y = type.y + headerHeight + 18 * nodeScale;
        for (RelationInfo rel : type.relations) {
            // Relation name in bold color
            if (rel.isComputed) {
                gc.setFill(Color.rgb(230, 81, 0)); // Dark orange for computed
            } else {
                gc.setFill(Color.rgb(27, 94, 32)); // Dark green for direct
            }
            gc.setFont(Font.font("Monospace", javafx.scene.text.FontWeight.BOLD, 11 * nodeScale));
            gc.fillText(rel.name, type.x + 10 * nodeScale, y);

            // Definition in gray
            gc.setFill(Color.rgb(80, 80, 80));
            gc.setFont(Font.font("Monospace", 10 * nodeScale));
            int availableChars = (int)((width - 20 * nodeScale - rel.name.length() * 7 * nodeScale) / (6 * nodeScale));
            String shortDef = truncate(rel.shortDef, Math.max(5, availableChars));
            gc.fillText(": " + shortDef, type.x + 10 * nodeScale + rel.name.length() * 7 * nodeScale, y);
            y += 18 * nodeScale;
        }
    }

    private void drawConditionNode(GraphicsContext gc, ConditionNode cond, int width) {
        int height = (int) (90 * nodeScale);
        int headerHeight = (int) (32 * nodeScale);
        int cornerRadius = (int) (12 * nodeScale);

        // Shadow
        gc.setFill(Color.rgb(0, 0, 0, 0.15));
        gc.fillRoundRect(cond.x + 4, cond.y + 4, width, height, cornerRadius, cornerRadius);

        // Node background - light pink/white
        gc.setFill(Color.rgb(255, 250, 250));
        gc.fillRoundRect(cond.x, cond.y, width, height, cornerRadius, cornerRadius);

        // Border
        gc.setStroke(Color.rgb(183, 28, 28));
        gc.setLineWidth(2 * nodeScale);
        gc.strokeRoundRect(cond.x, cond.y, width, height, cornerRadius, cornerRadius);

        // Header background
        gc.setFill(Color.rgb(183, 28, 28));
        gc.fillRoundRect(cond.x, cond.y, width, headerHeight, cornerRadius, cornerRadius);
        gc.fillRect(cond.x, cond.y + headerHeight * 0.6, width, headerHeight * 0.4);

        // Condition label and name (white on red header)
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 12 * nodeScale));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("condition: " + cond.name, cond.x + width / 2.0, cond.y + headerHeight * 0.65);

        // Parameters - dark text
        gc.setFill(Color.rgb(100, 100, 100));
        gc.setFont(Font.font("Monospace", 10 * nodeScale));
        gc.setTextAlign(TextAlignment.LEFT);
        int availableChars = (int)((width - 20 * nodeScale) / (6 * nodeScale));
        gc.fillText("(" + truncate(cond.params, Math.max(5, availableChars - 2)) + ")", cond.x + 10 * nodeScale, cond.y + headerHeight + 20 * nodeScale);

        // Expression - darker
        gc.setFill(Color.rgb(50, 50, 50));
        gc.setFont(Font.font("Monospace", javafx.scene.text.FontWeight.BOLD, 10 * nodeScale));
        gc.fillText(truncate(cond.expression, Math.max(5, availableChars)), cond.x + 10 * nodeScale, cond.y + headerHeight + 40 * nodeScale);
    }

    private void drawArrow(GraphicsContext gc, double x1, double y1, double x2, double y2, String label, boolean isComputed) {
        if (Math.abs(x1 - x2) < 1 && Math.abs(y1 - y2) < 1) return;

        // Color based on type - more visible colors
        Color lineColor;
        if (isComputed) {
            lineColor = Color.rgb(230, 81, 0, 0.8); // Dark orange
        } else {
            lineColor = Color.rgb(27, 94, 32, 0.8); // Dark green
        }
        gc.setStroke(lineColor);
        gc.setLineWidth(2 * nodeScale);

        // Draw curved line
        double midX = (x1 + x2) / 2;
        double midY = (y1 + y2) / 2;
        double offset = 30 * nodeScale;

        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.quadraticCurveTo(midX + offset, midY - offset, x2, y2);
        gc.stroke();

        // Arrowhead
        double angle = Math.atan2(y2 - (midY - offset), x2 - (midX + offset));
        double arrowLength = 12 * nodeScale;
        gc.setFill(lineColor);
        gc.fillPolygon(
            new double[]{x2, x2 - arrowLength * Math.cos(angle - Math.PI / 6), x2 - arrowLength * Math.cos(angle + Math.PI / 6)},
            new double[]{y2, y2 - arrowLength * Math.sin(angle - Math.PI / 6), y2 - arrowLength * Math.sin(angle + Math.PI / 6)},
            3
        );
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 2) + ".." : s;
    }

    private String extractTypeName(String ref) {
        // Extract type from references like "user", "team#member", "[user]", etc.
        ref = ref.trim();
        if (ref.startsWith("[")) ref = ref.substring(1);
        if (ref.endsWith("]")) ref = ref.substring(0, ref.length() - 1);
        if (ref.contains("#")) ref = ref.split("#")[0];
        if (ref.contains(" with ")) ref = ref.split(" with ")[0];
        return ref.trim();
    }

    // ==================== DSL Parsing for Visualization ====================

    private static class TypeNode {
        String name;
        List<RelationInfo> relations = new ArrayList<>();
        double x, y;
    }

    private static class RelationInfo {
        String name;
        String definition;
        String shortDef;
        boolean isComputed;
        List<String> references = new ArrayList<>();
    }

    private static class ConditionNode {
        String name;
        String params;
        String expression;
        double x, y;
    }

    private List<TypeNode> parseDslForVisualization(String dsl) {
        List<TypeNode> types = new ArrayList<>();
        String[] lines = dsl.split("\n");

        TypeNode currentType = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("type ")) {
                currentType = new TypeNode();
                currentType.name = trimmed.substring(5).trim();
                types.add(currentType);
            } else if (trimmed.startsWith("define ") && currentType != null) {
                RelationInfo rel = new RelationInfo();
                String defPart = trimmed.substring(7);
                int colonIdx = defPart.indexOf(':');
                if (colonIdx > 0) {
                    rel.name = defPart.substring(0, colonIdx).trim();
                    rel.definition = defPart.substring(colonIdx + 1).trim();
                    rel.shortDef = rel.definition;

                    // Determine if computed (contains "or", "and", "from")
                    rel.isComputed = rel.definition.contains(" or ") ||
                                     rel.definition.contains(" and ") ||
                                     rel.definition.contains(" from ");

                    // Extract references
                    rel.references = extractReferences(rel.definition);

                    currentType.relations.add(rel);
                }
            }
        }

        return types;
    }

    private List<String> extractReferences(String definition) {
        List<String> refs = new ArrayList<>();
        // Match type references: word characters, optionally with #member or "with condition"
        Pattern pattern = Pattern.compile("\\[?([a-zA-Z_][a-zA-Z0-9_]*(?:#[a-zA-Z_]+)?(?:\\s+with\\s+[a-zA-Z_]+)?)\\]?");
        Matcher matcher = pattern.matcher(definition);
        while (matcher.find()) {
            String ref = matcher.group(1);
            // Filter out keywords
            if (!ref.equals("or") && !ref.equals("and") && !ref.equals("from") && !ref.equals("with")) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private List<ConditionNode> parseConditionsForVisualization(String dsl) {
        List<ConditionNode> conditions = new ArrayList<>();
        Pattern pattern = Pattern.compile("condition\\s+(\\w+)\\(([^)]+)\\)\\s*\\{([^}]+)\\}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(dsl);

        while (matcher.find()) {
            ConditionNode cond = new ConditionNode();
            cond.name = matcher.group(1);
            cond.params = matcher.group(2).trim();
            cond.expression = matcher.group(3).trim();
            conditions.add(cond);
        }

        return conditions;
    }

    private Tab createTuplesTab() {
        Tab tab = new Tab("Tuples");

        VBox content = new VBox(10);
        content.setPadding(new Insets(15));

        // Text Format Section (Default - expanded)
        Label textLabel = new Label("Tuples (Text Format - paste from AI):");
        textLabel.setStyle("-fx-font-weight: bold;");

        Label formatHint = new Label("Use '---' to separate multiple tuples  |  Lines starting with '#' are comments");
        formatHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        tupleTextArea = new TextArea(DEFAULT_TUPLE_TEXT);
        tupleTextArea.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(tupleTextArea, Priority.ALWAYS);

        // Buttons for text format
        HBox textButtonBox = new HBox(10);
        Button writeFromTextBtn = new Button("Write Tuple(s)");
        writeFromTextBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        writeFromTextBtn.setOnAction(e -> writeTuplesFromText());
        Button deleteFromTextBtn = new Button("Delete Tuple(s)");
        deleteFromTextBtn.setStyle("-fx-background-color: #ff6b6b; -fx-text-fill: white;");
        deleteFromTextBtn.setOnAction(e -> deleteTuplesFromText());
        textButtonBox.getChildren().addAll(writeFromTextBtn, deleteFromTextBtn);

        // Top section with text area
        VBox topSection = new VBox(10);
        topSection.getChildren().addAll(textLabel, formatHint, tupleTextArea, textButtonBox);
        VBox.setVgrow(tupleTextArea, Priority.ALWAYS);

        // Fields Format Section (Collapsible - for rare cases)
        tupleFieldsPane = new TitledPane();
        tupleFieldsPane.setText("Separate Fields (Advanced)");
        tupleFieldsPane.setExpanded(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        tupleUserField = new TextField();
        tupleUserField.setPromptText("e.g., user:alice");
        tupleUserField.setPrefWidth(300);

        tupleRelationField = new TextField();
        tupleRelationField.setPromptText("e.g., viewer");

        tupleObjectField = new TextField();
        tupleObjectField.setPromptText("e.g., carefile:123");
        tupleObjectField.setPrefWidth(300);

        tupleConditionNameField = new TextField();
        tupleConditionNameField.setPromptText("e.g., role_match (optional)");

        tupleConditionContextArea = new TextArea();
        tupleConditionContextArea.setPromptText("{\"required_role\":\"Hulpverlener\"} (optional)");
        tupleConditionContextArea.setPrefRowCount(2);
        tupleConditionContextArea.setStyle("-fx-font-family: monospace;");

        grid.add(new Label("User:"), 0, 0);
        grid.add(tupleUserField, 1, 0);
        grid.add(new Label("Relation:"), 0, 1);
        grid.add(tupleRelationField, 1, 1);
        grid.add(new Label("Object:"), 0, 2);
        grid.add(tupleObjectField, 1, 2);
        grid.add(new Label("Condition:"), 0, 3);
        grid.add(tupleConditionNameField, 1, 3);
        grid.add(new Label("Context:"), 0, 4);
        grid.add(tupleConditionContextArea, 1, 4);

        HBox fieldButtonBox = new HBox(10);
        fieldButtonBox.setPadding(new Insets(10, 0, 0, 0));
        Button writeFieldBtn = new Button("Write Tuple");
        writeFieldBtn.setOnAction(e -> writeTupleFromFields());
        Button deleteFieldBtn = new Button("Delete Tuple");
        deleteFieldBtn.setOnAction(e -> deleteTupleFromFields());
        fieldButtonBox.getChildren().addAll(writeFieldBtn, deleteFieldBtn);

        VBox fieldsContent = new VBox(10);
        fieldsContent.getChildren().addAll(grid, fieldButtonBox);
        tupleFieldsPane.setContent(fieldsContent);

        // SplitPane for adjustable heights
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(topSection, tupleFieldsPane);
        splitPane.setDividerPositions(0.7);

        content.getChildren().add(splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        tab.setContent(content);
        return tab;
    }

    private Tab createCheckTab() {
        Tab tab = new Tab("Check");

        VBox content = new VBox(15);
        content.setPadding(new Insets(15));

        // Text Format Section (Default - expanded)
        Label textLabel = new Label("Check Query (Text Format - paste from AI):");
        textLabel.setStyle("-fx-font-weight: bold;");

        Label formatHint = new Label("Format: user: ... | relation: ... | object: ... | context: {...} (optional)");
        formatHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        checkTextArea = new TextArea(DEFAULT_CHECK_TEXT);
        checkTextArea.setPrefRowCount(5);
        checkTextArea.setStyle("-fx-font-family: monospace;");

        // Button and result for text format
        HBox textButtonBox = new HBox(10);
        textButtonBox.setAlignment(Pos.CENTER_LEFT);
        Button checkFromTextBtn = new Button("Check");
        checkFromTextBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white;");
        checkFromTextBtn.setOnAction(e -> performCheckFromText());

        checkResultLabel = new Label("");
        checkResultLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        textButtonBox.getChildren().addAll(checkFromTextBtn, new Label("  Result: "), checkResultLabel);

        // Fields Format Section (Collapsible - for rare cases)
        checkFieldsPane = new TitledPane();
        checkFieldsPane.setText("Separate Fields (Advanced)");
        checkFieldsPane.setExpanded(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        checkUserField = new TextField();
        checkUserField.setPromptText("e.g., user:alice");
        checkUserField.setPrefWidth(300);

        checkRelationField = new TextField();
        checkRelationField.setPromptText("e.g., viewer");

        checkObjectField = new TextField();
        checkObjectField.setPromptText("e.g., carefile:123");
        checkObjectField.setPrefWidth(300);

        checkContextArea = new TextArea();
        checkContextArea.setPromptText("{\"user_role\":\"Hulpverlener\"} (optional)");
        checkContextArea.setPrefRowCount(2);
        checkContextArea.setStyle("-fx-font-family: monospace;");

        grid.add(new Label("User:"), 0, 0);
        grid.add(checkUserField, 1, 0);
        grid.add(new Label("Relation:"), 0, 1);
        grid.add(checkRelationField, 1, 1);
        grid.add(new Label("Object:"), 0, 2);
        grid.add(checkObjectField, 1, 2);
        grid.add(new Label("Context:"), 0, 3);
        grid.add(checkContextArea, 1, 3);

        HBox fieldButtonBox = new HBox(10);
        fieldButtonBox.setPadding(new Insets(10, 0, 0, 0));
        Button checkFieldBtn = new Button("Check");
        checkFieldBtn.setOnAction(e -> performCheckFromFields());
        fieldButtonBox.getChildren().add(checkFieldBtn);

        VBox fieldsContent = new VBox(10);
        fieldsContent.getChildren().addAll(grid, fieldButtonBox);
        checkFieldsPane.setContent(fieldsContent);

        content.getChildren().addAll(textLabel, formatHint, checkTextArea, textButtonBox, checkFieldsPane);
        tab.setContent(content);
        return tab;
    }

    // ==================== Query Tab ====================

    private static final String DEFAULT_LIST_OBJECTS_TEXT = """
user: user:alice
relation: viewer
type: document
""";

    private static final String DEFAULT_LIST_USERS_TEXT = """
object: document:readme
relation: viewer
usertype: user
""";

    private static final String DEFAULT_EXPAND_TEXT = """
object: document:readme
relation: viewer
""";

    private static final String DEFAULT_READ_TUPLES_TEXT = """
user:
relation:
object:
""";

    private Tab createQueryTab() {
        Tab tab = new Tab("Query");

        VBox content = new VBox(15);
        content.setPadding(new Insets(15));

        Label titleLabel = new Label("Advanced Queries (Text Format - paste from AI)");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Results area (shared by all queries)
        TextArea queryResultArea = new TextArea();
        queryResultArea.setEditable(false);
        queryResultArea.setPrefRowCount(12);
        queryResultArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        queryResultArea.setPromptText("Query results will appear here...");

        // 1. List Objects Section
        TitledPane listObjectsPane = new TitledPane();
        listObjectsPane.setText("List Objects - \"What can this user access?\"");
        listObjectsPane.setExpanded(true);

        VBox loContent = new VBox(10);
        loContent.setPadding(new Insets(10));

        Label loHint = new Label("Format: user: ... | relation: ... | type: ... | context: {...} (optional)");
        loHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        TextArea loTextArea = new TextArea(DEFAULT_LIST_OBJECTS_TEXT);
        loTextArea.setPrefRowCount(5);
        loTextArea.setStyle("-fx-font-family: monospace;");

        Button loBtn = new Button("List Objects");
        loBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        loBtn.setOnAction(e -> {
            Map<String, String> parsed = parseTextFormat(loTextArea.getText());
            performListObjects(
                    parsed.getOrDefault("user", ""),
                    parsed.getOrDefault("relation", ""),
                    parsed.getOrDefault("type", ""),
                    parsed.getOrDefault("context", ""),
                    queryResultArea);
        });

        loContent.getChildren().addAll(loHint, loTextArea, loBtn);
        listObjectsPane.setContent(loContent);

        // 2. List Users Section
        TitledPane listUsersPane = new TitledPane();
        listUsersPane.setText("List Users - \"Who can access this object?\"");
        listUsersPane.setExpanded(false);

        VBox luContent = new VBox(10);
        luContent.setPadding(new Insets(10));

        Label luHint = new Label("Format: object: ... | relation: ... | usertype: ... | context: {...} (optional)");
        luHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        TextArea luTextArea = new TextArea(DEFAULT_LIST_USERS_TEXT);
        luTextArea.setPrefRowCount(5);
        luTextArea.setStyle("-fx-font-family: monospace;");

        Button luBtn = new Button("List Users");
        luBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white;");
        luBtn.setOnAction(e -> {
            Map<String, String> parsed = parseTextFormat(luTextArea.getText());
            performListUsers(
                    parsed.getOrDefault("object", ""),
                    parsed.getOrDefault("relation", ""),
                    parsed.getOrDefault("usertype", ""),
                    parsed.getOrDefault("context", ""),
                    queryResultArea);
        });

        luContent.getChildren().addAll(luHint, luTextArea, luBtn);
        listUsersPane.setContent(luContent);

        // 3. Expand Section
        TitledPane expandPane = new TitledPane();
        expandPane.setText("Expand - \"How is this permission computed?\"");
        expandPane.setExpanded(false);

        VBox exContent = new VBox(10);
        exContent.setPadding(new Insets(10));

        Label exHint = new Label("Format: object: ... | relation: ...");
        exHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        TextArea exTextArea = new TextArea(DEFAULT_EXPAND_TEXT);
        exTextArea.setPrefRowCount(4);
        exTextArea.setStyle("-fx-font-family: monospace;");

        Button exBtn = new Button("Expand");
        exBtn.setStyle("-fx-background-color: #9C27B0; -fx-text-fill: white;");
        exBtn.setOnAction(e -> {
            Map<String, String> parsed = parseTextFormat(exTextArea.getText());
            performExpand(
                    parsed.getOrDefault("object", ""),
                    parsed.getOrDefault("relation", ""),
                    queryResultArea);
        });

        exContent.getChildren().addAll(exHint, exTextArea, exBtn);
        expandPane.setContent(exContent);

        // 4. Read Tuples Section
        TitledPane readTuplesPane = new TitledPane();
        readTuplesPane.setText("Read Tuples - \"What tuples exist?\" (Debug)");
        readTuplesPane.setExpanded(false);

        VBox rtContent = new VBox(10);
        rtContent.setPadding(new Insets(10));

        Label rtHint = new Label("Format: user: ... | relation: ... | object: ... (all optional filters)");
        rtHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        TextArea rtTextArea = new TextArea(DEFAULT_READ_TUPLES_TEXT);
        rtTextArea.setPrefRowCount(4);
        rtTextArea.setStyle("-fx-font-family: monospace;");

        Button rtBtn = new Button("Read Tuples");
        rtBtn.setStyle("-fx-background-color: #FF5722; -fx-text-fill: white;");
        rtBtn.setOnAction(e -> {
            Map<String, String> parsed = parseTextFormat(rtTextArea.getText());
            performReadTuples(
                    parsed.getOrDefault("user", ""),
                    parsed.getOrDefault("relation", ""),
                    parsed.getOrDefault("object", ""),
                    queryResultArea);
        });

        rtContent.getChildren().addAll(rtHint, rtTextArea, rtBtn);
        readTuplesPane.setContent(rtContent);

        // Results label
        Label resultsLabel = new Label("Results:");
        resultsLabel.setStyle("-fx-font-weight: bold;");

        content.getChildren().addAll(
                titleLabel,
                listObjectsPane,
                listUsersPane,
                expandPane,
                readTuplesPane,
                resultsLabel,
                queryResultArea
        );

        tab.setContent(new ScrollPane(content));
        return tab;
    }

    private void performListObjects(String user, String relation, String type, String context, TextArea resultArea) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (user.isBlank() || relation.isBlank() || type.isBlank()) {
            appendOutput("ERROR: User, relation, and type are required");
            return;
        }

        updateServiceConfig();
        appendOutput("Listing objects: " + user + " -> " + relation + " -> " + type + ":*");

        runAsync(() -> {
            long startTime = System.currentTimeMillis();
            var objects = fgaService.listObjects(selected.getId(), user, relation, type, context);
            long duration = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("=== List Objects ===\n");
                sb.append("User: ").append(user).append("\n");
                sb.append("Relation: ").append(relation).append("\n");
                sb.append("Type: ").append(type).append("\n");
                if (!context.isBlank()) sb.append("Context: ").append(context).append("\n");
                sb.append("---\n");
                sb.append("Found ").append(objects.size()).append(" object(s) in ").append(duration).append(" ms\n\n");
                for (String obj : objects) {
                    sb.append("  - ").append(obj).append("\n");
                }
                resultArea.setText(sb.toString());
                appendOutput("Found " + objects.size() + " object(s) in " + duration + " ms");
            });
            return null;
        });
    }

    private void performListUsers(String object, String relation, String userType, String context, TextArea resultArea) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (object.isBlank() || relation.isBlank() || userType.isBlank()) {
            appendOutput("ERROR: Object, relation, and user type are required");
            return;
        }

        // Parse object into type and id
        String[] parts = object.split(":", 2);
        if (parts.length != 2) {
            appendOutput("ERROR: Object must be in format type:id");
            return;
        }

        updateServiceConfig();
        appendOutput("Listing users: " + userType + ":* -> " + relation + " -> " + object);

        runAsync(() -> {
            long startTime = System.currentTimeMillis();
            var response = fgaService.listUsers(selected.getId(), relation, parts[0], parts[1], userType, context);
            long duration = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("=== List Users === (").append(duration).append(" ms)\n");
                sb.append("Object: ").append(object).append("\n");
                sb.append("Relation: ").append(relation).append("\n");
                sb.append("User Type: ").append(userType).append("\n");
                if (!context.isBlank()) sb.append("Context: ").append(context).append("\n");
                sb.append("---\n");
                sb.append(prettyPrintJson(response.toString()));
                resultArea.setText(sb.toString());
                appendOutput("List users completed in " + duration + " ms");
            });
            return null;
        });
    }

    private void performExpand(String object, String relation, TextArea resultArea) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (object.isBlank() || relation.isBlank()) {
            appendOutput("ERROR: Object and relation are required");
            return;
        }

        updateServiceConfig();
        appendOutput("Expanding: " + relation + " on " + object);

        runAsync(() -> {
            long startTime = System.currentTimeMillis();
            var response = fgaService.expand(selected.getId(), relation, object);
            long duration = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("=== Expand === (").append(duration).append(" ms)\n");
                sb.append("Object: ").append(object).append("\n");
                sb.append("Relation: ").append(relation).append("\n");
                sb.append("---\n");
                sb.append(prettyPrintJson(response.toString()));
                resultArea.setText(sb.toString());
                appendOutput("Expand completed in " + duration + " ms");
            });
            return null;
        });
    }

    private void performReadTuples(String user, String relation, String object, TextArea resultArea) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        updateServiceConfig();
        appendOutput("Reading tuples...");

        runAsync(() -> {
            long startTime = System.currentTimeMillis();
            var response = fgaService.readTuples(selected.getId(), user, relation, object);
            long duration = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("=== Read Tuples === (").append(duration).append(" ms)\n");
                if (!user.isBlank()) sb.append("Filter User: ").append(user).append("\n");
                if (!relation.isBlank()) sb.append("Filter Relation: ").append(relation).append("\n");
                if (!object.isBlank()) sb.append("Filter Object: ").append(object).append("\n");
                sb.append("---\n");
                sb.append(prettyPrintJson(response.toString()));
                resultArea.setText(sb.toString());
                appendOutput("Read tuples completed in " + duration + " ms");
            });
            return null;
        });
    }

    private VBox createOutputPanel() {
        VBox outputBox = new VBox(5);
        outputBox.setPadding(new Insets(10, 0, 0, 0));

        Label outputLabel = new Label("Output / Logs:");
        outputLabel.setStyle("-fx-font-weight: bold;");

        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPrefRowCount(8);
        outputArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        Button clearBtn = new Button("Clear");
        clearBtn.setOnAction(e -> outputArea.clear());

        outputBox.getChildren().addAll(outputLabel, outputArea, clearBtn);
        return outputBox;
    }

    // ==================== Text Format Parsing ====================

    /**
     * Parse text format into a map of key-value pairs.
     * Supports formats like:
     *   user: user:alice
     *   relation: viewer
     *   object: carefile:123
     *   condition: role_match
     *   context: {"key":"value"}
     */
    private Map<String, String> parseTextFormat(String text) {
        Map<String, String> result = new HashMap<>();
        String[] lines = text.split("\n");

        StringBuilder currentValue = null;
        String currentKey = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue; // Skip comments

            // Check if this line starts a new key
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String potentialKey = line.substring(0, colonIndex).trim().toLowerCase();
                // Check if it's a known key
                if (potentialKey.equals("user") || potentialKey.equals("relation") ||
                    potentialKey.equals("object") || potentialKey.equals("condition") ||
                    potentialKey.equals("context") || potentialKey.equals("type") ||
                    potentialKey.equals("usertype") || potentialKey.equals("name")) {

                    // Save previous key-value if exists
                    if (currentKey != null && currentValue != null) {
                        result.put(currentKey, currentValue.toString().trim());
                    }

                    currentKey = potentialKey;
                    currentValue = new StringBuilder(line.substring(colonIndex + 1).trim());
                    continue;
                }
            }

            // Continuation of previous value (for multi-line JSON)
            if (currentValue != null) {
                currentValue.append("\n").append(line);
            }
        }

        // Save last key-value
        if (currentKey != null && currentValue != null) {
            result.put(currentKey, currentValue.toString().trim());
        }

        return result;
    }

    // ==================== Tuple Operations ====================

    private void writeTuplesFromText() {
        String text = tupleTextArea.getText();
        String[] tupleBlocks = text.split("---");

        int count = 0;
        for (String block : tupleBlocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            Map<String, String> parsed = parseTextFormat(block);
            String user = parsed.get("user");
            String relation = parsed.get("relation");
            String object = parsed.get("object");
            String condition = parsed.getOrDefault("condition", "");
            String context = parsed.getOrDefault("context", "");

            // Support "name:" as condition name if "condition:" was empty
            if (condition.isBlank() && parsed.containsKey("name")) {
                condition = parsed.get("name");
            }

            if (user != null && !user.isBlank() && relation != null && !relation.isBlank() && object != null && !object.isBlank()) {
                writeTuple(user, relation, object, condition, context);
                count++;
            }
        }

        if (count == 0) {
            appendOutput("ERROR: No valid tuples found");
        } else {
            appendOutput("Processing " + count + " tuple(s)...");
        }
    }

    private void deleteTuplesFromText() {
        String text = tupleTextArea.getText();
        String[] tupleBlocks = text.split("---");

        int count = 0;
        for (String block : tupleBlocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            Map<String, String> parsed = parseTextFormat(block);
            String user = parsed.get("user");
            String relation = parsed.get("relation");
            String object = parsed.get("object");

            if (user != null && !user.isBlank() && relation != null && !relation.isBlank() && object != null && !object.isBlank()) {
                deleteTuple(user, relation, object);
                count++;
            }
        }

        if (count == 0) {
            appendOutput("ERROR: No valid tuples found");
        } else {
            appendOutput("Deleting " + count + " tuple(s)...");
        }
    }

    private void writeTupleFromFields() {
        writeTuple(
            tupleUserField.getText(),
            tupleRelationField.getText(),
            tupleObjectField.getText(),
            tupleConditionNameField.getText(),
            tupleConditionContextArea.getText()
        );
    }

    private void deleteTupleFromFields() {
        deleteTuple(
            tupleUserField.getText(),
            tupleRelationField.getText(),
            tupleObjectField.getText()
        );
    }

    private void writeTuple(String user, String relation, String object, String conditionName, String conditionContext) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (user == null || user.isBlank() || relation == null || relation.isBlank() || object == null || object.isBlank()) {
            appendOutput("ERROR: User, relation, and object are required");
            return;
        }

        updateServiceConfig();
        appendOutput("Writing tuple: " + user + " -> " + relation + " -> " + object);

        runAsync(() -> {
            fgaService.writeTuple(selected.getId(), user, relation, object, conditionName, conditionContext);
            Platform.runLater(() -> {
                appendOutput("Tuple written successfully!");
            });
            return null;
        });
    }

    private void deleteTuple(String user, String relation, String object) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (user == null || user.isBlank() || relation == null || relation.isBlank() || object == null || object.isBlank()) {
            appendOutput("ERROR: User, relation, and object are required");
            return;
        }

        updateServiceConfig();
        appendOutput("Deleting tuple: " + user + " -> " + relation + " -> " + object);

        runAsync(() -> {
            fgaService.deleteTuple(selected.getId(), user, relation, object);
            Platform.runLater(() -> {
                appendOutput("Tuple deleted successfully!");
            });
            return null;
        });
    }

    // ==================== Check Operations ====================

    private void performCheckFromText() {
        Map<String, String> parsed = parseTextFormat(checkTextArea.getText());
        String user = parsed.get("user");
        String relation = parsed.get("relation");
        String object = parsed.get("object");
        String context = parsed.getOrDefault("context", "");

        performCheck(user, relation, object, context);
    }

    private void performCheckFromFields() {
        performCheck(
            checkUserField.getText(),
            checkRelationField.getText(),
            checkObjectField.getText(),
            checkContextArea.getText()
        );
    }

    private void performCheck(String user, String relation, String object, String context) {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        if (user == null || user.isBlank() || relation == null || relation.isBlank() || object == null || object.isBlank()) {
            appendOutput("ERROR: User, relation, and object are required");
            return;
        }

        updateServiceConfig();
        appendOutput("Checking: " + user + " -> " + relation + " -> " + object);

        runAsync(() -> {
            long startTime = System.currentTimeMillis();
            boolean allowed = fgaService.check(selected.getId(), user, relation, object, context);
            long duration = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> {
                if (allowed) {
                    checkResultLabel.setText("ALLOWED (" + duration + " ms)");
                    checkResultLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: green;");
                } else {
                    checkResultLabel.setText("DENIED (" + duration + " ms)");
                    checkResultLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: red;");
                }
                appendOutput("Check result: " + (allowed ? "ALLOWED" : "DENIED") + " in " + duration + " ms");
            });
            return null;
        });
    }

    // ==================== Other Operations ====================

    private void checkCliAvailability() {
        runAsync(() -> {
            boolean available = dslService.isCliAvailable();
            Platform.runLater(() -> {
                if (!available) {
                    appendOutput("WARNING: FGA CLI not found! DSL transformation will not work.");
                    appendOutput("Install it with: winget install openfga.cli");
                    appendOutput("Or download from: https://github.com/openfga/cli/releases");
                } else {
                    appendOutput("FGA CLI detected. Ready to use.");
                }
            });
            return null;
        });
    }

    private void listStores() {
        updateServiceConfig();
        appendOutput("Listing stores...");

        runAsync(() -> {
            var stores = fgaService.listStores();
            Platform.runLater(() -> {
                storeList.clear();
                storeList.addAll(stores);
                appendOutput("Found " + stores.size() + " store(s)");
            });
            return null;
        });
    }

    private void createStore(String name) {
        if (name == null || name.isBlank()) {
            appendOutput("ERROR: Store name cannot be empty");
            return;
        }

        updateServiceConfig();
        appendOutput("Creating store: " + name);

        runAsync(() -> {
            var store = fgaService.createStore(name);
            Platform.runLater(() -> {
                appendOutput("Store created: " + store.getId());
                listStores();
                // Auto-select the new store
                for (StoreInfo s : storeList) {
                    if (s.getId().equals(store.getId())) {
                        storeComboBox.setValue(s);
                        break;
                    }
                }
            });
            return null;
        });
    }

    private void deleteSelectedStore() {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Store");
        confirm.setHeaderText("Are you sure you want to delete this store?");
        confirm.setContentText("Store: " + selected.getName() + " (" + selected.getId() + ")\n\nThis action cannot be undone!");

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            updateServiceConfig();
            appendOutput("Deleting store: " + selected.getName());

            runAsync(() -> {
                fgaService.deleteStore(selected.getId());
                Platform.runLater(() -> {
                    appendOutput("Store deleted successfully");
                    storeComboBox.setValue(null);
                    listStores();
                });
                return null;
            });
        }
    }

    private void validateDsl() {
        String dsl = dslTextArea.getText();
        appendOutput("Validating DSL...");

        runAsync(() -> {
            var result = dslService.validateDsl(dsl);
            Platform.runLater(() -> {
                if (result.isSuccess()) {
                    appendOutput("DSL is valid!");
                } else {
                    appendOutput("DSL validation failed: " + result.getError());
                }
            });
            return null;
        });
    }

    private void applyModel() {
        StoreInfo selected = storeComboBox.getValue();
        if (selected == null) {
            appendOutput("ERROR: No store selected");
            return;
        }

        String dsl = dslTextArea.getText();
        appendOutput("Transforming DSL to JSON...");

        runAsync(() -> {
            // First transform DSL to JSON
            var transformResult = dslService.transformDslToJson(dsl);
            if (!transformResult.isSuccess()) {
                Platform.runLater(() -> {
                    appendOutput("ERROR: DSL transformation failed: " + transformResult.getError());
                });
                return null;
            }

            String json = transformResult.getJson();
            String prettyJson = prettyPrintJson(json);
            Platform.runLater(() -> {
                jsonPreviewArea.setText(prettyJson);
                appendOutput("DSL transformed successfully. Applying model...");
            });

            // Apply the model
            updateServiceConfig();
            String modelId = fgaService.writeAuthorizationModel(selected.getId(), json);

            Platform.runLater(() -> {
                currentAuthModelId = modelId;
                modelIdLabel.setText(modelId);
                modelIdLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: green;");
                appendOutput("Model applied successfully! Model ID: " + modelId);
            });

            return null;
        });
    }

    private void updateServiceConfig() {
        fgaService.setApiUrl(apiUrlField.getText());
        fgaService.setBearerToken(bearerTokenField.getText());
    }

    private void appendOutput(String message) {
        String timestamp = java.time.LocalTime.now().toString().substring(0, 8);
        outputArea.appendText("[" + timestamp + "] " + message + "\n");
    }

    private String prettyPrintJson(String json) {
        try {
            Object jsonObject = jsonMapper.readValue(json, Object.class);
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (Exception e) {
            // If parsing fails, return original
            return json;
        }
    }

    private void runAsync(java.util.concurrent.Callable<Void> task) {
        Task<Void> fxTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                try {
                    return task.call();
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        appendOutput("ERROR: " + e.getMessage());
                        e.printStackTrace();
                    });
                    throw e;
                }
            }
        };
        new Thread(fxTask).start();
    }
}
