package com.firefly.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 维护当前有效错误，并提供按界面顺序循环定位的游标。 */
public final class ValidationIssueManager {
    private final Map<String, ValidationIssue> byId = new LinkedHashMap<>();
    private Runnable listener = () -> { };
    private int currentIndex = -1;

    public void setChangeListener(Runnable listener) {
        this.listener = listener == null ? () -> { } : listener;
    }

    public void put(ValidationIssue issue) {
        ValidationIssue previous = byId.put(issue.id(), issue);
        if (!issue.equals(previous)) changed();
    }

    public void remove(String id) {
        if (byId.remove(id) != null) changed();
    }

    public void clear() {
        if (!byId.isEmpty()) {
            byId.clear();
            changed();
        } else currentIndex = -1;
    }

    public void retainIds(java.util.Set<String> ids) {
        if (byId.keySet().removeIf(id -> !ids.contains(id))) changed();
    }

    public int count() { return byId.size(); }
    public boolean contains(String id) { return byId.containsKey(id); }
    public int currentPosition() { return currentIndex < 0 ? 0 : currentIndex + 1; }

    public List<ValidationIssue> issues() {
        List<ValidationIssue> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparingInt(ValidationIssue::order).thenComparing(ValidationIssue::id));
        return result;
    }

    public ValidationIssue next() {
        List<ValidationIssue> ordered = issues();
        if (ordered.isEmpty()) { currentIndex = -1; return null; }
        currentIndex = (currentIndex + 1) % ordered.size();
        return ordered.get(currentIndex);
    }

    private void changed() {
        if (currentIndex >= byId.size()) currentIndex = -1;
        listener.run();
    }
}
