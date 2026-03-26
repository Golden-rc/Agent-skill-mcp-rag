package com.eureka.mcp.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CalendarTaskSyncTool implements McpTool {

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path storePath = Paths.get("data", "calendar-tasks.json");

    @Override
    public String name() {
        return "calendar_task_sync";
    }

    @Override
    public String description() {
        return "Create/list/complete/delete simple calendar tasks";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "create|list|complete|delete"),
                        "id", Map.of("type", "string", "description", "Task id for complete/delete"),
                        "title", Map.of("type", "string", "description", "Task title for create"),
                        "dueTime", Map.of("type", "string", "description", "ISO datetime, optional"),
                        "assignee", Map.of("type", "string", "description", "Optional owner")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public synchronized String execute(Map<String, Object> arguments) {
        try {
            String action = String.valueOf(arguments.getOrDefault("action", "list")).trim().toLowerCase();
            List<TaskItem> tasks = load();

            return switch (action) {
                case "create" -> create(arguments, tasks);
                case "complete" -> complete(arguments, tasks);
                case "delete" -> delete(arguments, tasks);
                case "list" -> list(tasks);
                default -> "日程任务同步失败: 不支持 action=" + action;
            };
        } catch (Exception e) {
            return "日程任务同步失败: " + safeMessage(e);
        }
    }

    private String create(Map<String, Object> arguments, List<TaskItem> tasks) throws Exception {
        String title = String.valueOf(arguments.getOrDefault("title", "")).trim();
        if (title.isBlank()) {
            return "日程任务同步失败: create 需要 title";
        }
        String dueTime = String.valueOf(arguments.getOrDefault("dueTime", "")).trim();
        String assignee = String.valueOf(arguments.getOrDefault("assignee", "")).trim();
        TaskItem task = new TaskItem();
        task.id = UUID.randomUUID().toString().substring(0, 8);
        task.title = title;
        task.dueTime = dueTime;
        task.assignee = assignee;
        task.status = "open";
        task.createdAt = Instant.now().toString();
        tasks.add(task);
        persist(tasks);
        return "日程任务已创建\nID: " + task.id + "\nTitle: " + task.title + "\nDue: " + (dueTime.isBlank() ? "(none)" : dueTime);
    }

    private String complete(Map<String, Object> arguments, List<TaskItem> tasks) throws Exception {
        String id = String.valueOf(arguments.getOrDefault("id", "")).trim();
        if (id.isBlank()) {
            return "日程任务同步失败: complete 需要 id";
        }
        for (TaskItem t : tasks) {
            if (id.equals(t.id)) {
                t.status = "done";
                t.completedAt = Instant.now().toString();
                persist(tasks);
                return "日程任务已完成\nID: " + t.id + "\nTitle: " + t.title;
            }
        }
        return "日程任务同步失败: 未找到 id=" + id;
    }

    private String delete(Map<String, Object> arguments, List<TaskItem> tasks) throws Exception {
        String id = String.valueOf(arguments.getOrDefault("id", "")).trim();
        if (id.isBlank()) {
            return "日程任务同步失败: delete 需要 id";
        }
        boolean removed = tasks.removeIf(t -> id.equals(t.id));
        if (!removed) {
            return "日程任务同步失败: 未找到 id=" + id;
        }
        persist(tasks);
        return "日程任务已删除\nID: " + id;
    }

    private String list(List<TaskItem> tasks) {
        if (tasks.isEmpty()) {
            return "日程任务列表为空";
        }
        tasks.sort(Comparator.comparing(t -> t.createdAt == null ? "" : t.createdAt));
        StringBuilder sb = new StringBuilder("日程任务列表\n");
        for (TaskItem t : tasks) {
            sb.append("- [")
                    .append(t.status == null ? "open" : t.status)
                    .append("] ")
                    .append(t.id)
                    .append(" | ")
                    .append(t.title)
                    .append(" | due=")
                    .append(t.dueTime == null || t.dueTime.isBlank() ? "(none)" : t.dueTime)
                    .append(" | assignee=")
                    .append(t.assignee == null || t.assignee.isBlank() ? "(none)" : t.assignee)
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private List<TaskItem> load() throws Exception {
        Files.createDirectories(storePath.getParent());
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(storePath.toFile(), new TypeReference<List<TaskItem>>() {
        });
    }

    private void persist(List<TaskItem> tasks) throws Exception {
        Files.createDirectories(storePath.getParent());
        objectMapper.writeValue(storePath.toFile(), tasks);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "unknown" : message.replaceAll("\\s+", " ").trim();
    }

    static class TaskItem {
        public String id;
        public String title;
        public String dueTime;
        public String assignee;
        public String status;
        public String createdAt;
        public String completedAt;
    }
}
