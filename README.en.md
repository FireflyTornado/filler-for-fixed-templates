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
- **Per-template configuration** → application state is stored in `config.json`, while each template keeps its own values, selected types, and decimal places under the mirrored path `Config/<template-relative-path>.json`; on exit, you can confirm cleanup of unused variables in templates checked during the current session
- **Safe legacy migration** → existing `last_values.json` data is migrated once in read-only mode and the legacy file is never modified or deleted; new configuration writes use temporary files and atomic replacement where supported
- **Generated-result protection** → changing the template, variable value, variable type, or base date immediately invalidates the old result and disables copying or saving it
- **General background file tasks** → template initialization, loading, importing, saving, refreshing, and migration, plus result generation and export, run in the background; a fixed status-bar area shows the current phase, progress, and an always-present Cancel button, while operation-based status text stays consistent across TXT and DOCX files
- **Safe template loading state** → switching or refreshing handles unsaved edits first, then locks the template workspace while progress is shown; success swaps the complete session at once, while failure or cancellation restores the previous template
- **Error navigation and accessibility** → numeric and date errors update live with non-color indicators; use “Locate Error” or `F4` to cycle through them, plus `F1` for help, `Ctrl+Enter` to generate, and `Ctrl+S` to save the template
- **Modeless help window** → includes syntax guidance, a calculation guide, every built-in date variable, and a live current-template variable inventory without blocking the main window
- **Multi-template management** → template files with any names can be stored at any depth below `Templates/`; you can switch via "Choose Template File…", "New Template", save changes back to the corresponding file with "Save Template", and the last-used template is remembered and restored on next startup
- **Synchronized rename** → click the template file name at the top of the window to rename it in place; its matching `Config` file is renamed at the same time
- **Edit templates on the fly** → edit and save directly in the UI, or click "Open Folder" to edit with an external editor
- **Manual template refresh** → reload the current template after editing it externally, with a warning before discarding unsaved in-app changes
- **Word template support** → replace variables in the body, tables, headers/footers, footnotes/endnotes, comments, and text boxes while preserving character and paragraph formatting

## Quick Start

**Requirements**: Windows + JDK 17 or higher.

**Run**:

1. Double-click `launcher.bat`
2. Or run `java -jar TemplateTool.jar` in a command line

**Edit templates**: templates are stored in `Templates/` or any nested folder and may be `.txt` or `.docx`. Text templates can be edited and saved directly in the app. Word templates are read-only in the app and must be edited in Word or another external editor, then refreshed or reopened. Clicking the template name at the top renames it in place and synchronizes its configuration. The rename field hides the extension and automatically preserves the existing `.txt` or `.docx`. "New Template" creates `.txt` files in the root `Templates` folder only.

### Organizing Templates in File Explorer

The app does not currently provide commands for moving templates, creating folders, bulk copying, or deleting files. Use **Open Folder**, or open the program's `Templates` directory directly in File Explorer. Exit the app before moving, bulk-renaming, or deleting files so it cannot save the current template or configuration back to an old path.

#### Naming and path rules

