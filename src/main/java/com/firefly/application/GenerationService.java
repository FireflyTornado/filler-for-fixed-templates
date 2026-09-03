package com.firefly.application;

import com.firefly.core.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** 文本/Word 生成业务；进度、取消和结果接收均通过非界面接口传入。 */
public final class GenerationService {
    public GeneratedResult generate(GenerationRequest request, OperationProgress progress,
                                    Runnable checkpoint, Consumer<Path> temporaryCreated) throws IOException {
        progress.update(FileOperationText.GENERATE_RESULT.inProgress(), 5, 100);
        checkpoint.run();
        if (!request.word()) {
            TemplateRenderer.RenderResult result = TemplateRenderer.renderUnified(
                    request.templateText(), request.values(), request.autoValues(),
                    request.numericVariables(), request.decimalPlaces());
            progress.update(FileOperationText.GENERATE_RESULT.inProgress(), 100, 100);
            return new GeneratedResult(request, result, null);
        }
        Path temp = Files.createTempFile("tt_result", ".docx");
        temp.toFile().deleteOnExit();
        try {
            temporaryCreated.accept(temp);
            TemplateRenderer.RenderResult result = DocxProcessor.renderUnified(
                    request.sourceFile(), temp, request.values(), request.autoValues(),
                    request.numericVariables(), request.decimalPlaces(), progress);
            checkpoint.run();
            return new GeneratedResult(request, result, temp);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(temp); } catch (IOException cleanupError) { e.addSuppressed(cleanupError); }
            throw e;
        }
    }
}
