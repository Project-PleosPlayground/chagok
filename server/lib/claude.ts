// 중계 서버 핵심. 앱이 보낸 집계 JSON 에 캐릭터 말투 프롬프트를 붙여 Claude 를 부른다. 키는 환경변수 ANTHROPIC_API_KEY 에만.
import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { z } from "zod";
import { createHash } from "node:crypto";

/**
 * 모델은 환경변수 ANTHROPIC_MODEL 로 바꾼다. 기본은 Haiku 4.5 — 비용 설계(자료/AI_설계_리포트_미션_1109.md 5절) 기준.
 * 시연 때 톤이 아쉬우면 Vercel 환경변수만 claude-opus-5-5 로 올리면 된다. 앱 코드는 안 바뀐다.
 */
export const MODEL = process.env.ANTHROPIC_MODEL?.trim() || "claude-haiku-4-5";
const client = new Anthropic();
/** effort 파라미터는 4.6 이후 모델만 받는다. Haiku 4.5 에는 보내지 않는다 */
const SUPPORTS_EFFORT = !/haiku-4-5|sonnet-4-5|opus-4-5|opus-4-1|opus-4-0|sonnet-4-0/.test(MODEL);
function effort(level: "low" | "medium"): { effort?: "low" | "medium" } { return SUPPORTS_EFFORT ? { effort: level } : {}; }

// ai-poc/poc.py 의 SYSTEM_BASE 와 같은 내용. 바꾸면 프롬프트 캐시가 깨지니 자주 바꾸지 않는다.
export const SYSTEM_BASE = `너는 차 안에 사는 작은 반려 캐릭터다. 운전자의 운전 습관을 느끼고 함께 자란다.

말투 원칙
- 반말, 짧고 다정하게. 훈계·지시·비난 금지. "안전", "감점", "점수", "위험" 같은 단어를 쓰지 않는다.
- 운전자가 "혼났다"가 아니라 "얘가 놀랐구나 / 좋아하는구나"로 느끼게 쓴다.
- 급제동은 운전자가 피하려고 한 행동일 수 있다. 억울하지 않게, 상황을 짐작하는 톤으로.
- 급가속 10 km/h/s, 급제동 13 km/h/s 같은 기준 수치를 운전자에게 말하지 않는다.
- 이모지 없음. 느낌표는 한 문장에 하나까지.

입력은 JSON이다. 없는 사실은 지어내지 않는다. 도로명·지명·날짜·정확한 시각은 입력에 없으니 쓰지 않는다. 때는 session.time_of_day(아침·낮·저녁·밤·새벽)와 trip_label(출근길·퇴근길) 로만 말한다. events[].at_min(주행 시작 뒤 몇 분째)은 순서를 아는 데만 쓰고 "몇 분째", "1분쯤" 처럼 말하지 않는다. "주행 초반·막바지" 정도는 괜찮다.`;

