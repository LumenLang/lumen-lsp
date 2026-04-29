# Lumen LSP

A language server for [Lumen](https://lumenlang.dev), providing editor support for `.luma` scripts.

The official VSCode extension is available at https://github.com/LumenLang/vscode-lumen.

## What is an LSP?

A Language Server Protocol (LSP) server runs in the background and gives your editor features like completions, hover info, error checking, and more. Instead of building these into every editor separately, an LSP works with any editor that supports the protocol. This project is the server. You pair it with a client extension for your editor.

> [!IMPORTANT]\
> 2.0.0 is a work in progress. Many features from the 1.x line have not been ported yet, and some that have are still rough.

> [!IMPORTANT]\
> Starting with 2.0.0, the LSP runs the real Lumen plugin against your script, headlessly without a Minecraft server, instead of approximating it. Diagnostics, types, and resolutions match what the compiler actually does, so editor analysis lines up with build behavior.
>
> The current tradeoff is reanalysis cost while typing on large scripts, since every change re-runs the plugin. The 1.3.0 architecture rebuild planned for upstream Lumen will introduce a typed IR with parallel resolution, after which the LSP will switch to it and reanalysis returns to lightweight territory.

> [!NOTE]\
> Native image builds are coming soon for some platforms. They cut memory usage roughly 10x and run a bit faster than running on the JVM. They are not available yet.

## Features

### Completions

Context-aware suggestions that change based on where you are in the script. Events, statements, expressions, variables, blocks, type bindings, and MiniColorize tags are all covered.

*Statements*
![Completions statements](screenshots/completions_statements.png)

*Events*
![Completions events](screenshots/completions_events.png)

**There are more completions as well, such as conditions, expressions, and blocks!**

### Hover

Hover over any statement, event, block, or variable to see its documentation. Descriptions, categories, available variables, and examples show up inline.

![Hover screenshot](screenshots/hover.png)

### Highlighting

Full semantic token support. Keywords, variables, types, events, properties.

### Diagnostics

Errors and warnings from the Lumen pipeline are shown as you type.

### Document Symbols

The outline view lists all blocks, commands, events, data classes, and variable declarations with proper nesting.

### Go to Definition

Jump to where a variable was declared.

### Document Colors

Hex colors inside MiniColorize strings show inline previews with a color picker.

![Colors screenshot](screenshots/colors.png)
![Color Picker screenshot](screenshots/color_picker.png)
