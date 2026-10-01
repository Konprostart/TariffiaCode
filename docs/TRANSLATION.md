# Translation Guide

## Contributing via Weblate

The easiest way to contribute translations is through our [Weblate instance](https://hosted.weblate.org/projects/and-code/).

1. Create a free Weblate account (or use GitHub/Google sign-in).
2. Navigate to the **AndCode** project.
3. Pick a language and start translating strings.
4. Weblate automatically opens a pull request with your changes.

## Contributing via Pull Request

1. Fork the repository.
2. Edit or create `app/src/main/res/values-<lang>/strings.xml`.
3. Ensure every `<string name="...">` key matches the source file (`values/strings.xml`).
4. Open a PR with your changes.

## File Structure

```
app/src/main/res/
├── values/strings.xml          # Source (English)
├── values-ar/strings.xml       # Arabic
├── values-es/strings.xml       # Spanish
├── values-fr/strings.xml       # French
├── values-ja/strings.xml       # Japanese
├── values-pt-rBR/strings.xml   # Portuguese (Brazil)
├── values-ru/strings.xml       # Russian
└── values-zh-rCN/strings.xml   # Chinese (Simplified)
```

## Currently Supported Languages

| Code      | Language              |
|-----------|-----------------------|
| en        | English (source)      |
| ar        | Arabic                |
| es        | Spanish               |
| fr        | French                |
| ja        | Japanese              |
| pt-rBR    | Portuguese (Brazil)   |
| ru        | Russian               |
| zh-rCN    | Chinese (Simplified)  |

## Adding a New Language

1. Create a directory: `app/src/main/res/values-<lang>/`
   - Use ISO 639-1 codes (e.g. `ko`, `de`, `it`).
   - For regional variants use `-r` (e.g. `values-pt-rPT`).
2. Copy `values/strings.xml` into the new directory.
3. Translate the string values (keep the `name` attributes unchanged).
4. Remove any strings you have not yet translated — Android falls back to English automatically.
5. Open a PR or submit via Weblate.

## String Naming Conventions

- Use `snake_case` for all string names.
- Prefix with the feature or screen area:
  - `nav_` — navigation items
  - `chat_` — chat screen
  - `workspace_` — workspace management
  - `provider_` — provider/auth flows
  - `notification_` — notifications
  - `voice_` — voice features
  - `settings_` / `section_` — settings screens
  - `drawer_` — navigation drawer
  - `mcp_` — MCP server management
  - `schedule_` — scheduled tasks
  - `onboarding_` — onboarding flow
  - `remote_` — remote connection setup
  - `github_` — GitHub integration
  - `install_step_` — local runtime installation
  - `runtime_status_` — runtime status labels
- Use `_title`, `_body`, `_hint`, `_label`, `_description`, `_button` suffixes for UI element types.
- Formatted strings use positional placeholders: `%1$s`, `%2$d`, etc.