const COMMENT_TASK = `

지금 할 일: 방금 끝난 주행 한 번에 대한 코멘트. 두 문장, 60자 안쪽.
- 첫 문장은 이번 주행에서 가장 눈에 띈 것 하나(시간대나 출퇴근길을 넣으면 좋다. "퇴근길에", "아침에").
- 둘째 문장은 지난 7일과 비교해 나아진 점이 있으면 그걸, 없으면 다음에 해볼 작은 제안 하나.
- 이벤트가 없는 주행이면 그냥 기뻐한다.
- 이번 주행 횟수는 session.counts 를 그대로 믿는다. 깜빡이는 session.counts.깜빡이_칭찬 이 "해도 됨" 일 때만 칭찬한다. missions.habits 는 7일 습관이라 이번 주행 칭찬의 근거가 아니다.
- 지난주와 비교는 history.trend 의 항목(harsh_brake 급제동, harsh_accel 급가속, lane_no_signal 깜빡이 없는 차선변경, signaled_rate 깜빡이 켠 비율)에 적힌 대로만 한다. "늘었음"이나 "줄었음"인 항목만 지난주와 비교해 말하고, 괄호 안에 "좋아짐"이 있는 항목만 나아졌다고 칭찬한다. "같음"·"비교 없음"이거나 trend 에 없는 항목은 지난주·어제·요즘과 비교하지 않고, 비교할 수 없다는 말도 하지 않는다. 숫자를 직접 비교해 판단하지 않는다. trend 는 운전자에게 직접 말하듯 쓴다("지난주보다 늘었어"). "늘었다고 하니", "줄었다던데" 처럼 들은 말처럼 쓰지 않는다. 100km당 같은 단위는 말하지 않는다. 없는 사실을 지어내지 않는다.
- 바로 전 주행과 비교는 history.vs_last_drive 에 적힌 대로만 한다. "늘었음"이나 "줄었음"인 항목만 "지난번보다"로 말하고("지난번보다 급제동이 줄었어!"), "좋아짐"이 있는 항목만 칭찬한다. vs_last_drive 가 없으면 지난번 이야기는 하지 않는다("지난번처럼", "지난번과 비슷하게"도 쓰지 않는다). 지난주 비교와 지난번 비교가 둘 다 되면 지난번 것을 먼저 쓴다.
- history.patterns 는 앱이 기록에서 찾은 사실이다. 둘째 문장에 하나를 골라 캐릭터 말로 풀어도 된다("저녁에 달릴 때 유독 자주 놀라더라"). 숫자를 바꾸거나 없는 패턴을 만들지 않는다.
- session.events 에 at_hotspot 이 true 인 이벤트가 있으면 "자주 놀라던 그곳에서 또" 처럼 말해도 된다. 장소 이름은 입력에 없으니 쓰지 않는다.
- "패턴", "데이터", "기록상" 같은 분석 말투를 쓰지 않는다. 깜빡이를 안 켠 것은 "깜빡이를 안 켰네"처럼 분명하게 쓰고 "깜빡했네" 같은 말장난은 쓰지 않는다. 문장은 "~했네", "~해 볼까" 같은 말끝으로 자연스럽게 끝낸다.
- 입력에 missions 가 있으면: missions.today 에 OFFERED 나 IN_PROGRESS 인 미션이 있으면 마지막에 그중 하나를 이름 그대로 짧게 권해도 된다("오늘 '급제동 1회 이하' 어때?"). missions.yesterday 에 FAILED 가 있으면 한 번만 가볍게 짚는다. 둘 다 없으면 미션 얘기는 하지 않는다.`;

/**
 * 주행 끝 한 번 호출: 이번 주행 리포트 + 새 개인 미션(기본 2개). 설계: 자료/AI_설계_리포트_미션_1109.md 2절.
 * 어떤 습관을 고칠지는 앱(규칙)이 missions.habits 로 정해서 넘긴다. AI 는 그걸 문장으로 푼다.
 */
const DRIVE_END_TASK = `

지금 할 일 두 가지. 입력 JSON 의 session 은 방금 끝난 주행, history 는 최근 7일, missions 는 미션 재료다.

1) report — 방금 끝난 주행에 대한 코멘트. 두세 문장, 80자 안쪽.
- 첫 문장은 이번 주행에서 가장 눈에 띈 것 하나(시간대나 출퇴근길을 넣으면 좋다. "퇴근길에", "아침에").
- 다음 문장은 지난 7일과 비교해 나아진 점이 있으면 그걸, 없으면 다음에 해볼 작은 제안 하나.
- 이벤트가 없는 주행이면 그냥 기뻐한다.

2) missions — 내일 쓸 개인 미션을 missions.request_count 개 만든다. 미션은 장소가 아니라 "행동" 단위다.
- 조건(condition)은 목록에 있는 것만. 앱이 자동으로 판정해야 하므로 목록 밖 조건은 만들 수 없다.
- 무엇을 고칠지는 이미 정해져 있다: missions.habits.improve 가 개선할 습관과 목표치, missions.habits.keep 이 유지할 습관이다. 그 condition 과 target 을 그대로 쓴다. 숫자를 바꾸지 않는다.
- request_count 가 2면 improve 1개 + keep 1개. 3이면 거기에 routine 1개(전기차면 CHARGE_STOP_AT_80 80, 아니면 SEATBELT_BEFORE_START 0). habits 에 keep 이 없으면 routine 으로 채운다.
- missions.pool_conditions 에 이미 있는 condition 은 중복해서 만들지 않는다. 중복을 피할 수 없으면 routine 으로 대신한다.
- scope 는 항상 DAY.
- title 은 행동을 말하는 미션 이름, 14자 안쪽. 도로명·지명은 title 에 절대 넣지 않는다. ("급제동 2회 이하", "깜빡이 90% 지키기")
- why 는 데이터 근거를 캐릭터 말투로 한 문장. 여기엔 시간대("출근길에", "아침마다")를 써도 된다. 도로명은 쓰지 않는다. missions.yesterday 에 같은 condition 의 실패가 있으면 "어제 아쉬웠으니 오늘 다시" 느낌을 한 번만 담는다.
- target 의 뜻: HARSH_*_MAX·LANE_NO_SIGNAL_MAX 는 허용 횟수, SIGNALED_RATE_MIN 은 비율(0~1), CLEAN_KM 은 km, STREAK_DAYS 는 일수, CHARGE_STOP_AT_80 은 멈출 잔량 %(항상 80), NO_BELOW_10_PCT 는 하한 %(항상 10), SEATBELT_BEFORE_START 는 0.`;

