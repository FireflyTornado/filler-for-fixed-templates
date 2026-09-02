# Template Filler Tool

> **Language: [中文](README.md)**

A lightweight Java Swing desktop tool with a unified variable system for filling text or Word templates, previewing results, and exporting them safely.

## Features

- **Split-pane workspace** → templates and results stay on the left, while the base date and unified variable form stay on the right; both dividers are draggable and their positions are restored on the next launch
- **Unified variable input** → `{{variable}}` is the recommended syntax, and each variable can be Numeric, Short Text, or Multi-line Text; a blank numeric value is treated as 0, and `Tab` in a value field moves directly to the next variable value
- **Legacy syntax migration** → when `[[variable]]` is found, it can be backed up and converted to `{{variable}}` in one click
- **Compact multi-line editing** → multi-line values use a one-line preview in the main window and open in a modal “Expand…” editor
- **Session draft protection** → Numeric, Short Text, and Multi-line Text keep independent values for the current template editing session; only the active type and value persist across sessions
- **Adjustable UI text** → Follow System, Comfortable, Large, and Extra Large presets build on the Windows/JDK DPI-aware system fonts without applying DPI twice
- **Unified decimal places** → use `−` / `+` in the variable form to keep 0–10 decimal places for the current template; numeric replacements and expression results share the same setting, defaulting to two places
- **Calendar base date** → all built-in date variables use the calendar selection as their base; every launch starts with the current system date, and you can type `yyyy-MM-dd` or use the popup calendar to change it
- **Complete date derivation** → supports day-only, month-only, year-month, full-date, and year output for relative days, months, and years, plus the first/last day of the base month, with automatic month, year, and leap-year handling
- **Per-template configuration** → application state is stored in `config.json`, while each template keeps its own values, selected types, and decimal places in `Config/<full-template-name>.json`; on exit, you can confirm cleanup of unused variables in templates checked during the current session
- **Safe legacy migration** → existing `last_values.json` data is migrated once in read-only mode and the legacy file is never modified or deleted; new configuration writes use temporary files and atomic replacement where supported
- **Generated-result protection** → changing the template, variable value, variable type, or base date immediately invalidates the old result and disables copying or saving it
- **Error navigation and accessibility** → numeric and date errors update live with non-color indicators; use “Locate Error” or `F4` to cycle through them, plus `F1` for help, `Ctrl+Enter` to generate, and `Ctrl+S` to save the template
- **Modeless help window** → includes syntax guidance, every built-in date variable, and a live current-template variable inventory without blocking the main window
- **Multi-template management** → template files with any names are stored in the `Templates/` folder; you can switch via "Choose Template File…", "New Template", save changes back to the corresponding file with "Save Template", and the last-used template is remembered and restored on next startup
- **Edit templates on the fly** → edit and save directly in the UI, or click "Open Folder" to edit with an external editor
- **Manual template refresh** → reload the current template after editing it externally, with a warning before discarding unsaved in-app changes
- **Word template support** → replace variables in the body, tables, headers/footers, footnotes/endnotes, comments, and text boxes while preserving character and paragraph formatting

## Quick Start

**Requirements**: Windows + JDK 17 or higher.

**Run**:

1. Double-click `launcher.bat`
2. Or run `java -jar TemplateTool.jar` in a command line

**Edit templates**: templates are plain-text files inside the `Templates/` folder. Click "Choose Template File…" in the top bar to pick the template to use, edit in the area below, then click "Save Template" to write back to that file; "New Template" creates a new file; "Open Folder" opens the templates folder for direct template file management.

**Fill variables**: every variable name appears only once in the right-hand form. Ordinary variables can switch among all three types; expression variables remain Numeric. Use `−` / `+` at the top to adjust decimal places for the current template. Multi-line edits are committed only when the dialog is confirmed.

**Keyboard shortcuts**: `Tab` in a variable value field moves to the next variable value; `Ctrl+Enter` generates the result, `Ctrl+S` saves a text template, `F1` opens help, and `F4` cycles through input errors. Auxiliary dialogs close with `Esc`.

**Use a Word template**: use `{{variable}}` in Word. A `.docx` template is shown as a read-only preview. Export the generated document with “Save Result to File.”

## Variable Syntax

### Ordinary variables

Declare a variable with double braces:

```text
{{variable name}}
```

The complete content between the braces is the variable name; surrounding whitespace is ignored. A numeric-only name is therefore valid for an ordinary variable:

```text
Customer: {{customerName}}
First item: {{1}}
Notes: {{notes}}
```

Each ordinary variable can use one of these input types:

- **Numeric**: accepts decimal, fractional, and scientific notation; a blank value is treated as `0`; replacements are rounded to the template's decimal-place setting and padded with trailing zeros.
- **Short Text**: inserted as entered and intended for one-line text.
- **Multi-line Text**: preserves line breaks for notes or longer content.

Repeated occurrences of the same variable share one input field.

### Arithmetic expressions

Content beginning with `=` is evaluated as an arithmetic expression:

```text
{{=quantity*unitPrice}}
```

Expressions support `+`, `-`, `*`, `/`, `**` (power), and parentheses. Results use the same 0–10 decimal-place setting as ordinary numeric replacements (two by default) and are rounded half up. Referenced variables are locked to Numeric, and blank values are treated as `0`.

Variables can be referenced in two ways:

