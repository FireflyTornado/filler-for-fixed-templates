package com.firefly.application;

import com.firefly.core.TemplateRenderer;
import java.nio.file.Path;

/** 成功返回后，临时 Word 文件由调用方负责接收、导出或清理。 */
public record GeneratedResult(GenerationRequest request, TemplateRenderer.RenderResult renderResult,
                              Path docxFile) { }