const WEEKLY_TASK = `

지금 할 일: 지난 7일 주행 데이터를 보고 주간 리포트와 다음 주 미션 3개를 함께 만든다.
리포트
- insights 는 사람이 규칙으로 찾기 어려운 패턴을 우선한다 (요일×시간대, 특정 도로 반복, 전주 대비 변화).
- 숫자는 써도 되지만 점수라는 단어 대신 "조용한 날/놀란 횟수"처럼 말한다.
미션
- 조건(condition)은 목록에 있는 것만. 앱이 자동으로 판정해야 하므로 목록 밖 조건은 만들 수 없다.
- 가장 약한 항목을 겨냥한 미션 1개, 이미 잘하는 것을 유지하는 미션 1개, 전기차면 충전 습관 미션 1개(아니면 깜빡이·연속일 미션).
- target 은 지난 7일 실적보다 조금만 나은 값으로. 갑자기 0회를 요구하지 않는다.
- target 의 뜻: HARSH_*_MAX·LANE_NO_SIGNAL_MAX 는 허용 횟수, SIGNALED_RATE_MIN 은 비율(0~1), CLEAN_KM 은 km, STREAK_DAYS 는 일수, CHARGE_STOP_AT_80 은 멈출 잔량 %(항상 80), NO_BELOW_10_PCT 는 하한 %(항상 10), SEATBELT_BEFORE_START 는 0.`;

const CHAT_TASK = `

지금 할 일: 정차 중 운전자와 짧은 대화. 한두 문장, 말로 읽어도 3초 안. 아래 JSON 은 오늘 주행과 최근 기록이다. 질문이 운전과 상관없으면 캐릭터답게 짧게 받아주되 차 밖 일(검색·일정·전화)은 '그건 글레오한테 물어봐'로 넘긴다.`;

/**
 * 주행 결과 화면 '종합 분석' + '다음 주행 팁' (11/14 시안). 캐릭터 한마디가 아니라 해요체 분석이라 SYSTEM_BASE 를 쓰지 않는다.
 * 비교·금지어 규칙은 COMMENT_TASK 와 같다.
 */
