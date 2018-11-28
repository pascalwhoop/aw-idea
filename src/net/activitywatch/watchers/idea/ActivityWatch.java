package net.activitywatch.watchers.idea;

import com.intellij.AppTopics;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformUtils;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * This is a port of the WakaTime plugin to serve as a watcher for ActivityWatch.
 */
public class ActivityWatch implements ApplicationComponent {

    public static final String PORT = "5600";

    private static final BigDecimal FREQUENCY = new BigDecimal(2 * 60); // max secs between heartbeats for continuous coding
    public static final Logger log = Logger.getInstance("ActivityWatch");
    private static String VERSION;
    private static String IDE_NAME;
    private static String IDE_VERSION;
    private static MessageBusConnection connection;
    private static Boolean DEBUG = false;
    private static Boolean READY = false;
    private static String lastFile = null;
    private static BigDecimal lastTime = new BigDecimal(0);

    private final int queueTimeoutSeconds = 30;
    private static ConcurrentLinkedQueue<Event> heartbeatsQueue = new ConcurrentLinkedQueue<Event>();
    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static ScheduledFuture<?> scheduledFixture;

    public ActivityWatch() {
        log.setLevel(Level.DEBUG);
    }

    public void initComponent() {
        VERSION = PluginManager.getPlugin(PluginId.getId("com.activitywatch.intellij.plugin")).getVersion();
        log.info("Initializing ActivityWatch plugin v" + VERSION + " (https://activitywatch.com/)");
        //System.out.println("Initializing ActivityWatch plugin v" + VERSION + " (https://activitywatch.com/)");

        // Set runtime constants
        IDE_NAME = PlatformUtils.getPlatformPrefix();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();

        setupDebugging();
        setLoggingLevel();


        setupEventListeners();
        setupQueueProcessor();
        checkDebug();
        log.info("Finished initializing ActivityWatch plugin");



    }


    private void setupEventListeners() {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {

                // save file
                MessageBus bus = ApplicationManager.getApplication().getMessageBus();
                connection = bus.connect();
                connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new CustomSaveListener());

                // edit document
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new CustomDocumentListener());

                // mouse press
                EditorFactory.getInstance().getEventMulticaster().addEditorMouseListener(new CustomEditorMouseListener());

