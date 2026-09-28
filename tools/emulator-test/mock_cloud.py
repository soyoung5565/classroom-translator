# -*- coding: utf-8 -*-
"""
Mock 云端 (OpenAI 兼容) —— 课堂翻译 App 端到端验证用。
emulator -> adb reverse tcp:13099 -> PC:13099 -> 本服务。

场景文件 D:/tmp/mock_scenario.json（每次请求现读，可随时切换；切换时计数重置）:
{
  "summary": "garbage_then_good" | "good",
  "polish": "rewrite" | "passthrough",
  "summary_delay": 6.0,      # 秒，summary 响应前等待（留出"退屏"操作窗口）
  "polish_delay": 2.5,       # 秒，每批 polish 响应前等待
  "log": "D:/tmp/mock_cloud.log"
}
计数规则：同一场景字符串下线性计数；换场景重置。
"""
import json, re, threading, time, pathlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCENARIO_PATH = pathlib.Path('D:/tmp/mock_scenario.json')
LOG_PATH = pathlib.Path('D:/tmp/mock_cloud.log')
_lock = threading.Lock()
_counters = {}
_current_scenario = None

DEFAULT_SCENARIO = {
    "summary": "garbage_then_good",
    "polish": "rewrite",
    "summary_delay": 6.0,
    "polish_delay": 2.5,
}


def load_scenario():
    global _current_scenario, _counters
    try:
        s = json.loads(SCENARIO_PATH.read_text(encoding='utf-8'))
    except Exception:
        s = dict(DEFAULT_SCENARIO)
    key = json.dumps(s, sort_keys=True, ensure_ascii=False)
    if key != _current_scenario:
        _current_scenario = key
        _counters = {}
        log('SCENARIO changed -> ' + key)
    return s


def log(msg):
    line = time.strftime('[%H:%M:%S] ') + msg
    print(line, flush=True)
    with LOG_PATH.open('a', encoding='utf-8') as f:
        f.write(line + '\n')


def bump(kind):
    with _lock:
        _counters[kind] = _counters.get(kind, 0) + 1
        return _counters[kind]


GOOD_SUMMARY = {
    "overview": "【Mock验证】本课围绕阅读材料第一段的转折词展开:通过 however 引出的论点讲解,并练习了结对复述与结论前置的写法。",
    "points": [
        {"title": "转折词的位置", "detail": "however 出现的地方,真正的论点通常就在后面。", "at": 0},
        {"title": "结论前置", "detail": "这段的中心思想其实就是:结论前置。", "at": 2},
    ],
    "explainers": [
        {"term": "however", "kind": "vocab", "exp": "转折连词——提示后文才是重点,写作时可用来制造反转。", "at": 1},
    ],
    "homework": ["用 however 写一句转折句"],
    "questions": [
        {"q": "这段话的主旨是什么?", "a": "结论前置:中心思想放在段首。", "at": 2},
    ],
    "keywords": ["however", "transition word", "结论前置"],
}

GARBAGE = "抱歉,我无法以 JSON 格式输出课堂总结。"


SEED_PAIRS = {
    "Let's look at the first paragraph — notice the transition word the author uses here.":
        ("Let us look at the first paragraph — pay attention to the transition word the author uses here.",
         "咱们先来看第一段,留意作者在这里用的过渡词。"),
    "When 'however' shows up, the real argument usually follows.":
        ("When 'however' appears, the real argument usually follows.",
         "「However」出现时,后面往往才是真正的论点。"),
    "So the central idea of this paragraph is simply: conclusion first.":
        ("So the central idea of this paragraph is simple: conclusion first.",
         "所以这段的中心思想就是:结论前置。"),
    "Next, in pairs, retell the previous paragraph using this structure.":
        ("Next, work in pairs: retell the previous paragraph using this structure.",
         "接下来两人一组,用这个结构复述上一段内容。"),
}


def polish_rewrite(en):
    """演示素材的固定改写（少量词替换 → 便于观察对比高亮）。"""
    if en in SEED_PAIRS:
        return SEED_PAIRS[en]
    return en, "【润色】" + en


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        pass

    def _send(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith('/ping'):
            self._send({"pong": True})
        else:
            self._send({"error": "not found"}, 404)

    def do_POST(self):
        ln = int(self.headers.get('Content-Length', 0) or 0)
        raw = self.rfile.read(ln) if ln else b'{}'
        try:
            body = json.loads(raw.decode('utf-8'))
        except Exception:
            self._send({"error": {"message": "bad json"}}, 400)
            return
        sc = load_scenario()
        msgs = body.get('messages', [])
        system = ''
        user = ''
        for m in msgs:
            if m.get('role') == 'system':
                system = m.get('content') or ''
            elif m.get('role') == 'user':
                user = m.get('content') or ''
        if isinstance(user, list):  # 多模态内容
            user = json.dumps(user, ensure_ascii=False)

        kind = 'other'
        if '课堂学习助手' in system:
            kind = 'summary'
        elif 'professional interpreter' in system and 'numbered ASR segments' in system:
            kind = 'polish'

        n = bump(kind)
        model = body.get('model', '?')
        log(f'REQ kind={kind} seq={n} model={model} sys_len={len(system)} user_len={len(user)} '
            f'reasoning_effort={body.get("reasoning_effort")} max_tokens={body.get("max_tokens")}')

        if kind == 'summary':
            delay = float(sc.get('summary_delay', 0) or 0)
            if delay:
                time.sleep(delay)
            mode = sc.get('summary', 'good')
            if mode == 'garbage_then_good' and n == 1:
                content = GARBAGE
                log(f'RSP summary seq={n}: GARBAGE (non-JSON), 触发 App 重试')
            else:
                content = json.dumps(GOOD_SUMMARY, ensure_ascii=False)
                log(f'RSP summary seq={n}: GOOD json ({len(content)} chars)')
        elif kind == 'polish':
            delay = float(sc.get('polish_delay', 0) or 0)
            if delay:
                time.sleep(delay)
            mcount = re.search(r'exactly (\d+) numbered ASR segments', system)
            count = int(mcount.group(1)) if mcount else 0
            segs = re.findall(r'^\s*\d+\.\s(.*)$', user, re.M)
            pairs = []
            for i, s in enumerate(segs[:count]):
                if sc.get('polish') == 'passthrough':
                    pairs.append([s, "【直通】" + s])
                else:
                    en2, zh2 = polish_rewrite(s)
                    pairs.append([en2, zh2])
            content = json.dumps(pairs, ensure_ascii=False)
            log(f'RSP polish seq={n}: {len(pairs)}/{count} pairs')
        else:
            content = "OK"

        self._send({
            "id": "mock-1",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [{
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content, "reasoning_content": ""},
            }],
            "usage": {"prompt_tokens": 100, "completion_tokens": 100, "total_tokens": 200,
                       "completion_tokens_details": {"reasoning_tokens": 0}},
        })


if __name__ == '__main__':
    srv = ThreadingHTTPServer(('0.0.0.0', 13099), Handler)
    log('mock cloud listening on :13099')
    srv.serve_forever()
