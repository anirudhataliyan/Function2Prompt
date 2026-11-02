package function2prompt;

import java.awt.Toolkit;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JPasswordField;
import java.util.prefs.Preferences;
import javax.swing.SwingWorker;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import docking.ActionContext;
import docking.ComponentProvider;
import docking.action.DockingAction;
import docking.action.MenuData;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.util.task.TaskMonitor;

@PluginInfo(
    status = PluginStatus.RELEASED,
    packageName = "Function2Prompt",
    category = PluginCategoryNames.ANALYSIS,
    shortDescription = "Export function for ChatGPT",
    description = "Decompile the function under the cursor and copy a structured analysis prompt."
)
public class Function2PromptPlugin extends ProgramPlugin {
    private static final int TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_MODEL = "openrouter/free";
    private static final String DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private final AiProvider aiProvider = new AiProvider();

    public Function2PromptPlugin(PluginTool tool) {
        super(tool);
    }

    @Override
    protected void init() {
        super.init();
        tool.addComponentProvider(aiProvider, false);
        DockingAction openAi = new DockingAction("Open AI Assistant", getName()) {
            @Override public void actionPerformed(ActionContext context) {
                aiProvider.setVisible(true);
                aiProvider.refreshContext();
            }
        };
        openAi.setMenuBarData(new MenuData(new String[] { "Tools", "Function2Prompt", "Open AI Assistant" }));
        tool.addAction(openAi);

        DockingAction action = new DockingAction(
            "Export Function for ChatGPT", getName()) {
            @Override
            public void actionPerformed(ActionContext context) {
                exportCurrentFunction();
            }
        };
        action.setMenuBarData(new MenuData(
            new String[] { "Tools", "Function2Prompt", "Export Function for ChatGPT" }));
        action.setPopupMenuData(new MenuData(
            new String[] { "Function2Prompt", "Export Function for ChatGPT" }));
        action.setEnabled(true);
        tool.addAction(action);
    }

