# Template Filler Tool

> **Language: [中文](README.md)**

A lightweight Java Swing desktop tool with a unified variable system for filling text or Word templates, previewing results, and exporting them safely.

## Features

- **Split-pane workspace** → templates and results stay on the left, while the base date and unified variable form stay on the right; both dividers are draggable and their positions are restored on the next launch
- **Unified variable input** → `{{variable}}` is the recommended syntax, and each variable can be Numeric, Short Text, or Multi-line Text; a blank numeric value is treated as 0
- **Legacy syntax compatibility** → existing `[[string]]` placeholders keep working unchanged and default to Multi-line Text when no saved type exists
- **Compact multi-line editing** → multi-line values use a one-line preview in the main window and open in a modal “Expand…” editor
- **Per-type draft protection** → Numeric, Short Text, and Multi-line Text keep independent drafts, so switching types or cancelling a lossy conversion never discards the original content
- **Expression type locking** → a `=` prefix denotes an arithmetic expression, e.g. `{{=variable1/variable2}}`; referenced variables are automatically locked to Numeric, with support for `+ - * / **` and parentheses
- **Calendar base date** → all built-in date variables use the calendar selection as their base; every launch starts with the current system date, and you can type `yyyy-MM-dd` or use the popup calendar to change it
- **Complete date derivation** → supports yesterday/today/tomorrow, previous/current/next month, previous/current/next year, and the first/last day of the current month, with automatic month, year, and leap-year handling
- **Per-template configuration** → application state is stored in `config.json`, while each template keeps its own values and selected types in `Config/<full-template-name>.json`
- **Safe legacy migration** → existing `last_values.json` data is migrated once in read-only mode and the legacy file is never modified or deleted; new configuration writes use temporary files and atomic replacement where supported
- **Generated-result protection** → changing the template, variable value, variable type, or base date immediately invalidates the old result and disables copying or saving it
- **Error navigation and accessibility** → numeric and date errors update live with non-color indicators; use “Locate Error” or `F4` to cycle through them, plus `F1` for help, `Ctrl+Enter` to generate, and `Ctrl+S` to save the template
- **Modeless help window** → includes syntax guidance, every built-in date variable, and a live current-template variable inventory without blocking the main window
- **Multi-template management** → template files with any names are stored in the `Templates/` folder; you can switch via "Choose Template File…", "New Template", save changes back to the corresponding file with "Save Template", and the last-used template is remembered and restored on next startup
- **Edit templates on the fly** → edit and save directly in the UI, or click "Open Folder" to edit with an external editor
- **Word template support** → pick a `.docx` file as a template; `{{variable}}` and `[[string]]` placeholders in the body, tables, headers/footers, footnotes/endnotes, comments and text boxes are all replaced while keeping the original character formatting (font, bold, size, color) and paragraph formatting (alignment, indent, spacing); the finished Word document is exported via "Save Result to File"

## Quick Start

**Requirements**: Windows + JDK 17 or higher.

**Run**:

1. Double-click `launcher.bat`
2. Or run `java -jar TemplateTool.jar` in a command line

**Edit templates**: templates are plain-text files inside the `Templates/` folder. Click "Choose Template File…" in the top bar to pick the template to use, edit in the area below, then click "Save Template" to write back to that file; "New Template" creates a new file; "Open Folder" opens the templates folder for direct template file management.

**Fill variables**: every variable name appears only once in the right-hand form, even when both placeholder syntaxes are used. Ordinary variables can switch among all three types; expression variables remain Numeric. Multi-line edits are committed only when the dialog is confirmed.

**Keyboard shortcuts**: `Ctrl+Enter` generates the result, `Ctrl+S` saves a text template, `F1` opens help, and `F4` cycles through input errors. Auxiliary dialogs close with `Esc`.

**Use a Word template**: use the recommended `{{variable}}` syntax in Word; legacy `[[string]]` placeholders remain supported. A `.docx` template is always shown as a read-only preview: “Save Template” is disabled, but creating a new `.txt` template remains available. Export the generated document with “Save Result to File.”

## Syntax

