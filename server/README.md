# server — AI 중계 (Vercel)

앱 → 이 서버 → Claude. API 키는 Vercel 환경변수 `ANTHROPIC_API_KEY` 에만 둔다. 앱에는 서버 주소와 `APP_TOKEN` 만 들어간다. 앱 쪽에서는 `android/local.properties` 의 `ai.relay.url`·`ai.relay.token` 으로 넣는다(gitignore, android/README ":app AI·원격 콘텐츠 연결").

| 주소 | 입력 | 출력 |
|---|---|---|
| **`POST /api/drive-end`** | `DriveEndPayload` (core `AiPayloadBuilder.driveEnd`, `AiJson.encode`) | `{ "report": "두세 문장", "missions": [] }` — 주행 끝 1회. 앱은 `missions.request_count` 0 으로 보내므로 리포트만 온다(개인 미션은 앱 안 규칙 `PersonalMissionPlanner` 가 고른다). 입력의 `missions.habits`·`yesterday`·`today` 를 보고 리포트가 미션을 한 마디 언급할 수 있다. request_count 를 1 이상으로 보내면 미션 제안도 만들어 주지만 지금 앱은 쓰지 않는다. core `AiJson.parseReport` 가 파싱 |
| `POST /api/comment` | `SessionCommentPayload` (core `AiPayload.kt`) | `{ "comment": "두 문장" }` |
| `POST /api/weekly` | `WeeklyPayload` | `{ "report": {...}, "missions": [...] }` — core `AiResponses` 가 파싱 |
| `POST /api/chat` | `{ "context": SessionCommentPayload, "text": "...", "history": [] }` | `{ "reply": "..." }` |
| `GET /api/health` | — | `{ ok, model, keyConfigured }` |

헤더 `x-app-token: <APP_TOKEN>` (환경변수가 비어 있으면 검사 안 함). 호출 횟수는 IP 하나당 1시간 30번, 서버 전체 하루 500번까지 받고 넘으면 429 (`lib/limit.ts`, 환경변수 `RATE_PER_IP_HOUR`·`RATE_GLOBAL_DAY` 로 바꿈). 인스턴스 메모리로 세므로 정확한 상한은 아니고, 돈의 최종 상한은 Anthropic 콘솔 월 지출 한도다. `/api/comment`·`/api/weekly`·`/api/chat` 은 환경변수 `POC_ENDPOINTS=1` 일 때만 열린다(기본 404). 응답 실패·타임아웃(drive-end 8초)이면 앱은 파이프라인의 규칙 문장(`SessionEndPipeline.fallbackComment`)을 그대로 쓴다.

모델은 환경변수 `ANTHROPIC_MODEL` (기본 `claude-haiku-4-5`, 비용 설계 `자료/AI_설계_리포트_미션_1109.md` 5절). 시연 때 톤을 올리고 싶으면 Vercel 에서 `claude-opus-5-5` 로만 바꾸면 된다. `/api/health` 가 지금 모델을 보여준다. 앱은 `/api/drive-end` 만 부른다. `/api/comment`·`/api/weekly`·`/api/chat` 은 PoC 용으로 남겨 둔다 (음성 없음 결정으로 chat 은 안 쓴다).

## 로컬
```
cd server && npm install
echo "ANTHROPIC_API_KEY=..." > .env      # gitignore
npm run typecheck
npm run smoke                            # ai-poc 샘플로 전부 호출
npm run smoke -- drive-end               # 주행 끝 호출만
```

## 배포 (서버 배포 담당 Vercel 계정)
```
npx vercel login
npx vercel link                          # 새 프로젝트, root = server
npx vercel env add ANTHROPIC_API_KEY production
npx vercel env add APP_TOKEN production  # 아무 긴 문자열. 앱에도 같은 값
npx vercel --prod
```
