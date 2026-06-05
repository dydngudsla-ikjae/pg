---
name: commit
description: Stage and create a git commit using Conventional Commits format with a Korean message. Use when asked to commit, create a commit, or stage changes.
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git add:*), Bash(git commit:*)
---

# Git Commit Guide

## Core Principles
- Use Conventional Commits format
- Write commit messages in Korean
- Analyze changes and choose the appropriate type
- Always review changes before committing

## Check Current Changes

```
!`git status`
!`git diff HEAD`
```

## Commit Message Format

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

## Types
- **feat**: New feature
- **fix**: Bug fix
- **refactor**: Code improvement without behavior change
- **test**: Add or modify tests
- **docs**: Documentation changes
- **chore**: Build, config, or other misc changes
- **perf**: Performance improvement

## Rules
1. Subject within 50 chars, start with a verb (추가, 수정, 삭제, 개선...)
2. Body explains *why*, not *what*
3. If there's a related issue, add `Closes #123` in the footer
4. Analyze the changes, propose a commit message, and commit only after user confirmation

## Examples

```
feat(auth): 소셜 로그인 기능 추가

카카오, 구글 OAuth2 로그인을 지원합니다.
기존 이메일 로그인과 병행 사용 가능합니다.

Closes #42
```

```
fix(order): 주문 금액 계산 오류 수정

할인 쿠폰 적용 시 음수가 되는 엣지 케이스 처리
```