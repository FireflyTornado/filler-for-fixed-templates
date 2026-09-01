package com.firefly.ui;

import javax.swing.JComponent;

/** 可按界面顺序定位的输入错误。 */
public record ValidationIssue(String id, String variableName, String message,
                              JComponent targetComponent, IssueSeverity severity, int order) { }
