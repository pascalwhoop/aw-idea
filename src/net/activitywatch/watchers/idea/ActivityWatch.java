package net.activitywatch.watchers.idea;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformUtils;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

/**
 * This is a port of the WakaTime plugin to serve as a watcher for ActivityWatch.
 */
public class ActivityWatch implements ApplicationComponent {

    public static final String PORT = "5600";
    private static ObjectMapper mapper = new ObjectMapper();

    private static final Logger log = Logger.getInstance("ActivityWatch");
    private static String VERSION;
    private static String IDE_NAME = "Idea";
    private static String IDE_VERSION;
    private static MessageBusConnection connection;
    private static Boolean READY = false;
    private static String lastFile = null;
    static BigDecimal lastTime = new BigDecimal(0);

    private static ConcurrentLinkedQueue<Event> messageQueue = new ConcurrentLinkedQueue<>();
    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static ScheduledFuture<?> scheduledFixture;

    public ActivityWatch() {
        log.setLevel(Level.DEBUG);
    }

    public void initComponent() {
        VERSION = Objects.requireNonNull(PluginManager.getPlugin(PluginId.getId("com.activitywatch.intellij.plugin"))).getVersion();
        log.info("Initializing ActivityWatch plugin v" + VERSION + " (https://activitywatch.com/)");
        //System.out.println("Initializing ActivityWatch plugin v" + VERSION + " (https://activitywatch.com/)");

        // Set runtime constants
        IDE_NAME = PlatformUtils.getPlatformPrefix();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();
        READY = true;


        setupEventListeners();
        setupQueueProcessor();
        log.info("Finished initializing ActivityWatch plugin");


    }


    private void setupEventListeners() {
        ApplicationManager.getApplication().invokeLater(() -> {

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
        });
    }

    private void setupQueueProcessor() {
        final Runnable handler = ActivityWatch::processHeartbeatQueue;
        long delay = 30;
        scheduledFixture = scheduler.scheduleAtFixedRate(handler, delay, delay, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void disposeComponent() {
        try {
            connection.disconnect();
        } catch (Exception ignored) {
        }
        try {
            scheduledFixture.cancel(true);
        } catch (Exception ignored) {
        }

        // make sure to send all heartbeats before exiting
        processHeartbeatQueue();
    }

    static BigDecimal getCurrentTimestamp() {
        return new BigDecimal(String.valueOf(System.currentTimeMillis() / 1000.0)).setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * Key method that records all events to the server
     *
     * @param file    the file currently open
     * @param project current project
     * @param isWrite TODO??
     */
    static void appendHeartbeat(final VirtualFile file, Project project, final boolean isWrite) {
        if (!shouldLogFile(file))
            return;

        ActivityWatch.lastTime = ActivityWatch.getCurrentTimestamp();
        ActivityWatch.lastFile = file.getPath();

        //TODO ??
        if (!isWrite && file.getPath().equals(ActivityWatch.lastFile)) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            EventData data = new EventData(file, project, getLanguage(file));
            Event h = new Event(data);
            messageQueue.add(h);
        });
    }

    private static void processHeartbeatQueue() {
        if (!ActivityWatch.READY) {
            return;
        }
        // process all of them
        Event event;
        do {
            event = messageQueue.poll();
            sendEvent(event);
        } while (event != null);
    }

    /**
     * sends event to server. The event is converted to a a JSON string and posted to the API
     *
     * @param event passing of the event to the backend
     */
    private static void sendEvent(final Event event) {
        //TODO??
    }


    private static String toJSON(Event event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return null;
        }
    }


    private static String getLanguage(final VirtualFile file) {
        return file.getFileType().getName();
    }

    private static boolean shouldLogFile(VirtualFile file) {
        if (file == null || file.getUrl().startsWith("mock://")) {
            return false;
        }
        String filePath = file.getPath();
        return !filePath.equals("atlassian-ide-plugin.xml") && !filePath.contains("/.idea/workspace.xml");
    }

    static Project getProject(Document document) {
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
