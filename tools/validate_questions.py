#!/usr/bin/env python3
"""正式题库结构与覆盖门禁；任何一项失败都返回非零。"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANK = ROOT / "app/src/main/assets/questions.json"
EXPECTED_UNITS = {
    "math": {
        "一 混合运算", "二 测量（二）", "三 大数加与减（二）", "四 我们生活的空间（一）",
        "五 认识图形", "六 乘除法的应用（二）", "七 认识小数", "八 调查与记录",
        "综合实践 记录我们的校园", "数学好玩 搭配中的学问", "综合实践 探索年月日的秘密", "总复习",
    },
    "english": {
        "Unit 1 How do we feel?", "Unit 2 What's interesting about families?",
        "Unit 3 What do we look like?", "Unit 4 How do we have fun?",
        "Unit 5 What do we eat?", "Unit 6 What do we like about small animals?",
        "Unit 7 What do we know about weather?", "Unit 8 Why do we like birthdays?",
    },
}


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=Path, help="输出分层抽样供语义复核")
    args = parser.parse_args()
    errors: list[str] = []
    raw = BANK.read_bytes()
    root = json.loads(raw)
    questions = root.get("questions", [])
    if root.get("bankVersion") != "1.0.0-shenzhen-2025":
        fail(errors, "bankVersion 不是正式版本")
    if len(questions) != 1800:
        fail(errors, f"总题量应为1800，实际{len(questions)}")

    ids = [q.get("id") for q in questions]
    stems = [q.get("stem") for q in questions]
    for label, values in (("ID", ids), ("题干", stems)):
        duplicates = [v for v, count in Counter(values).items() if count > 1]
        if duplicates:
            fail(errors, f"{label}重复：{duplicates[:5]}")

    subjects = Counter(q.get("subject") for q in questions)
    for subject in ("chinese", "math", "english"):
        if subjects[subject] != 600:
            fail(errors, f"{subject} 应600题，实际{subjects[subject]}")

    units: dict[str, Counter] = defaultdict(Counter)
    difficulties: dict[str, Counter] = defaultdict(Counter)
    answers: dict[str, Counter] = defaultdict(Counter)
    for index, q in enumerate(questions):
        qid = q.get("id", f"#{index}")
        subject = q.get("subject")
        units[subject][q.get("unit")] += 1
        difficulties[subject][q.get("difficulty")] += 1
        answers[subject][q.get("answerId")] += 1
        if not re.fullmatch(r"(cn|ma|en)-\d{3}", q.get("id", "")):
            fail(errors, f"{qid}: ID格式错误")
        if q.get("difficulty") not in (1, 2, 3):
            fail(errors, f"{qid}: 难度不在1～3")
        if not q.get("knowledgePoint"):
            fail(errors, f"{qid}: 缺知识点")
        if not q.get("explanation") or len(q["explanation"].strip()) < 8:
            fail(errors, f"{qid}: 解析缺失或过短")
        options = q.get("options", [])
        if len(options) != 4:
            fail(errors, f"{qid}: 选项数不是4")
            continue
        option_ids = [o.get("id") for o in options]
        option_texts = [o.get("text", "").strip() for o in options]
        if set(option_ids) != set("ABCD") or len(set(option_ids)) != 4:
            fail(errors, f"{qid}: 选项ID错误")
        if len(set(option_texts)) != 4 or any(not text for text in option_texts):
            fail(errors, f"{qid}: 选项文本为空或重复")
        if q.get("answerId") not in option_ids:
            fail(errors, f"{qid}: 答案不在选项中")
        if any(token in q.get("stem", "") for token in ("示例题", "仅用于核心流程", "优先选择", "不必为整数")):
            fail(errors, f"{qid}: 存在开发残留或不确定表述")

    for subject, expected in EXPECTED_UNITS.items():
        if set(units[subject]) != expected:
            fail(errors, f"{subject} 单元覆盖不符：{set(units[subject]) ^ expected}")
        for unit in expected:
            if units[subject][unit] != 50 if subject == "math" else units[subject][unit] != 75:
                fail(errors, f"{subject}/{unit} 题量错误：{units[subject][unit]}")
    if len(units["chinese"]) != 33:
        fail(errors, f"语文应有26课及基础/阅读/交际分类，实际{len(units['chinese'])}个单元标签")
    for subject in difficulties:
        if difficulties[subject][3] < 30:
            fail(errors, f"{subject} 较难题不足30道")
        if min(answers[subject][letter] for letter in "ABCD") < 60:
            fail(errors, f"{subject} 答案位置分布过度集中：{answers[subject]}")

    report = {
        "bankVersion": root.get("bankVersion"), "total": len(questions),
        "subjects": subjects, "units": {s: dict(sorted(c.items())) for s, c in units.items()},
        "difficulties": {s: dict(sorted(c.items())) for s, c in difficulties.items()},
        "answerPositions": {s: dict(sorted(c.items())) for s, c in answers.items()},
        "sha256": hashlib.sha256(raw).hexdigest(), "errors": errors,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, default=dict))
    if args.samples:
        rng = random.Random(3102025)
        sampled = []
        for subject in ("chinese", "math", "english"):
            by_unit = defaultdict(list)
            for q in questions:
                if q["subject"] == subject:
                    by_unit[q["unit"]].append(q)
            for unit, items in sorted(by_unit.items()):
                sampled.extend(rng.sample(items, min(3, len(items))))
        args.samples.parent.mkdir(parents=True, exist_ok=True)
        with args.samples.open("w", encoding="utf-8") as output:
            for q in sampled:
                answer = next(o["text"] for o in q["options"] if o["id"] == q["answerId"])
                output.write(f"\n{q['id']} | {q['unit']} | {q['knowledgePoint']} | 难度{q['difficulty']}\n{q['stem']}\n")
                output.write(" / ".join(o["text"] for o in q["options"]) + f"\n答案：{answer}\n解析：{q['explanation']}\n")
        print(f"分层抽样：{len(sampled)}题，已写入 {args.samples}")
    if errors:
        print(f"题库校验失败：{len(errors)}项", file=sys.stderr)
        return 1
    print("题库结构与覆盖校验通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
