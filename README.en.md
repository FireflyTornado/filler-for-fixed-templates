# Template Filler Tool

> **Language: [中文](README.md)**

A lightweight desktop utility built with Java Swing: it fills a fixed template with your inputs to produce complete text, ready to copy to the clipboard or save to a file.

## Features

- **Auto-generated input fields** → Each `{{variable}}` in the template becomes a numeric input field; empty fields default to `0`, and non-numeric input triggers a warning
- **Add free-form strings** → `[[string]]` uses a large text input; the content is output verbatim (line breaks, spaces, and formatting preserved)
- **Expression support** → e.g. `{{var1/var2}}`, supporting `+ - * / **` and parentheses, with results rounded to two decimal places
- **Automatic dates** → `{{year}}`, `{{yearMonth}}`, `{{yearMonthDay}}` pull the current system date automatically
- **Remembers your last input** → Previously entered values are saved to `last_values.json` and restored the next time you open the tool
- **Edit templates on the fly** → Modify and save directly in the UI, or open `template.conf` with your system editor

## Quick Start

**Requirements**: Windows + JDK 17 or later.

**Run** (either way):

1. Double-click `launcher.bat`
2. Run `java -jar TemplateTool.jar` from the command line

**Modify the template**: Open `template.conf` and edit the `template =` value (you can also edit it directly in the UI, then click "Save Template").

## Placeholder Syntax

| Syntax | Description | Example |
| --- | --- | --- |
| `{{variable}}` | Generates a numeric input field; empty defaults to `0` | `{{yesterdayData}}` |
| `{{var1*var2}}` | Arithmetic expression, result rounded to 2 decimals | `{{monthlyTotal/monthlyPlan}}` |
| `{{year}} {{yearMonth}} {{yearMonthDay}}` | Auto-fetched date variables | `{{yearMonthDay}}` |
| `[[stringName]]` | Multi-line text, output verbatim | `[[notes]]` |
| `\n` | Line break in the template | `First line\nSecond line` |

## Build

Double-click `build.bat`, or run it from the command line:

```bat
build.bat
```

Building requires a JDK (with `javac` and `jar`). On success, `TemplateTool.jar` is generated.

## Directory Structure

```
├── build.bat               # Compiles sources and packages TemplateTool.jar (auto-detects JDK)
├── launcher.bat            # One-click launcher (auto-detects java)
├── template.conf           # Template configuration file (auto-generated with defaults on first run if missing)
├── last_values.json        # Memory file for your last input (auto-generated after each use)
└── src/main/java/com/firefly/
    ├── Main.java                 # Program entry point: high-DPI / system look-and-feel, starts the main window
    ├── TemplateToolApp.java      # Main window: layout, event handling, data sync
    ├── TemplateConstants.java    # Global constants: file names, auto date variables, placeholder regexes, default config
    ├── core/                     # Logic layer: config I/O, parsing, rendering, expression evaluation, etc.
    │   ├── ConfigStore.java         # template.conf read/write
    │   ├── ExpressionEvaluator.java # Safe arithmetic evaluation (+ - * / ** and parentheses, hand-written recursive-descent parser)
    │   ├── LastValuesStore.java     # last_values.json read/write (last-input memory, UTF-8 with BOM)
    │   ├── MiniJson.java            # JSON read/write
    │   ├── TemplateParser.java      # Template placeholder parsing: extracts variables / auto dates / string variables
    │   ├── TemplateRenderer.java    # Renders the template into the final result (substitution, evaluation, verbatim strings)
    │   ├── TextFileWriter.java      # Text file I/O (UTF-8 with BOM, LF→CRLF, notepad-friendly)
    │   └── ValueNormalizer.java     # Numeric input validation / normalization (empty → 0, non-numeric → null)
    └── ui/                     # Swing UI components
        ├── ResultPanel.java          # "Result output" area (read-only multi-line text)
        ├── ScrollablePanel.java      # Scrollable vertical form panel (width adapts, height follows content)
        ├── StringInputPanel.java     # "String input" area (a multi-line field per [[string]])
        └── VariableInputPanel.java   # "Variable value input" area (a field per {{variable}}, with error highlighting)
```

## License

[MIT License](LICENSE)