- Only `.txt` and `.docx` templates are supported; extension matching is case-insensitive. Other files are not included in template scans.
- Templates may be stored at any depth under `Templates`. Names must be unique within one folder, while separate folders may contain templates with the same file name.
- Do not begin a file or folder name with `.` because paths containing such a segment are treated as hidden and ignored.
- For Windows compatibility, names must not contain `\ / : * ? " < > |`, be `.` or `..`, or end in a space or period. Avoid reserved names such as `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, and `LPT1`–`LPT9`.
- A template is identified by its path relative to `Templates`. For example, `Contracts/Purchasing/Quote.txt` and `Contracts/Sales/Quote.txt` are independent templates.
- Clicking the template name in the app only renames it within its current folder. The field does not show or require the extension; the existing `.txt` or `.docx` is retained automatically.

#### Template-to-configuration mapping

Saved variable values, variable types, and decimal places use a mirrored path below `Config`. The configuration name is the complete template file name followed by `.json`:

| Template | Matching configuration |
| --- | --- |
| `Templates/Quote.txt` | `Config/Quote.txt.json` |
| `Templates/Contracts/Purchasing/Quote.txt` | `Config/Contracts/Purchasing/Quote.txt.json` |
| `Templates/Word/Notice.docx` | `Config/Word/Notice.docx.json` |

A configuration file is optional. If none exists, the app uses defaults and creates the required `Config` folders and JSON file when settings are saved. Do not edit JSON contents manually or move the application-level `config.json` into `Config`.

#### Common File Explorer operations

- **Move**: move the template below `Templates`. To retain its values and settings, move its configuration to the exactly matching relative folder below `Config` as well.
- **Rename externally**: keep the `.txt` or `.docx` extension, then rename the matching configuration to the template's new complete file name plus `.json`. Do this while the app is closed.
- **Copy**: copying only the template creates an independent template with default settings. Copy and rename its configuration too if the clone should inherit existing values and settings.
- **Delete**: delete the matching configuration as well to remove all saved values. If the configuration is retained, a future template created at that same relative path will reuse it.
- **Move a category folder**: move the corresponding category folders under both `Templates` and `Config` to preserve every mapping inside that category.

After organizing files, open the target with **Choose Template File…**. If the path remembered at startup no longer exists, select the template once at its new location; the app then remembers the new relative path.

**Fill variables**: every variable name appears only once in the right-hand form. Ordinary variables can switch among all three types; expression variables remain Numeric. Use `−` / `+` at the top to adjust decimal places for the current template. Multi-line edits are committed only when the dialog is confirmed.

**Keyboard shortcuts**: `Tab` in a variable value field moves to the next variable value; `Ctrl+Enter` generates the result, `Ctrl+S` saves a text template, `F1` opens help, and `F4` cycles through input errors. Auxiliary dialogs close with `Esc`.

**Use a Word template**: use `{{variable}}` in Word. A `.docx` template is shown as a read-only preview. Export the generated document with “Save Result to File.”

### File Tasks and Progress

- Initialization, loading, importing, saving, refreshing, legacy-syntax conversion, result generation, and result export run as background file tasks, so long operations do not block the entire main window.
- The status area uses operation-based labels such as “Load Template,” “Save Template,” and “Generate Result” instead of separate TXT and DOCX wording. These labels are defined centrally by `FileOperationText`.
- The progress area has a fixed position and size. Its Cancel button is always present and is disabled automatically when the current task cannot be cancelled.
- Loading, refreshing, or switching templates temporarily disables only the template-related workspace. A failed or cancelled load preserves the previous template and its input state.
- You may continue editing the template, date, and variables during generation and may keep using an existing result. Each generation uses a snapshot captured when it starts; if inputs change before it finishes, the status explicitly marks the completed result as inconsistent with the current inputs.
- If switching, creating, or refreshing a template would affect an active generation task, the app asks before cancelling it instead of interrupting it silently.

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

Expressions support `+`, `-`, `*`, `/`, `**` (power), postfix `%` (percentage), and parentheses. `5%` equals `0.05`, for example `{{=amount*5%}}`. Results use the same 0–10 decimal-place setting as ordinary numeric replacements (two by default) and are rounded half up. Referenced variables are locked to Numeric, and blank values are treated as `0`. More common examples are available on the Calculation Guide tab in the help window.

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

Main and test builds use two independent scripts. To compile and package the application, double-click `build.bat` or run:

```bat
build.bat
```

Run `build-test.bat` separately after a successful main build when you want to run tests. To run both from another batch file:

```bat
call build.bat
if errorlevel 1 exit /b 1
call build-test.bat
```

JDK 17 or later is required. Each script independently checks `JAVA_HOME`, JDK installations under `Program Files/Java`, then PATH. The main build uses `javac` and `jar`; the test build uses `javac` and `java`.

| Script | Responsibility | Output |
| --- | --- | --- |
| `build.bat` | Clean production output, recursively compile `src/main/java`, and package only production classes; does not compile or run tests | `out/`, `TemplateTool.jar` |
| `build-test.bat` | Clean test output, recursively compile `test/java` against the existing `TemplateTool.jar`, and run all regression suites via `com.firefly.AllTests` | `out-test/` |

Neither script calls the other or cleans the other's output directory. The test script never modifies the application JAR; if it is missing, it asks you to run `build.bat` first. Rebuild the application after changing main sources before testing the latest code. A successful main build confirms compilation and packaging only; the test script reports test results separately. Failed tests do not delete or replace the existing JAR.

Source lists are written separately to `work/build/main-sources.txt` and `work/build/test-sources.txt`. New source packages are discovered automatically. Register new test suites in `AllTests`.

## Project Structure

```
├── build.bat                      # Compiles and packages the main program only
├── build-test.bat                 # Independently compiles and runs tests against the existing JAR
├── launcher.bat                   # One-click Windows launcher
├── .gitignore                     # Excludes build output, runtime state, and editor files
├── TemplateTool.jar               # Runnable application generated by build.bat
├── out/                          # Production class files only; the release JAR's sole input
├── out-test/                     # Test class files only; excluded from the release JAR
├── work/build/                   # Generated recursive source lists and build intermediates
├── README.md / README.en.md       # Chinese and English documentation
├── LICENSE                        # MIT license
├── Templates/                     # Created automatically on first run; stores templates and migration backups
├── Config/                        # Created at runtime; stores per-template values, types, and decimal places
├── config.json                    # Generated at runtime; stores UI state and the last selected template
├── last_values.json               # Generated only by legacy versions; read by the current version for migration
├── src/main/java/com/firefly/
│   ├── Main.java                    # Entry point and system appearance setup
│   ├── TemplateToolApp.java         # Main window, file-task coordination, prompts, and result display
│   ├── TemplateConstants.java       # Placeholder, date-variable, and default-template constants
│   ├── application/                # Session and generation logic without Swing dependencies
│   │   ├── TemplateSession.java     # Current template, variable drafts, revisions, and update entry points
│   │   ├── VariableValidation.java  # Pre-generation normalization, issues, and numeric variable names
│   │   ├── GenerationRequest.java   # Independent input snapshot and stale-result detection
│   │   ├── GeneratedResult.java     # Generated content and temporary Word file information
│   │   └── GenerationService.java   # Text/Word generation, progress, and failure/cancellation cleanup
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
│   │   ├── VariableInputState.java  # Values, session drafts, expression locks, and independent copies
│   │   ├── AppConfig*.java          # Application configuration model and storage
│   │   ├── TemplateConfig*.java     # Per-template variable and decimal-place configuration
│   │   ├── AtomicConfigWriter.java  # Temporary writes and atomic config replacement
│   │   ├── OperationProgress.java   # Core file-operation progress callback
│   │   ├── FileOperationText.java   # Shared status text for loading, saving, generation, and other background tasks
│   │   ├── JsonData.java / MiniJson.java # JSON support
│   │   └── LegacyConfigMigrator.java / LastValuesStore.java # Legacy config migration
│   └── ui/
│       ├── DatePickerPanel.java      # Base-date input and calendar
│       ├── VariableInputPanel.java   # Variable display, conversion prompts, and session update wiring
│       ├── ScrollablePanel.java      # Adaptive scrolling container for the variable form
│       ├── MultilineEditorDialog.java # Multi-line text editor
│       ├── VariableTypeConversionDialog.java # Type-conversion confirmation
│       ├── ResultPanel.java          # Result preview
│       ├── FileTaskManager.java / FileTaskProgressPanel.java # Background tasks and fixed progress area
│       ├── TemplateBusyLayerUI.java  # Workspace blocking layer for exclusive template tasks
│       ├── TemplateHelpDialog.java   # Syntax, date, and variable help
│       ├── UiFontManager.java / FontScalePreset.java # Font scaling
│       └── ValidationIssue*.java / IssueSeverity.java # Issue tracking and navigation
└── test/java/com/firefly/
    ├── AllTests.java                # Single entry point for all regression suites
    ├── TemplateFeatureTests.java    # Existing variable, format, Word, config, and migration regression suite
    └── application/
        └── ApplicationTests.java    # Session, batch updates, generation snapshots, cleanup, and UI wiring
