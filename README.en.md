# Template Filler Tool

> **Language: [中文](README.md)**

A lightweight Java Swing desktop tool: fills pre-written fixed templates into complete text, and supports copying to the clipboard or saving to a file.

## Features

- **Auto-generated input fields** → every `{{variable}}` in a template automatically becomes a numeric input field; leaving it blank is treated as 0, and entering a non-numeric value shows a warning
- **Addable strings** → `[[string]]` uses a large text input area, output as-is (including line breaks, spaces, formatting)
- **Operator support** → a `=` prefix denotes an arithmetic expression, e.g. `{{=variable1/variable2}}`, supporting `+ - * / **` and parentheses, with results rounded to 2 decimal places
- **Automatic date retrieval** → `{{todayYear}}`, `{{todayYearMonth}}`, `{{todayYearMonthDay}}` automatically take the current system date, and `{{yesterdayYear}}`, `{{yesterdayYearMonth}}`, `{{yesterdayYearMonthDay}}` automatically take the previous day's date
- **Remembers last input** → each template remembers its last filled values, auto-saved to `last_values.json`, and auto-restored when that template is opened
- **Multi-template management** → template files with any names are stored in the `Templates/` folder; you can switch via "Choose Template File…", "New Template", save changes back to the corresponding file with "Save Template", and the last-used template is remembered and restored on next startup
- **Edit templates on the fly** → edit and save directly in the UI, or click "Open Folder" to edit with an external editor

## Quick Start

**Requirements**: Windows + JDK 17 or higher.

**Run**:

1. Double-click `launcher.bat`
2. Or run `java -jar TemplateTool.jar` in a command line

**Edit templates**: templates are plain-text files inside the `Templates/` folder. Click "Choose Template File…" in the top bar to pick the template to use, edit in the area below, then click "Save Template" to write back to that file; "New Template" creates a new file; "Open Folder" opens the templates folder for direct template file management.

## Syntax

| Syntax | Description | Example |
| --- | --- | --- |
| `{{variable}}` | Generates a numeric input field; blank is treated as 0 | `{{yesterdayData}}` |
| `{{=variable1*variable2}}` | Arithmetic expression (requires `=` prefix), result rounded to 2 decimal places | `{{=monthlyTotal/monthlyPlan}}` |
| `{{todayYear}} {{todayYearMonth}} {{todayYearMonthDay}}` | Auto-fetches today's date variable | `{{todayYearMonthDay}}` |
| `{{yesterdayYear}} {{yesterdayYearMonth}} {{yesterdayYearMonthDay}}` | Auto-fetches yesterday's date variable | `{{yesterdayYearMonthDay}}` |
| `[[stringName]]` | Multi-line text, output as-is | `[[remarks]]` |

## Build

Double-click `build.bat`, or run in a command line:

```bat
build.bat
```

Building requires a JDK (with `javac` and `jar`); on success it generates `TemplateTool.jar`.

## Directory Structure

```
├── build.bat               # Compiles the source and packages TemplateTool.jar (auto-detects JDK)
├── launcher.bat            # One-click launch (auto-detects java)
├── Templates/              # Directory for template files (any names; auto-generates example.txt on first run)
├── last_values.json        # Per-template memory of last input + last-used template (auto-generated after use)
└── src/main/java/com/firefly/
    ├── Main.java                 # Program entry: sets high-DPI / system look-and-feel, launches the main window
    ├── TemplateToolApp.java      # Main window: UI layout, event handling, data sync
    ├── TemplateConstants.java    # Global constants: file names, automatic date variables, placeholders/regex, default example template
    ├── core/                     # Logic layer: template read/write, parsing, rendering, evaluation, etc.
    │   ├── TemplateStore.java       # Reads/writes the Templates folder (list/read/write, generates example template on first run)
    │   ├── ExpressionEvaluator.java # Safe arithmetic expression evaluation (+ - * / ** and parentheses; hand-written recursive-descent parser)
    │   ├── LastValuesStore.java     # Reads/writes last_values.json (last input/template memory; UTF-8 with BOM)
    │   ├── MiniJson.java            # JSON read/write
    │   ├── TemplateParser.java      # Template placeholder parsing: extracts variables / auto dates / string variables
    │   ├── TemplateRenderer.java    # Renders a template to the final result (substitution, evaluation, as-is string output)
    │   ├── TextFileWriter.java      # Text file read/write (UTF-8 with BOM, converts newlines to CRLF, Notepad-compatible)
    │   └── ValueNormalizer.java     # Numeric input validation/normalization (blank is 0, non-numeric returns null)
    └── ui/                     # Swing UI components
        ├── ResultPanel.java          # "Result Output" area (read-only multi-line text)
        ├── ScrollablePanel.java      # Scrollable vertical form panel (width adapts, height follows content)
        └── InputPanel.java           # "Variable/String Input" area (variables {{}} single-line, strings [[]] multi-line, with error highlighting)
```

## License

[MIT License](LICENSE)