| Syntax | Description | Example |
| --- | --- | --- |
| `{{variable}}` | Recommended syntax; choose Numeric, Short Text, or Multi-line Text | `{{weather}}` |
| `{{=variable1*variable2}}` | Arithmetic expression; referenced variables are locked to Numeric, result rounded to 2 decimals | `{{=monthlyTotal/monthlyPlan}}` |
| `{{今日年}} {{今日年月}} {{今日年月日}}` | Calendar base date | `{{今日年月日}}` |
| `{{昨日年}} {{昨日年月}} {{昨日年月日}}` | Day before the base date | `{{昨日年月日}}` |
| `{{明日年}} {{明日年月}} {{明日年月日}}` | Day after the base date | `{{明日年月日}}` |
| `{{本月年}} {{本月年月}}` | Base date's year or year-month | `{{本月年月}}` |
| `{{上月年}} {{上月年月}}` | Previous month's year or year-month | `{{上月年月}}` |
| `{{下月年}} {{下月年月}}` | Next month's year or year-month | `{{下月年月}}` |
| `{{上年}} {{本年}} {{下年}}` | Previous, current, or next year relative to the base date | `{{本年}}` |
| `{{本月月首}} {{本月月末}}` | First or last day of the base date's month | `{{本月月末}}` |
| `[[stringName]]` | Legacy-compatible syntax; defaults to Multi-line Text and is never rewritten automatically | `[[remarks]]` |

## Build

Double-click `build.bat`, or run in a command line:

```bat
build.bat
```

Building requires a JDK (with `javac` and `jar`). The build script runs the regression tests first, then generates `TemplateTool.jar` on success.

## Directory Structure

```
├── build.bat               # Compiles the source and packages TemplateTool.jar (auto-detects JDK)
├── launcher.bat            # One-click launch (auto-detects java)
├── Templates/              # Directory for template files (any names; auto-generates example.txt and example.docx on first run)
├── config.json             # Application state such as the last template and divider positions
├── Config/                 # Independent variable values and types for each full template filename
├── last_values.json        # Legacy data; read once for migration and never modified afterward
├── src/main/java/com/firefly/
    ├── Main.java                 # Program entry: sets high-DPI / system look-and-feel, launches the main window
    ├── TemplateToolApp.java      # Main window: UI layout, event handling, calendar base date, and data sync
    ├── TemplateConstants.java    # Global constants: date variables and derivation, placeholders/regex, file names, default example template
    ├── core/                     # Logic layer: template read/write, parsing, rendering, evaluation, etc.
    │   ├── TemplateStore.java       # Reads/writes the Templates folder (list/read/write, generates example.txt and example.docx on first run)
    │   ├── ExpressionEvaluator.java # Safe arithmetic expression evaluation (+ - * / ** and parentheses; hand-written recursive-descent parser)
    │   ├── AppConfigStore.java      # Fault-tolerant, atomic config.json persistence
    │   ├── TemplateConfigStore.java # Per-template configuration under Config/
    │   ├── VariableType.java        # Numeric / Short Text / Multi-line Text types
    │   ├── VariableInputState.java  # Unified value, type, and expression-lock state
    │   ├── LegacyConfigMigrator.java # One-time, read-only last_values.json migration
    │   ├── LastValuesStore.java     # Legacy reader retained for migration only
    │   ├── MiniJson.java            # JSON read/write
    │   ├── DocxProcessor.java       # Word (.docx) templates: text extraction + placeholder replacement + built-in example.docx generation (zero dependencies, keeps run/paragraph formatting)
    │   ├── TemplateParser.java      # Template placeholder parsing: extracts variables / auto dates / string variables
    │   ├── TemplateRenderer.java    # Renders a template to the final result (substitution, evaluation, as-is string output; shared resolve with Word rendering)
    │   ├── TextFileWriter.java      # Text file read/write (UTF-8 with BOM, converts newlines to CRLF, Notepad-compatible)
    │   └── ValueNormalizer.java     # Numeric input validation/normalization (blank is 0, non-numeric returns null)
    └── ui/                     # Swing UI components
        ├── DatePickerPanel.java      # Base-date input and popup calendar (syncs to the system date at startup)
        ├── ResultPanel.java          # "Result Output" area (read-only multi-line text)
        ├── ScrollablePanel.java      # Scrollable vertical form panel (width adapts, height follows content)
        ├── VariableInputPanel.java  # Compact unified input panel for all three variable types
        ├── MultilineEditorDialog.java # Modal editor for complete multi-line values
        ├── VariableTypeConversionDialog.java # Confirmation UI for lossy type conversions
        ├── ValidationIssueManager.java # Live issue collection and cyclic navigation
        └── TemplateHelpDialog.java  # Modeless syntax, date-variable, and current-template help
└── src/test/java/com/firefly/
    └── TemplateFeatureTests.java # Regression tests for unified variables, config safety, and migration
```

## License

[MIT License](LICENSE)
