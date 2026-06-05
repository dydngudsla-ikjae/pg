---
name: tdd-list
description: Write a list of test scenarios for TDD. Use when starting TDD for a new feature or adding scenarios to an existing list.
---

# TDD List — Write Test Scenarios

Prepare a list of test scenarios to cover a feature.

## Syntax

```
/tdd-list <policy-file>
```

**Parameters:**
- `policy-file` (required): Path to the policy/spec markdown file under `docs/service/` (e.g., `docs/service/001.merchant.md`). The output scenario file will be created at the mirrored path under `docs/test/` (e.g., `docs/test/001.merchant.md`).

## Workflow

### 1. Understand the Feature

- Read the policy file at the given path
- Explore the existing codebase for relevant context
- Identify the system under test (sut)
- Identify the behaviors to verify
- Resolve ambiguities by asking the user
- Derive the output path by replacing the leading `service/` with `test/` (e.g., `service/001.merchant.md` → `test/001.merchant.md`)
- If the output file already exists and contains a test scenario list:
  - If some scenarios are marked done (`- [x]`), present the list to the user showing completion status and ask how to proceed: **resume** (continue from the first incomplete scenario), **reset** (uncheck all and start over), or **augment** (add missing scenarios to the existing list)
  - If no scenarios are marked done, ask the user whether to **skip** (use the existing list as-is) or **augment** (review and add missing scenarios)

### 2. Write Scenarios

Write test scenarios following these rules:

- Write one scenario per line as a checklist item (`- [ ]`)
- Write in Korean, present tense
- Use a specific Korean subject for each scenario group (e.g., "가맹점 등록 API가", "로그인 API가") instead of the generic 'sut'
- Write as concisely as possible while preserving meaning
- Omit filler words and obvious context
- Prefer short verb phrases (e.g., "가맹점 등록 API가 빈 이름을 거부한다" over "가맹점 등록 API는 이름이 비어 있는 경우 거부해야 한다")
- Order from most important to least important
- Start with the simplest, most fundamental behavior

### 3. Persist the List

After user approval, write the scenario list to the output file (`test/` mirrored path):

- If the `test/` directory (or any intermediate directory) does not exist, create it automatically
- If the output file does not exist, create it
- If the output file already exists, update it with the new or revised scenario list

## Important

- Do NOT write tests or code. Only produce the scenario list.
- Do NOT proceed to implementation. Stop after persisting the list.
- All output presented to the user MUST be written in Korean.
- Write the scenario list to the output file immediately without asking for approval.