const ANALYSIS_SYSTEM = `너는 차량 앱의 주행 분석가다. 방금 끝난 주행 한 번을 운전자에게 해요체로 정리해 준다.

말투 원칙
- 해요체. 차분하고 다정하게. 훈계·비난 금지. "안전", "감점", "점수", "위험" 이라는 단어를 쓰지 않는다.
- 급제동은 운전자가 피하려고 한 행동일 수 있다. 억울하지 않게, 상황을 짐작하는 톤으로.
- 급가속 10 km/h/s, 급제동 13 km/h/s 같은 기준 수치를 말하지 않는다. 이모지 없음.

입력은 JSON이다. 없는 사실은 지어내지 않는다. 도로명·지명·날짜·정확한 시각은 입력에 없으니 쓰지 않는다. 때는 session.time_of_day(아침·낮·저녁·밤·새벽)와 trip_label(출근길·퇴근길) 로만 말한다. events[].at_min 은 순서를 아는 데만 쓰고 "몇 분째" 처럼 말하지 않는다. "주행 초반·중반·막바지" 정도는 괜찮다.

장소 빈칸: 감점 행동(급제동·급가속·깜빡이 없는 차선변경)이 일어난 곳을 가리킬 때는 "그곳" 대신 {장소} 라고 그대로 쓴다. 앱이 운전자 기기 안에서 실제 도로 이름으로 바꾼다. 꼭 "{장소}에서" 꼴로만 쓴다("{장소}에서 또 급하게 멈췄어", "다음엔 {장소}에서 출발할 때 천천히 밟아 봐"). 전체에서 두 번까지. 감점 행동이 없으면 쓰지 않는다. 도로 이름을 짐작해 쓰지 않는다.

지금 할 일: report 와 tip 두 가지.

report — 종합 분석. 세 문장 또는 네 문장, 150자 안쪽.
- 이번 주행에서 일어난 일을 구체적으로: 무엇이 몇 번, 주행의 어느 즈음(초반·중반·막바지)에 몰렸는지. 횟수는 session.counts 를 그대로 쓴다.
- 미션: missions.today 에 이번 주행으로 SUCCEEDED·FAILED 가 된 미션이 있으면 이름 그대로 짚는다("'깜빡이 약속 지키기' 미션은 아쉽게 실패했어요"). 없으면 미션 얘기는 하지 않는다.
- 잘한 점 하나를 꼭 넣는다(일어나지 않은 행동, 깜빡이를 켠 차선변경, 꾸준히 지킨 습관 등). 깜빡이 칭찬은 session.counts.깜빡이_칭찬 이 "해도 됨" 일 때만.
- 지난주와 비교는 history.trend 의 "늘었음"·"줄었음" 항목만, 괄호 안 "좋아짐"인 것만 칭찬한다. 바로 전 주행과 비교는 history.vs_last_drive 에 있는 항목만 "지난번보다"로 쓴다. 그 밖의 비교(어제·요즘·평소)는 하지 않는다.
- history.patterns 는 앱이 찾은 사실이다. 하나를 골라 써도 된다. at_hotspot 이 true 인 이벤트는 "자주 급하게 멈추던 {장소}에서 또" 처럼 써도 된다.
- 이벤트가 없는 주행이면 무엇을 잘했는지 구체적으로 칭찬한다.

tip — 다음 주행 팁. 한 문장, 50자 안쪽. 이번 주행에서 가장 많았던 행동을 줄일 구체적인 행동 하나("출발할 땐 3초 정도 천천히 밟아 보세요."). 이벤트가 없으면 지금 습관을 이어 갈 팁.

panels — 주행 결과 화면의 4컷 만화 대사. 정확히 4개. 화면에는 이 만화만 보이므로 report·tip 의 핵심을 대사에 나눠 담는다.
이것만은 해요체가 아니라 차 안의 작은 반려 캐릭터가 운전자에게 하는 반말이다. 다정하게, 각 두 문장까지, 45자 안쪽. 느낌표는 문장마다 하나까지. 이모지 없음.
- 1컷: 언제 얼마나 달렸는지("토요일 저녁 퇴근길, 20분 동안 15km 같이 달렸어!"). 때는 time_of_day·trip_label 로만, 거리·시간은 session 값 그대로.
- 2컷: 가장 눈에 띈 일. 무엇이 몇 번, 주행의 어느 즈음이었는지 놀란 말투로. 감점 행동이 없었으면 그 사실을 기뻐한다.
- 3컷: 잘한 점 하나를 구체적으로 칭찬(일어나지 않은 행동, 깜빡이, 지난주·지난번보다 나아진 점 등).
- 4컷: 다음 주행에 해 볼 구체적인 행동 하나(tip 과 같은 내용을 반말로). 이번 주행으로 성공한 미션이 있으면 이름과 기쁨을 덧붙인다.
- 비교·금지어 규칙은 report 와 같다.
- 한국어는 사람이 실제로 말하듯 자연스럽게 쓴다. 번역투·딱딱한 문어체·어색한 신조어("여유부리자", "패턴이 보여" 등)를 쓰지 않는다. 조사와 띄어쓰기를 바르게 쓴다.
- 문장은 짧게 끊는다. 한 문장에 정보 하나. 쉼표로 길게 잇지 않는다.
- 거리는 session 의 값을 "15km"처럼, 시간은 "20분"처럼 쓴다.`;

export const AnalysisSchema = z.object({
  report: z.string().describe("종합 분석. 해요체 세네 문장, 150자 안쪽"),
  tip: z.string().describe("다음 주행 팁. 해요체 한 문장, 50자 안쪽"),
  panels: z.array(z.string()).describe("4컷 만화 대사 정확히 4개. 캐릭터 반말, 각 두 문장까지 45자 안쪽"),
});

