---
name: review
description: Review Java Spring Boot code before committing or opening a PR. Use when asked to review code, check quality, or before committing changes. Checks code quality, exception handling, naming, security, and test coverage.
allowed-tools: Read, Grep, Glob, Bash(git diff:*), Bash(git status:*)
---

# 코드 리뷰 가이드

## 리뷰 순서
1. `git diff HEAD`로 변경된 파일 파악
2. 변경 파일을 읽고 아래 체크리스트 기준으로 리뷰
3. 문제 발견 시 파일명 + 라인 위치와 함께 구체적인 개선안 제시
4. 심각도 구분: 🔴 반드시 수정 / 🟡 권장 / 🟢 제안

## 체크리스트

### 코드 품질
- 메서드가 단일 책임을 가지는가
- 불필요한 중복 코드가 없는가
- 매직 넘버/스트링 대신 상수 또는 enum을 사용하는가
- 불필요한 주석(코드로 충분히 표현 가능한 것)이 없는가

### 네이밍
- 클래스, 메서드, 변수명이 역할을 명확히 표현하는가
- Spring 레이어 컨벤션을 따르는가 (xxxController, xxxService, xxxRepository)
- 약어보다 명확한 전체 단어를 사용하는가

### 예외 처리
- 예외를 무시(빈 catch 블록)하고 있지 않은가
- 적절한 커스텀 예외를 사용하는가
- @ControllerAdvice / @ExceptionHandler로 일관된 에러 응답을 내려주는가
- RuntimeException을 그대로 던지지 않는가

### Spring & JPA
- 트랜잭션 범위가 적절한가 (@Transactional 위치)
- N+1 문제가 발생할 가능성이 있는가 (fetch join 또는 @BatchSize 필요 여부)
- 영속성 컨텍스트 범위 밖에서 지연 로딩을 호출하지 않는가
- DTO와 엔티티가 적절히 분리되어 있는가 (엔티티를 직접 반환하지 않는가)

### 보안
- SQL Injection 가능성 (네이티브 쿼리에서 문자열 직접 조합 여부)
- 민감한 정보(비밀번호, 토큰)를 로그에 출력하지 않는가
- 입력값 검증(@Valid, @Validated)이 적절히 적용되어 있는가

### 테스트
- 변경된 비즈니스 로직에 대응하는 테스트가 있는가
- 엣지 케이스(빈 값, 경계값, 예외 상황)가 테스트되는가

## 리뷰 결과 형식

```
## 코드 리뷰 결과

### 🔴 반드시 수정
- [파일명:라인] 문제 설명
  → 개선 방법

### 🟡 권장
- [파일명:라인] 문제 설명
  → 개선 방법

### 🟢 제안
- [파일명:라인] 문제 설명
  → 개선 방법

### 전반적인 평가
(간단한 총평)
```

🔴 항목이 없으면 커밋 또는 PR을 진행해도 좋다고 알려줄 것.
