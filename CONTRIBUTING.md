# Contributing to OpenFGA Desktop Client

Thank you for your interest in contributing! This document provides guidelines for contributing to this project.

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/openfga-desktop-client.git
   cd openfga-desktop-client
   ```
3. **Create a branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## Development Setup

### Prerequisites

- Java 21 or later
- Maven 3.8+
- OpenFGA CLI installed
- An IDE (IntelliJ IDEA, Eclipse, or VS Code with Java extensions)

### Building

```bash
mvn clean compile
```

### Running

```bash
mvn javafx:run
```

### Testing

```bash
mvn test
```

## Code Style

- Use 4 spaces for indentation (no tabs)
- Follow standard Java naming conventions
- Add JavaDoc comments for public methods
- Keep methods focused and concise

## Submitting Changes

1. **Commit your changes** with a clear message:
   ```bash
   git commit -m "Add feature: description of what you added"
   ```

2. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

3. **Create a Pull Request** on GitHub

### Pull Request Guidelines

- Provide a clear description of what the PR does
- Reference any related issues
- Include screenshots for UI changes
- Ensure the code compiles without errors
- Test your changes with a running OpenFGA server

## Reporting Issues

When reporting issues, please include:

- A clear and descriptive title
- Steps to reproduce the problem
- Expected behavior vs actual behavior
- Your environment (OS, Java version, OpenFGA version)
- Screenshots if applicable

## Feature Requests

Feature requests are welcome! Please:

- Check if the feature has already been requested
- Provide a clear description of the feature
- Explain the use case and benefits

## Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help others learn and grow

## Questions?

Feel free to open an issue for any questions about contributing.

Thank you for contributing!
