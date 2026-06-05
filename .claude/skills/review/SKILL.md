---
name: review
description: Review Java Spring Boot code before committing or opening a PR. Use when asked to review code, check quality, or before committing changes. Checks code quality, exception handling, naming, security, and test coverage.
allowed-tools: Read, Grep, Glob, Bash(git diff:*), Bash(git status:*)
---

# Code Review Guide

## Review Order
1. Check changed files with `git diff HEAD`
2. Read the changed files and review using the checklist below
3. For issues found, provide specific improvements with file name + line location
4. Severity: 🔴 Must fix / 🟡 Recommended / 🟢 Suggestion

## Checklist

### Code Quality
- Does each method have a single responsibility?
- Is there unnecessary duplicate code?
- Are magic numbers/strings replaced with constants or enums?
- Are there unnecessary comments (things adequately expressed by code)?

### Naming
- Do class, method, and variable names clearly express their role?
- Do they follow Spring layer conventions (xxxController, xxxService, xxxRepository)?
- Are full words used instead of abbreviations?

### Exception Handling
- Are exceptions being silently ignored (empty catch blocks)?
- Are appropriate custom exceptions being used?
- Is a consistent error response returned via @ControllerAdvice / @ExceptionHandler?
- Is RuntimeException being thrown directly?

### Spring & JPA
- Is the transaction scope appropriate (@Transactional placement)?
- Is there potential for N+1 problems (need for fetch join or @BatchSize)?
- Is lazy loading being called outside the persistence context?
- Are DTOs and entities properly separated (entities not returned directly)?

### Security
- SQL Injection risk (direct string concatenation in native queries)?
- Sensitive info (passwords, tokens) not printed in logs?
- Input validation (@Valid, @Validated) properly applied?

### Tests
- Are there tests for changed business logic?
- Are edge cases (empty values, boundary values, exceptions) tested?

## Review Output Format

```
## Code Review Results

### 🔴 Must Fix
- [filename:line] Problem description
  → How to fix

### 🟡 Recommended
- [filename:line] Problem description
  → How to fix

### 🟢 Suggestion
- [filename:line] Problem description
  → How to fix

### Overall Assessment
(Brief summary)
```

If there are no 🔴 items, indicate that it's okay to proceed with a commit or PR.