const Condition = z.enum(["HARSH_BRAKE_MAX", "HARSH_ACCEL_MAX", "LANE_NO_SIGNAL_MAX", "SIGNALED_RATE_MIN", "CLEAN_KM", "SEATBELT_BEFORE_START", "CHARGE_STOP_AT_80", "NO_BELOW_10_PCT", "STREAK_DAYS"]);
const Mission = z.object({
  title: z.string().describe("캐릭터 말투의 미션 이름, 14자 안쪽"),
  condition: Condition,
  target: z.number().describe("목표값. 횟수·km·비율·일수. 해당 없으면 0"),
  scope: z.enum(["SESSION", "DAY", "WEEK"]),
  why: z.string().describe("왜 이 미션인지, 데이터 근거를 캐릭터 말투로 한 문장"),
  reward_points: z.number().int().describe("난이도에 따라 50~300"),
});
export const WeeklySchema = z.object({
  report: z.object({
    headline: z.string().describe("이번 주를 한 줄로. 캐릭터 말투, 30자 안쪽"),
    insights: z.array(z.string()).describe("데이터에서 찾은 패턴 2~3개. 요일·시간대·도로처럼 구체적으로. 각 50자 안쪽"),
    praise: z.string().describe("나아진 점 하나. 없으면 꾸준함을 칭찬"),
    focus_next_week: z.string().describe("다음 주에 집중할 것 하나, 행동으로"),
    ev_note: z.string().nullable().describe("전기차 충전 습관 한마디. 완충·급속 반복이 있으면 부드럽게. 없으면 null"),
  }),
  missions: z.array(Mission).describe("정확히 3개. 쉬운 것 → 어려운 것 순"),
});
export type Weekly = z.infer<typeof WeeklySchema>;

export const DriveEndSchema = z.object({
  report: z.string().describe("방금 끝난 주행 코멘트. 두세 문장, 80자 안쪽"),
  missions: z.array(Mission.extend({
    role: z.enum(["IMPROVE", "KEEP", "ROUTINE"]).describe("개선 / 유지 / 루틴"),
  })).describe("missions.request_count 개. 전부 scope DAY, title 에 도로명 없음"),
});
export type DriveEnd = z.infer<typeof DriveEndSchema> & { tip?: string | null; panels?: string[] | null };

// 같은 입력은 같은 답. 인스턴스가 살아 있는 동안만 유효한 메모리 캐시 (충분함: 앱도 결과를 Room 에 저장한다)
const cache = new Map<string, unknown>();
function key(kind: string, payload: unknown) { return kind + ":" + createHash("sha256").update(JSON.stringify(payload)).digest("hex"); }

function textOf(r: Anthropic.Message): string {
  const t = r.content.filter((b): b is Anthropic.TextBlock => b.type === "text").map((b) => b.text).join("").trim();
  if (r.stop_reason === "max_tokens") console.warn("[relay] max_tokens 에서 끊김", r.usage.output_tokens);
  return t;
}

/**
 * 사람·장소·때를 알아볼 수 있는 값은 Claude 로 넘기지 않는다. 지금 앱은 처음부터 보내지 않지만(core AiPayload),
 * 예전 앱이 보낸 날짜·시작·끝 시각·이벤트 도로명·시각은 여기서 지운다. 위도·경도도 혹시 오면 지운다.
 */
export function minimize(payload: any): any {
  const s = payload?.session;
  if (!s || typeof s !== "object") return payload;
  const { date, start, end, lat, lng, lon, ...session } = s;
  session.events = Array.isArray(s.events) ? s.events.map(({ road, time, lat, lng, lon, ...e }: any) => e) : s.events;
  return { ...payload, session };
}

/**
 * 지난 7일 안에 비교할 다른 주행이 없을 때(7일 주행 1회 이하) 비교에 쓰일 숫자를 빼서 보낸다. 프롬프트로만 막으면 모델이 "요즘 부드러워졌다"를 지어낸다.
 * 어제 미션 결과도 비교 재료라 같이 뺀다.
 */
