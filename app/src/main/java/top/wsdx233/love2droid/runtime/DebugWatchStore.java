package top.wsdx233.love2droid.runtime;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Persists per-project debug watch expressions outside project archives. */
public final class DebugWatchStore {
    private static final int VERSION = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    static final int MAX_WATCHES = 128;
    static final int MAX_EXPRESSION_LENGTH = 2048;
    private static final Pattern SAFE_PROJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String DIRECTORY_NAME = "debug-watches";

    private final AtomicFile stateFile;

    DebugWatchStore(Context context, String projectId) {
        String safeProjectId = safeProjectId(projectId);
        stateFile = safeProjectId == null
            ? null
            : new AtomicFile(new File(new File(context.getApplicationContext().getFilesDir(), DIRECTORY_NAME), safeProjectId + ".json"));
    }

    List<Watch> load() {
        if (stateFile == null) return Collections.emptyList();
        try (FileInputStream input = stateFile.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_FILE_BYTES) return Collections.emptyList();
                output.write(buffer, 0, read);
            }
            JSONObject root = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            if (root.optInt("version") != VERSION) return Collections.emptyList();
            JSONArray values = root.optJSONArray("watches");
            if (values == null) return Collections.emptyList();
            List<Watch> watches = new ArrayList<>(Math.min(values.length(), MAX_WATCHES));
            for (int index = 0; index < values.length() && watches.size() < MAX_WATCHES; ++index) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String expression = value.optString("expression").trim();
                if (expression.isEmpty() || expression.length() > MAX_EXPRESSION_LENGTH) continue;
                watches.add(new Watch(expression, value.optBoolean("pinned")));
            }
            return watches;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    boolean save(List<Watch> watches) {
        if (watches.size() > MAX_WATCHES) return false;
        if (stateFile == null) return true;
        if (watches.isEmpty()) {
            stateFile.delete();
            return true;
        }
        File parent = stateFile.getBaseFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        byte[] content;
        try {
            JSONArray values = new JSONArray();
            for (Watch watch : watches) {
                if (watch == null || watch.expression == null || watch.expression.isEmpty()
                    || watch.expression.length() > MAX_EXPRESSION_LENGTH) return false;
                values.put(new JSONObject()
                    .put("expression", watch.expression)
                    .put("pinned", watch.pinned));
            }
            content = new JSONObject()
                .put("version", VERSION)
                .put("watches", values)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return false;
        }
        if (content.length > MAX_FILE_BYTES) return false;
        FileOutputStream output = null;
        try {
            output = stateFile.startWrite();
            output.write(content);
            stateFile.finishWrite(output);
            return true;
        } catch (Exception ignored) {
            if (output != null) stateFile.failWrite(output);
            return false;
        }
    }

    public static void renameProject(Context context, String oldProjectId, String newProjectId) {
        if (safeProjectId(oldProjectId) == null || safeProjectId(newProjectId) == null || oldProjectId.equals(newProjectId)) return;
        DebugWatchStore oldStore = new DebugWatchStore(context, oldProjectId);
        List<Watch> watches = oldStore.load();
        if (watches.isEmpty()) {
            oldStore.delete();
            return;
        }
        DebugWatchStore newStore = new DebugWatchStore(context, newProjectId);
        if (newStore.save(watches)) oldStore.delete();
    }

    public static void deleteProject(Context context, String projectId) {
        new DebugWatchStore(context, projectId).delete();
    }

    private void delete() {
        if (stateFile != null) stateFile.delete();
    }

    private static String safeProjectId(String projectId) {
        if (projectId == null || !SAFE_PROJECT_ID.matcher(projectId).matches()) return null;
        return projectId;
    }

    static final class Watch {
        final String expression;
        final boolean pinned;

        Watch(String expression, boolean pinned) {
            this.expression = expression;
            this.pinned = pinned;
        }
    }
}
