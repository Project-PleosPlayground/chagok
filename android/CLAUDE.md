# 작업 규칙 (Claude)

- 보안 세팅 완료(2026-11-06): `.gitignore`에 비밀 파일 패턴, `.claude/settings.local.json`에 읽기·수정 금지 목록. 다시 묻지 않는다.
- API 키는 `secrets.properties`(gitignore)에 둔다. 코드·커밋에 넣지 않는다.
- 커밋·푸시는 사용자가 요청할 때만 한다.