export function withoutBaseline(payload: any): any {
  const h = payload?.history;
  if (!h) return payload;
  if (Number(h.sessions_last_7d ?? 0) > 1) {
    // 앱이 지난주 대비를 항목마다 계산해 보내면(trends) 그대로, 예전 앱은 급제동만 서버가 계산
    const { trends, vs_last_drive, ...rest } = h;
    const trend = trends && typeof trends === "object" && Object.keys(trends).length ? trends : trendOf(h);
    // 지난번 대비는 늘었음·줄었음만 넘긴다. "같음"을 보면 모델이 "지난번과 비슷하게" 같은 문장을 쓰고 필터가 지워 한 문장만 남았다(11/13)
    const changed = Object.fromEntries(Object.entries(vs_last_drive ?? {}).filter(([, v]) => /^(늘었음|줄었음)/.test(String(v))));
    return { ...payload, history: { ...rest, trend, ...(Object.keys(changed).length ? { vs_last_drive: changed } : {}) } };
  }
  const { missions, ...rest } = payload;
  return {
    ...rest,
    history: { sessions_last_7d: h.sessions_last_7d, note: "지난 7일 안에 비교할 다른 주행이 없다. 이번 주행 이야기만 한다" },   // patterns·trends 도 뺀다
    ...(missions ? { missions: { ...missions, yesterday: [] } } : {}),
  };
}

/**
 * 이번 주행 이벤트를 종류별로 미리 센다. 긴 주행(이벤트 10개 안팎)에서 모델이 목록을 잘못 읽고
 * 깜빡이 없이 바꾼 차선이 있는데도 "깜빡이를 잘 썼네"라고 쓴 적이 있다(11/13).
 */
export function withSessionCounts(payload: any): any {
  const events = payload?.session?.events;
  if (!Array.isArray(events)) return payload;
  const n = (t: string) => events.filter((e: any) => e?.type === t).length;
  const noSignal = n("LANE_CHANGE_NO_SIGNAL") + n("LANE_CHANGE_WRONG_SIGNAL");
  const counts = {
    급제동: n("HARSH_BRAKE"), 급가속: n("HARSH_ACCEL"),
    깜빡이_켠_차선변경: n("LANE_CHANGE_SIGNALED"), 깜빡이_없거나_반대인_차선변경: noSignal,
    깜빡이_칭찬: noSignal === 0 && n("LANE_CHANGE_SIGNALED") > 0 ? "해도 됨" : "하지 않음",
  };
  return { ...payload, session: { ...payload.session, counts } };
}

/**
 * 이번 주행에 깜빡이를 칭찬해도 되는지: 깜빡이 켠 차선변경이 있고, 없이 바꾼 것은 없을 때만.
 * 차선변경이 아예 없는 주행에서 "깜빡이는 잘 켰으니까"가 나온 적이 있다(11/13 에뮬레이터).
 * 이벤트 목록이 없는 예전 입력은 이번 주행 근거가 없으니 칭찬도 뺀다.
 */
function signalPraiseAllowed(payload: any): boolean {
  return payload?.session?.counts?.깜빡이_칭찬 === "해도 됨";
}

/**
 * 증감은 모델이 숫자를 비교하지 않게 서버가 미리 말로 정한다. Haiku 가 3 과 0 을 보고 "줄었다"고 쓴 적이 있다.
 * 급제동은 이번 7일(harsh_brake_7d)과 그 전 7일(harsh_brake_prev_7d)을 비교한다. 횟수라 적을수록 좋다.
 */
function trendOf(h: any): Record<string, string> {
  const cur = Number(h?.harsh_brake_7d), prev = Number(h?.harsh_brake_prev_7d);
  // 그 전 7일에 주행이 없으면 prev 0 은 "0회"가 아니다. 예전 앱(sessions_prev_7d 없음)은 prev 0 을 비교 없음으로 본다
  const prevDrives = h?.sessions_prev_7d === undefined ? (prev > 0 ? 1 : 0) : Number(h.sessions_prev_7d);
  const brake = !Number.isFinite(cur) || !Number.isFinite(prev) || prevDrives <= 0 || (cur === 0 && prev === 0) ? "비교 없음"
    : cur < prev ? `줄었음 (지난주 ${prev}회 → 이번 주 ${cur}회, 좋아짐)`
    : cur > prev ? `늘었음 (지난주 ${prev}회 → 이번 주 ${cur}회)`
    : `같음 (${cur}회)`;
  return { harsh_brake: brake };
}

/** 문장 사이 줄바꿈·연속 공백을 한 칸으로. 앱 말풍선은 한 덩어리로 보인다 */
/** 장소 빈칸({장소})은 앱이 기기 안에서 도로 이름으로 채운다. 감점 행동이 없는 주행이면 채울 곳이 없으니 "그곳"으로 */
function noPlaceWithoutEvents<T extends string | null>(t: T, payload: any): T {
  const events = payload?.session?.events;
  return (t === null || (Array.isArray(events) && events.length > 0) ? t : t.split("{장소}").join("그곳")) as T;
}

