# AI Commits - JetBrains Plugin

AI Commits is a JetBrains IDE plugin that generates your commit messages by using git diff and Large Language Models (LLMs).

## Features

✨ **Generate commit message from git diff using LLM** - Automatically analyze your code changes and generate meaningful commit messages

📝 **Compute diff only from selected files and lines** - Generate commit messages based only on the changes you've selected in the commit dialog

⚡ **Real-time streaming support** - Watch your commit message being generated in real-time with streaming responses

😊 **Optional emoji integration** - Add expressive emojis to make commit messages more human-friendly and visually appealing

🎯 **Create your own prompt for commit message generation** - Customize the AI prompt to match your commit message style and conventions

🔧 **Use predefined variables and hints** - Leverage variables like `{{diff}}`, `{{files}}`, `{{branch}}`, and `{{emoji}}` in your custom prompts

🔑 **Support custom API host and key** - Configure your own OpenAI-compatible API endpoint and API key

🧠 **Smart response cleaning** - Automatically removes unwanted content like code blocks, think tags, and formatting artifacts

## Installation

### Option 1: Download from Releases (Recommended)
1. Go to the [Releases page](../../releases) and download the latest `ai-commits-x.x.x.zip`
2. In your JetBrains IDE: `Settings > Plugins > ⚙️ > Install Plugin from Disk`
3. Select the downloaded ZIP file
4. Restart your IDE

### Option 2: Build from Source
1. Clone this repository and run `./gradlew buildPlugin`
2. Install the plugin from `build/distributions/ai-commits-x.x.x.zip`

### Option 3: JetBrains Marketplace (Coming Soon)
The plugin will be available on JetBrains Marketplace once published.

## Configuration

1. Go to `Settings > Tools > AI Commits`
2. Configure your API settings:
   - **API Host**: Your OpenAI-compatible API endpoint (default: https://api.openai.com)
   - **API Key**: Your API key for the LLM service
   - **Model**: Model name (e.g., gpt-4o, gpt-3.5-turbo)
   - **Max Tokens**: Maximum tokens for the response (default: 1024)
   - **Temperature**: Randomness of the output (0.0 = deterministic, 2.0 = very random)
   - **Enable Streaming**: Show real-time generation of commit messages (recommended)
   - **Enable Emoji**: Add expressive emojis to commit messages

3. Optionally customize the prompt template:
   - Enable "Use custom prompt"
   - Edit the prompt template using available variables

## Usage

1. Open the commit dialog in your JetBrains IDE
2. Select the files and changes you want to include
3. Click the "Generate AI Commit Message" button in the commit dialog
4. Watch the AI analyze your changes and generate a commit message in real-time (if streaming is enabled)
5. The generated message will include emojis if enabled in settings
6. Review and edit the generated message if needed
7. Commit your changes

**Pro Tip**: The plugin automatically cleans up unwanted content from AI responses, including code blocks and think tags, ensuring clean commit messages.

## Prompt Variables

When creating custom prompts, you can use these variables:

- `{{diff}}` - The git diff of the selected changes
- `{{files}}` - List of changed files
- `{{branch}}` - Current git branch name
- `{{emoji}}` - Emoji instructions (automatic based on settings)

## Default Prompt

The plugin comes with a sensible default prompt that follows conventional commit format:

```
You are an expert programmer, and you are trying to write a commit message for this change.
Generate a concise git commit message written in present tense for the following code changes:

Variables available:
- {{diff}}: The git diff of the changes
- {{files}}: List of changed files
- {{branch}}: Current git branch name
- {{emoji}}: Whether to include emojis in the commit message

Rules:
1. Use conventional commit format: type(scope): description
2. Keep the message under 72 characters for the title
3. Use present tense ("add feature" not "added feature")
4. Focus on the why and what, not how
5. If there are multiple changes, focus on the most significant one
{{emoji}}

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

## 🚀 Automated Release Process

This project uses GitHub Actions for automated building, testing, and releasing:

### 📦 Auto Release
- **Push to `master`**: Automatically increments patch version and creates a new release
- **Manual Release**: Go to **Actions** → **Build and Release** → **Run workflow** to choose version bump type (patch/minor/major)

### 🧪 Continuous Testing  
- **Pull Requests**: Automatically run tests and build validation
- **Feature Branches**: Push to any branch triggers testing

### 📋 Version Management
The project follows [Semantic Versioning](https://semver.org/):
- **PATCH** (x.y.Z): Bug fixes and small improvements
- **MINOR** (x.Y.z): New features (backward compatible)  
- **MAJOR** (X.y.z): Breaking changes

### 📥 Downloading Releases
Latest releases are available on the [Releases page](../../releases) with:
- Pre-built plugin ZIP files
- Automatic changelog generation
- Installation instructions

For detailed information about the CI/CD process, see [GitHub Actions Documentation](.github/workflows/README.md).

## Requirements

- JetBrains IDE 2023.2 or later
- Git integration enabled
- OpenAI-compatible API access

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request. 