                // scroll document
                EditorFactory.getInstance().getEventMulticaster().addVisibleAreaListener(new CustomVisibleAreaListener());
            }
        });
    }

    private void setupQueueProcessor() {
        final Runnable handler = new Runnable() {
            public void run() {
                processHeartbeatQueue();
            }
        };
        long delay = queueTimeoutSeconds;
        scheduledFixture = scheduler.scheduleAtFixedRate(handler, delay, delay, java.util.concurrent.TimeUnit.SECONDS);
    }


    private void checkDebug() {
        if (ActivityWatch.DEBUG) {
            try {
                Messages.showWarningDialog("Running ActivityWatch in DEBUG mode. Your IDE may be slow when saving or editing files.", "Debug");
            } catch (NullPointerException e) {
            }
        }
    }

    public void disposeComponent() {
        try {
            connection.disconnect();
        } catch (Exception e) {
        }
        try {
            scheduledFixture.cancel(true);
        } catch (Exception e) {
        }

        // make sure to send all heartbeats before exiting
        processHeartbeatQueue();
    }

    public static BigDecimal getCurrentTimestamp() {
        return new BigDecimal(String.valueOf(System.currentTimeMillis() / 1000.0)).setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    public static void appendHeartbeat(final VirtualFile file, Project project, final boolean isWrite) {
        if (!shouldLogFile(file))
            return;
        final String projectName = project != null ? project.getName() : null;
        final BigDecimal time = ActivityWatch.getCurrentTimestamp();
        if (!isWrite && file.getPath().equals(ActivityWatch.lastFile) && !enoughTimePassed(time)) {
            return;
        }
        ActivityWatch.lastFile = file.getPath();
        ActivityWatch.lastTime = time;
        final String language = ActivityWatch.getLanguage(file);
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            public void run() {
                Event h = new Event();
                h.entity = file.getPath();
                h.timestamp = time;
                h.isWrite = isWrite;
                h.project = projectName;
                h.language = language;
                heartbeatsQueue.add(h);
            }
        });
    }

    private static void processHeartbeatQueue() {
        if (ActivityWatch.READY) {

            // get single event from queue
            Event event = heartbeatsQueue.poll();
            if (event == null)
                return;

            // get all extra heartbeats from queue
            ArrayList<Event> extraEvents = new ArrayList<Event>();
            while (true) {
                Event h = heartbeatsQueue.poll();
                if (h == null)
                    break;
                extraEvents.add(h);
            }

            sendHeartbeat(event, extraEvents);
        }
    }

    private static void sendHeartbeat(final Event event, final ArrayList<Event> extraEvents) {
        final String[] cmds = buildJsonString(event, extraEvents);
        log.debug("Executing CLI: " + Arrays.toString(obfuscateKey(cmds)));
        try {
            Process proc = Runtime.getRuntime().exec(cmds);
            if (extraEvents.size() > 0) {
                String json = toJSON(extraEvents);
                log.debug(json);
                try {
                    BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
                    stdin.write(json);
                    stdin.write("\n");
                    try {
                        stdin.flush();
                        stdin.close();
                    } catch (IOException e) { /* ignored because activitywatch-cli closes pipe after receiving \n */ }
                } catch (IOException e) {
                    log.warn(e);
                }
            }
            if (ActivityWatch.DEBUG) {
                BufferedReader stdout = new BufferedReader(new
                        InputStreamReader(proc.getInputStream()));
                BufferedReader stderr = new BufferedReader(new
                        InputStreamReader(proc.getErrorStream()));
                proc.waitFor();
                String s;
                while ((s = stdout.readLine()) != null) {
                    log.debug(s);
                }
                while ((s = stderr.readLine()) != null) {
                    log.debug(s);
                }
                log.debug("Command finished with return value: " + proc.exitValue());
            }
        } catch (Exception e) {
            log.warn(e);
        }
    }

    private static String[] buildJsonString(Event event, ArrayList<Event> extraEvents) {
        // TODO
        return null;
    }

    private static String toJSON(ArrayList<Event> extraEvents) {
        StringBuffer json = new StringBuffer();
        json.append("[");
        boolean first = true;
        for (Event event : extraEvents) {
            StringBuffer h = new StringBuffer();
            h.append("{\"entity\":\"");
            h.append(jsonEscape(event.entity));
            h.append("\",\"timestamp\":");
            h.append(event.timestamp.toPlainString());
            h.append(",\"is_write\":");
            h.append(event.isWrite.toString());
            if (event.project != null) {
                h.append(",\"project\":\"");
                h.append(jsonEscape(event.project));
                h.append("\"");
            }
            if (event.language != null) {
                h.append(",\"language\":\"");
                h.append(jsonEscape(event.language));
                h.append("\"");
            }
            h.append("}");
            if (!first)
                json.append(",");
            json.append(h.toString());
            first = false;
        }
        json.append("]");
        return json.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null)
            return null;
        StringBuffer escaped = new StringBuffer();
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    boolean isUnicode = (c >= '\u0000' && c <= '\u001F') || (c >= '\u007F' && c <= '\u009F') || (c >= '\u2000' && c <= '\u20FF');
                    if (isUnicode) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = 0; k < 4 - hex.length(); k++) {
                            escaped.append('0');
                        }
                        escaped.append(hex.toUpperCase());
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    private static String getLanguage(final VirtualFile file) {
        FileType type = file.getFileType();
        if (type != null)
            return type.getName();
        return null;
    }

    public static boolean enoughTimePassed(BigDecimal currentTime) {
        return ActivityWatch.lastTime.add(FREQUENCY).compareTo(currentTime) < 0;
    }

    public static boolean shouldLogFile(VirtualFile file) {
        if (file == null || file.getUrl().startsWith("mock://")) {
            return false;
        }
        String filePath = file.getPath();
        if (filePath.equals("atlassian-ide-plugin.xml") || filePath.contains("/.idea/workspace.xml")) {
            return false;
        }
        return true;
    }

    public static Project getProject(Document document) {
        Editor[] editors = EditorFactory.getInstance().getEditors(document);
        if (editors.length > 0) {
            return editors[0].getProject();
        }
        return null;
    }

    @NotNull
    public String getComponentName() {
        return "ActivityWatch";
    }
}
