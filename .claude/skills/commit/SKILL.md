---
name: commit
description: Stage and create a git commit using Conventional Commits format with Korean message. Use when asked to commit, create a commit, or stage changes.
allowed-tools: Bash(git status:*), Bash(git diff:*), Bash(git add:*), Bash(git commit:*)
---

# Git 커밋 가이드

## 핵심 원칙
- Conventional Commits 형식 사용
- 커밋 메시지는 한글로 작성
- 변경사항을 분석하여 적절한 type 선택
- 커밋 전 반드시 변경사항 확인

## 현재 변경사항 파악

```
!`git status`
!`git diff HEAD`
```

## 커밋 메시지 형식

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

## Type 종류
- **feat**: 새로운 기능 추가
- **fix**: 버그 수정
- **refactor**: 기능 변경 없는 코드 개선
- **test**: 테스트 추가 또는 수정
- **docs**: 문서 수정
- **chore**: 빌드, 설정 등 기타 변경
- **perf**: 성능 개선

## 규칙
1. subject는 50자 이내, 동사로 시작 (추가, 수정, 삭제, 개선...)
2. body는 '무엇을'이 아닌 '왜' 변경했는지 설명
3. 관련 이슈가 있으면 footer에 `Closes #123` 추가
4. 변경사항을 분석하고 커밋 메시지를 제안한 뒤, 사용자 확인 후 커밋

## 예시

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