```

The project has no Maven, Gradle, or third-party-library dependency. Production code follows the `src/main/java` layout; regression tests use the project's custom `test/java` directory and are independently compiled and run by `build-test.bat`. `TemplateTool.jar`, `out/`, `out-test/`, `work/`, `Templates/`, `Config/`, and runtime configuration files are generated artifacts or local data and are excluded by `.gitignore`. If the project later adopts Maven or Gradle, the tests can be moved to the conventional `src/test/java`; no such move is needed for the current build.

### Extension boundaries

- `core` retains parsing, formatting, Word processing, and configuration storage. `application` combines these capabilities into sessions and generation workflows. `ui` handles controls, confirmation dialogs, and issue navigation. The application layer does not depend on Swing.
- Manual edits use `TemplateSession.setValue` and `activateType`. External data can use `applyValues` to assign a batch using the current variable types. It validates the entire batch before updating, rejects unknown variables, missing values, and blank/invalid numbers, preserves unmapped variables, and emits one change notification. The existing manual-input rule that a blank number means zero remains unchanged.
- The main window subscribes to session changes to invalidate old results, refresh batch values, and schedule configuration saves. Variable reads return independent copies, so extensions cannot mutate session state through a read result.
- Access each session serially on one thread; the desktop application uses the Swing event thread. Apply background data-reading results on that thread. Generation receives a `GenerationRequest` snapshot and never reads window controls. Template edits mark revisions before delayed parsing, preserving stale-result protection.
- Excel reading and mapping UI are not implemented yet. Future reader and mapping modules can connect through the session API. Existing template syntax, configuration formats, and user workflows remain unchanged.

## License

[MIT License](LICENSE)
