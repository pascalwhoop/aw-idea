/* ==========================================================
File:        CustomDocumentListener.java
Description: Logs time from document change events.
Maintainer:  ActivityWatch <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package net.activitywatch.watchers.idea;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;

public class CustomDocumentListener implements DocumentListener {
    @Override
    public void beforeDocumentChange(DocumentEvent documentEvent) {
    }

    @Override
    public void documentChanged(DocumentEvent documentEvent) {
        Document document = documentEvent.getDocument();
        FileDocumentManager instance = FileDocumentManager.getInstance();
        VirtualFile file = instance.getFile(document);
        ActivityWatch.appendHeartbeat(file, ActivityWatch.getProject(document), false);
    }
}