    private void exportCurrentFunction() {
        Program program = currentProgram;
        if (program == null || currentLocation == null) {
            Msg.showWarn(this, tool.getActiveWindow(), "Function2Prompt",
                "Open a program and place the cursor inside a function first.");
            return;
        }

        Function function = program.getFunctionManager()
            .getFunctionContaining(currentLocation.getAddress());
        if (function == null) {
            Msg.showWarn(this, tool.getActiveWindow(), "Function2Prompt",
                "The current address is not inside a function.");
            return;
        }

        DecompInterface decompiler = new DecompInterface();
        try {
            decompiler.toggleCCode(true);
            decompiler.toggleSyntaxTree(false);
            decompiler.setSimplificationStyle("decompile");
            if (!decompiler.openProgram(program)) {
                throw new IOException("Could not open program in the decompiler.");
            }

            TaskMonitor monitor = new ConsoleTaskMonitor();
            DecompileResults results = decompiler.decompileFunction(
                function, TIMEOUT_SECONDS, monitor);
            if (!results.decompileCompleted() || results.getDecompiledFunction() == null) {
                String error = results.getErrorMessage();
                throw new IOException("Decompilation failed: " +
                    (error == null || error.isBlank() ? "unknown error" : error));
            }

            String report = buildPrompt(program, function, results.getDecompiledFunction().getC());
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(report), null);
            Path saved = writeReport(program, function, report);
            Msg.showInfo(this, tool.getActiveWindow(), "Function2Prompt",
                "Prompt copied to clipboard.\nSaved report: " + saved);
        }
        catch (Exception e) {
            Msg.showError(this, tool.getActiveWindow(), "Function2Prompt",
                "Could not export function", e);
        }
        finally {
            decompiler.dispose();
        }
    }

    private Function getCurrentFunction() {
        if (currentProgram == null || currentLocation == null) return null;
        return currentProgram.getFunctionManager().getFunctionContaining(currentLocation.getAddress());
    }

    private String decompile(Function function) throws IOException {
        DecompInterface decompiler = new DecompInterface();
        try {
            decompiler.toggleCCode(true);
            decompiler.toggleSyntaxTree(false);
            decompiler.setSimplificationStyle("decompile");
            if (!decompiler.openProgram(currentProgram)) throw new IOException("Could not open program in decompiler.");
            DecompileResults results = decompiler.decompileFunction(function, TIMEOUT_SECONDS, new ConsoleTaskMonitor());
            if (!results.decompileCompleted() || results.getDecompiledFunction() == null) {
                throw new IOException("Decompilation failed: " + results.getErrorMessage());
            }
            return results.getDecompiledFunction().getC();
        } finally { decompiler.dispose(); }
    }

    private final class AiProvider extends ComponentProvider {
        private final JPanel panel = new JPanel(new BorderLayout(6, 6));
        private final JTextArea conversation = new JTextArea();
        private final JTextArea question = new JTextArea(4, 40);
        private final Preferences preferences = Preferences.userNodeForPackage(Function2PromptPlugin.class);
        private final JTextField endpoint = new JTextField(DEFAULT_ENDPOINT, 34);
        private final JTextField model = new JTextField(DEFAULT_MODEL, 18);
        private final JPasswordField apiKey = new JPasswordField(22);
        private final JLabel status = new JLabel("Configure provider settings and save.");
        private final JButton analyse = new JButton("Analyse current function");
        private final JButton send = new JButton("Send");
        private final StringBuilder history = new StringBuilder();
        private String functionContext = "";

        AiProvider() {
            super(Function2PromptPlugin.this.tool, "Function2Prompt AI", "Function2Prompt");
            conversation.setEditable(false);
            conversation.setLineWrap(true);
            conversation.setWrapStyleWord(true);
            conversation.setText("Function2Prompt AI\n\nConfigure your API endpoint, model and API key below, then click Save settings.\n");
            endpoint.setText(preferences.get("endpoint", DEFAULT_ENDPOINT));
            model.setText(preferences.get("model", DEFAULT_MODEL));
            apiKey.setText(preferences.get("apiKey", ""));
            question.setLineWrap(true);
            question.setWrapStyleWord(true);
            JPanel top = new JPanel(new BorderLayout(4, 4));
            JPanel settings = new JPanel(new java.awt.GridLayout(3, 2, 4, 4));
            settings.add(new JLabel("API endpoint:")); settings.add(endpoint);
            settings.add(new JLabel("Model:")); settings.add(model);
            settings.add(new JLabel("API key:")); settings.add(apiKey);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton saveSettings = new JButton("Save settings");
            saveSettings.addActionListener(e -> saveSettings());
            controls.add(saveSettings); controls.add(analyse); controls.add(status);
            top.add(settings, BorderLayout.CENTER); top.add(controls, BorderLayout.SOUTH);
            JPanel bottom = new JPanel(new BorderLayout(4, 4));
            bottom.add(new JScrollPane(question), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton clear = new JButton("Clear chat");
            clear.addActionListener(e -> { history.setLength(0); conversation.setText(""); });
            buttons.add(clear); buttons.add(send); bottom.add(buttons, BorderLayout.SOUTH);
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            panel.add(top, BorderLayout.NORTH);
            panel.add(new JScrollPane(conversation), BorderLayout.CENTER);
            panel.add(bottom, BorderLayout.SOUTH);
            panel.setPreferredSize(new Dimension(620, 520));
            analyse.addActionListener(this::analyseCurrent);
            send.addActionListener(this::sendQuestion);
            updateKeyStatus();
        }

        @Override public JComponent getComponent() { return panel; }

        void refreshContext() { updateKeyStatus(); }
        private void saveSettings() {
            String endpointValue = endpoint.getText().trim();
            String modelValue = model.getText().trim();
            String keyValue = new String(apiKey.getPassword()).trim();
            if (endpointValue.isEmpty() || modelValue.isEmpty() || keyValue.isEmpty()) {
                status.setText("Endpoint, model and API key are required.");
                return;
            }
            try {
                URI uri = URI.create(endpointValue);
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    throw new IllegalArgumentException("Endpoint must use HTTP or HTTPS.");
                preferences.put("endpoint", endpointValue);
                preferences.put("model", modelValue);
                preferences.put("apiKey", keyValue);
                preferences.flush();
                status.setText("Settings saved.");
            } catch (Exception ex) {
                status.setText("Could not save settings: " + ex.getMessage());
            }
        }
        private void updateKeyStatus() {
            status.setText(apiKey.getPassword().length > 0 ? "API key configured" : "API key required");
        }

        private void analyseCurrent(ActionEvent event) {
            Function function = getCurrentFunction();
            if (function == null) { append("[Function2Prompt] Put the cursor inside a function first.\n"); return; }
            analyse.setEnabled(false); send.setEnabled(false); status.setText("Decompiling...");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception {
                    return buildPrompt(currentProgram, function, decompile(function));
                }
                @Override protected void done() {
                    try {
                        functionContext = get();
                        history.setLength(0);
                        history.append("You are an expert reverse-engineering and software-security assistant. Analyse only what can be inferred; distinguish observations from hypotheses.\n\n");
                        history.append(functionContext);
                        append("\n[Analysing " + function.getName() + " at " + function.getEntryPoint() + "]\n");
                        callApi(history.toString(), "Initial function analysis");
                    } catch (Exception ex) { append("[Error] " + ex.getMessage() + "\n"); finishRequest(); }
                }
            }.execute();
        }

        private void sendQuestion(ActionEvent event) {
            String text = question.getText().trim();
            if (text.isEmpty()) return;
            if (functionContext.isEmpty()) { append("Analyse a function first.\n"); return; }
            question.setText("");
            history.append("\nUser: ").append(text).append("\n");
            append("\nYou: " + text + "\n\nThinking...\n");
            callApi(history.toString(), text);
        }

        private void callApi(String input, String userText) {
            String key = new String(apiKey.getPassword()).trim();
            if (key.isBlank()) { append("[Error] Enter your API key in the settings above and click Save settings.\n"); finishRequest(); return; }
            String selectedModel = model.getText().trim();
            if (selectedModel.isEmpty()) selectedModel = DEFAULT_MODEL;
            final String modelName = selectedModel;
            final String endpointUrl = endpoint.getText().trim();
            if (endpointUrl.isEmpty()) { append("[Error] API endpoint is empty.\n"); finishRequest(); return; }
            analyse.setEnabled(false); send.setEnabled(false); status.setText("Calling OpenAI...");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception {
                    String body = "{\"model\":" + json(modelName) + ",\"messages\":[{" +
                        "\"role\":\"system\",\"content\":\"You are an expert reverse-engineering and software-security assistant. Be evidence-based and distinguish observations from hypotheses.\"},{" +
                        "\"role\":\"user\",\"content\":" + json(input) + "}]}";
                    HttpRequest request = HttpRequest.newBuilder(URI.create(endpointUrl))
                        .timeout(Duration.ofMinutes(3)).header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                    HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25)).build()
                        .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw new IOException("OpenAI API HTTP " + response.statusCode() + ": " + response.body());
                    String answer = extractChatCompletionText(response.body());
                    if (answer.isBlank()) throw new IOException("API returned no output_text. Raw response: " + response.body());
                    return answer;
                }
                @Override protected void done() {
                    try {
                        String answer = get();
                        int thinking = conversation.getText().lastIndexOf("Thinking...");
                        if (thinking >= 0) conversation.replaceRange("", thinking, thinking + "Thinking...".length());
                        append(answer + "\n");
                        history.append("Assistant: ").append(answer).append("\n");
                    } catch (Exception ex) {
                        append("[API error] " + rootMessage(ex) + "\n");
                    } finally { finishRequest(); }
                }
            }.execute();
        }

        private void finishRequest() { analyse.setEnabled(true); send.setEnabled(true); status.setText("Ready"); }
        private void append(String text) { conversation.append(text); conversation.setCaretPosition(conversation.getDocument().getLength()); }
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t; while (x.getCause() != null) x = x.getCause();
        return x.getMessage() == null ? x.toString() : x.getMessage();
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c);
            }
        }
        return out.append('\"').toString();
    }

    private static String extractChatCompletionText(String json) {
        int choices = json.indexOf("\"choices\"");
        if (choices < 0) return "";
        int content = json.indexOf("\"content\"", choices);
        if (content < 0) return "";
        int colon = json.indexOf(':', content);
        int start = colon < 0 ? -1 : json.indexOf('\"', colon + 1);
        if (start < 0) return "";
        StringBuilder raw = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!escaped && c == '\"') return unescapeJson(raw.toString());
            raw.append(c);
            if (c == '\\' && !escaped) escaped = true; else escaped = false;
        }
        return "";
    }

    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { out.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case '\"': out.append('\"'); break; case '\\': out.append('\\'); break;
                case '/': out.append('/'); break; case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break; case 'r': out.append('\r'); break; case 't': out.append('\t'); break;
                case 'u': if (i + 4 < s.length()) { out.append((char)Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } break;
                default: out.append(n);
            }
        }
        return out.toString();
    }

    private String buildPrompt(Program program, Function function, String pseudocode) {
        String header = "# Function2Prompt — ChatGPT Function Analysis\n\n" +
            "## Binary context\n" +
            "- Program: " + program.getName() + "\n" +
            "- Function: " + function.getName() + "\n" +
            "- Entry address: " + function.getEntryPoint() + "\n" +
            "- Language ID: " + program.getLanguageID().getIdAsString() + "\n" +
            "- Compiler spec: " + program.getCompilerSpec().getCompilerSpecID() + "\n" +
            "- Exported: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n\n" +
            "## Decompiled pseudocode\n```c\n" + pseudocode + "\n```\n\n" +
            "## Analysis instructions\n" +
            "Analyze only what can reasonably be inferred. Separate observations from hypotheses; " +
            "do not invent missing types or behavior.\n\n" +
            "1. Summarize the function's purpose in plain English.\n" +
            "2. Explain its control flow and important operations.\n" +
            "3. Describe parameters, return value, and likely data structures.\n" +
            "4. Identify API/library calls and explain their significance.\n" +
            "5. Flag security-relevant behavior: input handling, memory operations, parsing, " +
            "authentication, file/network access, and command execution.\n" +
            "6. Identify suspicious patterns and potential vulnerabilities, citing evidence " +
            "and uncertainty.\n" +
            "7. Suggest meaningful names for the function and variables.\n" +
            "8. Recommend callers, callees, cross-references, or other functions to inspect next.\n\n" +
            "Do not claim a vulnerability solely because an operation looks unusual. " +
            "Include a confidence assessment and unanswered questions.\n";
        return header;
    }

    private Path writeReport(Program program, Function function, String report)
        throws IOException {
        String filename = sanitize(program.getName()) + "_" +
            sanitize(function.getName()) + "_" +
            function.getEntryPoint().toString() + ".md";
        String executablePath = program.getExecutablePath();
        if (executablePath == null || executablePath.isBlank()) {
            throw new IOException(
                "Ghidra does not have the original executable path for this program. " +
                "Re-import the binary with its source path available, or configure an " +
                "export directory in a future Function2Prompt version.");
        }

        Path binaryFile;
        try {
            binaryFile = Path.of(executablePath).toAbsolutePath().normalize();
        }
        catch (RuntimeException e) {
            throw new IOException("Invalid original executable path: " + executablePath, e);
        }

        Path dir = binaryFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException(
                "Could not find the binary's parent directory: " + executablePath);
        }

        Path output = dir.resolve(filename);
        Files.writeString(output, report, StandardCharsets.UTF_8);
        return output;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