| Form | Use | Example |
| --- | --- | --- |
| Bare name | Starts with a letter and may continue with letters, digits, or underscores | `{{=quantity*unitPrice}}` |
| `[variable name]` | Numeric-only names, spaces, special characters, or an explicit reference to any ordinary name | `{{=[1]*[2]}}` |

Numeric variables and numeric literals are deliberately distinct:

```text
{{=1*2}}                  Numeric literals 1 × 2; result: 2.00
{{=[1]*[2]}}              Variable “1” × variable “2”
{{=quantity*2}}           Variable “quantity” × numeric literal 2
{{=[sales quantity]*[price-discount]}}
```

Numeric literals include forms such as `5`, `3.5`, `.5`, `2e3`, and `1.5E-2`. Built-in date variables cannot participate in arithmetic.

### Built-in date variables

All date variables are derived from the base date selected in the UI:

| Syntax | Meaning |
| --- | --- |
| `{{今日年}}` `{{今日年月}}` `{{今日年月日}}` | Base date |
| `{{昨日年}}` `{{昨日年月}}` `{{昨日年月日}}` | Day before the base date |
| `{{明日年}}` `{{明日年月}}` `{{明日年月日}}` | Day after the base date |
| `{{今日}}` `{{昨日}}` `{{明日}}` | Day only, such as `1日` or `31日` |
| `{{本月年}}` `{{本月年月}}` | Base month |
| `{{上月年}}` `{{上月年月}}` | Previous month |
| `{{下月年}}` `{{下月年月}}` | Next month |
| `{{本月}}` `{{上月}}` `{{下月}}` | Month only, such as `1月` or `12月` |
| `{{本月天数}}` | Number of days in the base month, output as `28`, `29`, `30`, or `31` |
| `{{上年}}` `{{本年}}` `{{下年}}` | Relative years |
| `{{本月月首}}` `{{本月月末}}` | First or last day of the base month, month-day only, such as `2月1日` or `2月29日` |

The old `[[variable]]` form is deprecated. The app offers a one-time conversion when such a template is loaded; unconverted placeholders remain unchanged and are not filled.

## Build

Double-click `build.bat`, or run in a command line:

```bat
build.bat
```

Building requires a JDK (with `javac` and `jar`). The build script runs the regression tests first, then generates `TemplateTool.jar` on success.

## Project Structure

```
├── build.bat                      # Compiles, tests, and packages TemplateTool.jar
├── launcher.bat                   # One-click Windows launcher
├── TemplateTool.jar               # Runnable application generated by build.bat
├── README.md / README.en.md       # Chinese and English documentation
├── LICENSE                        # MIT license
├── Templates/                     # Created automatically on first run; stores templates and migration backups
├── Config/                        # Created at runtime; stores per-template values, types, and decimal places
├── config.json                    # Generated at runtime; stores UI state and the last selected template
├── last_values.json               # Generated only by legacy versions; read by the current version for migration
├── src/main/java/com/firefly/
│   ├── Main.java                    # Entry point and system appearance setup
│   ├── TemplateToolApp.java         # Main window, template loading, generation, and migration UI
│   ├── TemplateConstants.java       # Placeholder, date-variable, and default-template constants
│   ├── core/
│   │   ├── TemplateParser.java      # Extracts variables, expression dependencies, and dates
│   │   ├── ExpressionEvaluator.java # Expression tokenizer and safe recursive-descent evaluator
│   │   ├── NumericFormatter.java   # Shared decimal formatting for numeric values and expressions
│   │   ├── TemplateRenderer.java    # Plain-text substitution and expression rendering
│   │   ├── DocxProcessor.java       # Word extraction, rendering, and cross-run migration
│   │   ├── LegacyTemplateMigrator.java # Legacy detection, backup, and atomic migration
│   │   ├── TemplateStore.java       # Templates directory and example management
│   │   ├── TextFileWriter.java      # UTF-8 BOM text I/O and newline handling
│   │   ├── ValueNormalizer.java     # Numeric validation and normalization
│   │   ├── VariableType.java        # Numeric, Short Text, and Multi-line Text types
│   │   ├── VariableInputState.java  # Values, session drafts, and expression locks
│   │   ├── AppConfig*.java          # Application configuration model and storage
│   │   ├── TemplateConfig*.java     # Per-template variable and decimal-place configuration
│   │   ├── AtomicConfigWriter.java  # Temporary writes and atomic config replacement
│   │   ├── JsonData.java / MiniJson.java # JSON support
│   │   └── LegacyConfigMigrator.java / LastValuesStore.java # Legacy config migration
│   └── ui/
│       ├── DatePickerPanel.java      # Base-date input and calendar
│       ├── VariableInputPanel.java   # Unified variable input and validation
│       ├── ScrollablePanel.java      # Adaptive scrolling container for the variable form
│       ├── MultilineEditorDialog.java # Multi-line text editor
│       ├── VariableTypeConversionDialog.java # Type-conversion confirmation
│       ├── ResultPanel.java          # Result preview
│       ├── TemplateHelpDialog.java   # Syntax, date, and variable help
│       ├── UiFontManager.java / FontScalePreset.java # Font scaling
│       └── ValidationIssue*.java / IssueSeverity.java # Issue tracking and navigation
└── test/java/com/firefly/
    └── TemplateFeatureTests.java    # Variable, numeric-format, Word, config, and migration tests
```

## License

[MIT License](LICENSE)