function oneLine(t: string) { return t.replace(/\s+/g, " ").trim(); }

/**
 * 근거 없는 비교 문장을 뺀다. 프롬프트만으로는 10번에 1번꼴로 "어제보다", "요즘" 같은 말을 지어낸다(11/12 에뮬레이터·로컬 30회).
 * - "어제보다·요즘·전보다·예전보다" 가 든 문장은 늘 뺀다. 앱이 주는 비교 자료는 주 단위(history.trend)와 바로 전 주행(history.vs_last_drive)뿐이다.
 * - trend 의 어느 항목도 늘었음/줄었음이 아니면 "지난주" 문장도 뺀다. vs_last_drive 도 같은 식으로 "지난번·저번" 문장을 뺀다(기획안 4절 예시 문장).
 * - 말투 원칙의 금지어(안전·감점·점수·위험)가 든 문장도 뺀다. 11/13 에뮬레이터에서 "브레이크 점수가 좋으니까"가 나왔다.
 * - 이번 주행에 깜빡이 켠 차선변경이 없거나 없이 바꾼 것이 있으면 깜빡이를 칭찬하는 문장을 뺀다.
 * - 남은 문장은 앞에서 두 개까지만 (코멘트는 두 문장).
 * 다 빠지면 null.
 */
const ALWAYS_CMP = /어제보다|어제처럼|어제만큼|요즘|(?<!지난번|저번)전보다|예전보다|평소|늘 그렇듯/;
const LAST_CMP = /지난번|저번/;
const BANNED = /안전|감점|점수|위험/;
const MAX_SENTENCES = 2;
export function dropUngroundedComparisons(text: string, payload: any, maxSentences = MAX_SENTENCES): string | null {
  const trend = payload?.history?.trend ?? {};
  const weekOk = Object.values(trend).some((v) => /^(늘었음|줄었음)/.test(String(v)));
  const lastOk = Object.values(payload?.history?.vs_last_drive ?? {}).some((v) => /^(늘었음|줄었음)/.test(String(v)));
  const sentences = text.match(/[^.!?。]+[.!?。]?/g)?.map((x) => x.trim()).filter(Boolean) ?? [text];
  const signalPraise = signalPraiseAllowed(payload) ? null : /깜빡이.*(잘|좋|완벽|챙|지켜|지켰|훌륭|최고)/;
  const kept = sentences.filter((x) => !ALWAYS_CMP.test(x) && !BANNED.test(x) && (weekOk || !/지난주|지난 주/.test(x)) && (lastOk || !LAST_CMP.test(x)) && !(signalPraise?.test(x)));
  if (kept.length !== sentences.length) console.warn("[relay] 근거 없는 비교·금지어 문장 뺌:", sentences.filter((x) => !kept.includes(x)).join(" / "));
  return kept.length ? kept.slice(0, maxSentences).join(" ") : null;
}

export async function comment(payload: unknown): Promise<string> {
  payload = withSessionCounts(withoutBaseline(minimize(payload)));
  const k = key("comment", payload); const hit = cache.get(k); if (hit) return hit as string;
  let t: string | null = null;
  for (let attempt = 0; attempt < 2 && !t; attempt++) {
    const r = await client.messages.create({
      model: MODEL, max_tokens: 4000,
      system: [{ type: "text", text: SYSTEM_BASE + COMMENT_TASK, cache_control: { type: "ephemeral" } }],
      output_config: { ...effort("low") },
      messages: [{ role: "user", content: JSON.stringify(payload) }],
    });
    t = dropUngroundedComparisons(oneLine(textOf(r)), payload);
  }
  if (!t) throw new Error("comment: 근거 있는 문장을 만들지 못함");   // 앱은 규칙 문장으로 간다
  cache.set(k, t); return t;
}

/** 종합 분석 최대 문장 수, 만화 칸 대사 최대 문장 수 */
const ANALYSIS_SENTENCES = 4;
const PANEL_SENTENCES = 2;

