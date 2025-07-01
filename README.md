# AI Commits - JetBrains Plugin

AI Commits is a JetBrains IDE plugin that generates your commit messages by using git diff and Large Language Models (LLMs).

## Features

✨ **Generate commit message from git diff using LLM** - Automatically analyze your code changes and generate meaningful commit messages

📝 **Compute diff only from selected files and lines** - Generate commit messages based only on the changes you've selected in the commit dialog

🎯 **Create your own prompt for commit message generation** - Customize the AI prompt to match your commit message style and conventions

🔧 **Use predefined variables and hints** - Leverage variables like `{{diff}}`, `{{files}}`, and `{{branch}}` in your custom prompts

🔑 **Support custom API host and key** - Configure your own OpenAI-compatible API endpoint and API key

## Installation

1. Download the plugin from JetBrains Marketplace or build from source
2. Install in your JetBrains IDE via `Settings > Plugins > Install from Disk`
3. Restart your IDE

## Configuration

1. Go to `Settings > Tools > AI Commits`
2. Configure your API settings:
   - **API Host**: Your OpenAI-compatible API endpoint (default: https://api.openai.com)
   - **API Key**: Your API key for the LLM service
   - **Model**: Model name (e.g., gpt-3.5-turbo, gpt-4)
   - **Max Tokens**: Maximum tokens for the response
   - **Temperature**: Randomness of the output (0.0 = deterministic, 2.0 = very random)

3. Optionally customize the prompt template:
   - Enable "Use custom prompt"
   - Edit the prompt template using available variables

## Usage

1. Open the commit dialog in your JetBrains IDE
2. Select the files and changes you want to include
3. Click the "Generate AI Commit Message" button in the commit dialog
4. The AI will analyze your changes and generate a commit message
5. Review and edit the generated message if needed
6. Commit your changes

## Prompt Variables

When creating custom prompts, you can use these variables:

- `{{diff}}` - The git diff of the selected changes
- `{{files}}` - List of changed files
- `{{branch}}` - Current git branch name

## Default Prompt

The plugin comes with a sensible default prompt that follows conventional commit format:

```
You are an expert programmer, and you are trying to write a commit message for this change.
Generate a concise git commit message written in present tense for the following code changes:

Variables available:
- {{diff}}: The git diff of the changes
- {{files}}: List of changed files
- {{branch}}: Current git branch name

Rules:
1. Use conventional commit format: type(scope): description
2. Keep the message under 72 characters for the title
3. Use present tense ("add feature" not "added feature")
4. Focus on the why and what, not how
5. If there are multiple changes, focus on the most significant one

The diff:
{{diff}}

Files changed:
{{files}}

Current branch: {{branch}}

Generate a commit message:
```

## Building from Source

1. Clone this repository
2. Run `./gradlew buildPlugin`
3. The built plugin will be in `build/distributions/`

## Requirements

- JetBrains IDE 2023.2 or later
- Git integration enabled
- OpenAI-compatible API access

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. 