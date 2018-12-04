package net.activitywatch.watchers.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public class EventData {

    public EventData(VirtualFile file, Project project, String language) {
        this.file = file.getName();
        this.project = project.getName();
        this.language = file.getExtension();
    }

    public final String file;
    public final String project;
    public final String language;
    public final String eventType = "app.editor.activity";
}