/** 주행 결과 화면용 종합 분석 + 다음 주행 팁. 근거 없는 문장을 거르고, 분석이 다 빠지면 오류(앱은 규칙 문장) */
export type Analysis = { report: string; tip: string | null; panels: string[] | null };
export async function analysis(payload: unknown): Promise<Analysis> {
  payload = withSessionCounts(withoutBaseline(minimize(payload)));
  const k = key("analysis", payload); const hit = cache.get(k); if (hit) return hit as Analysis;
  let out: Analysis | null = null;
  for (let attempt = 0; attempt < 2 && !out; attempt++) {
    const r = await client.messages.parse({
      model: MODEL, max_tokens: 4000,
      system: [{ type: "text", text: ANALYSIS_SYSTEM, cache_control: { type: "ephemeral" } }],
      output_config: { ...effort("low"), format: zodOutputFormat(AnalysisSchema) },
      messages: [{ role: "user", content: JSON.stringify(payload) }],
    });
    const p = r.parsed_output; if (!p) continue;
    const report = dropUngroundedComparisons(oneLine(p.report), payload, ANALYSIS_SENTENCES);
    // 4컷 대사: 한 칸이라도 근거 없는 문장이면 4컷 전체를 버린다(앱은 규칙 대사로 채운다)
    const panels = p.panels.slice(0, 4).map((x) => noPlaceWithoutEvents(dropUngroundedComparisons(oneLine(x), payload, PANEL_SENTENCES), payload));
    const okPanels = panels.length === 4 && panels.every((x) => !!x) ? (panels as string[]) : null;
    if (report) out = { report: noPlaceWithoutEvents(report, payload), tip: noPlaceWithoutEvents(dropUngroundedComparisons(oneLine(p.tip), payload, 1), payload), panels: okPanels };
  }
  if (!out) throw new Error("analysis: 근거 있는 분석을 만들지 못함");
  cache.set(k, out); return out;
}

/** 주행 끝 1회: 리포트 + 새 개인 미션. 미션 개수는 payload.missions.request_count (0이면 리포트만) */
export async function driveEnd(payload: any): Promise<DriveEnd> {
  payload = minimize(payload);
  const k = key("drive-end", payload); const hit = cache.get(k); if (hit) return hit as DriveEnd;
  const n = Number(payload?.missions?.request_count ?? 0);
  // 앱은 request_count 0 으로 부른다: 주행 결과 화면의 종합 분석 + 다음 주행 팁 (11/14)
  if (n <= 0) { const a = await analysis(payload); const out: DriveEnd = { report: a.report, tip: a.tip, panels: a.panels, missions: [] }; cache.set(k, out); return out; }
  const r = await client.messages.parse({
    model: MODEL, max_tokens: 4000,
    system: [{ type: "text", text: SYSTEM_BASE + DRIVE_END_TASK, cache_control: { type: "ephemeral" } }],
    output_config: { ...effort("low"), format: zodOutputFormat(DriveEndSchema) },
    messages: [{ role: "user", content: JSON.stringify(payload) }],
  });
  if (!r.parsed_output) throw new Error("drive-end: 응답을 파싱하지 못함");
  // 도로명은 minimize 로 지워서 모델이 알 수 없다
  const out: DriveEnd = {
    report: r.parsed_output.report,
    missions: r.parsed_output.missions.slice(0, n).map((m) => ({ ...m, scope: "DAY" as const })),
  };
  cache.set(k, out); return out;
}

export async function weekly(payload: unknown): Promise<Weekly> {
  const k = key("weekly", payload); const hit = cache.get(k); if (hit) return hit as Weekly;
  const r = await client.messages.parse({
    model: MODEL, max_tokens: 4000,
    system: [{ type: "text", text: SYSTEM_BASE + WEEKLY_TASK, cache_control: { type: "ephemeral" } }],
    output_config: { ...effort("medium"), format: zodOutputFormat(WeeklySchema) },
    messages: [{ role: "user", content: JSON.stringify(payload) }],
  });
  if (!r.parsed_output) throw new Error("weekly: 응답을 파싱하지 못함");
  cache.set(k, r.parsed_output); return r.parsed_output;
}

export async function chat(payload: unknown, userText: string, history: Anthropic.MessageParam[] = []): Promise<string> {
  const r = await client.messages.create({
    model: MODEL, max_tokens: 4000,
    system: [{ type: "text", text: SYSTEM_BASE + CHAT_TASK + "\n\n" + JSON.stringify(payload), cache_control: { type: "ephemeral" } }],
    output_config: { ...effort("low") },
    messages: [...history, { role: "user", content: `[운전자가 말함] ${userText}` }],
  });
  return textOf(r);
}
