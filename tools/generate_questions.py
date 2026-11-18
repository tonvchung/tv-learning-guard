#!/usr/bin/env python3
"""生成深圳三年级上册正式题库。题目为原创，固定种子保证可复现。"""
from __future__ import annotations

import hashlib
import json
import random
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/questions.json"
QUESTIONS: list[dict] = []
RNG = random.Random(20261108)


def add(subject: str, unit: str, kp: str, difficulty: int, stem: str,
        correct: str, wrong: list[str], explanation: str) -> None:
    assert len(wrong) == 3 and len({correct, *wrong}) == 4, (stem, correct, wrong)
    prefix = {"chinese": "cn", "math": "ma", "english": "en"}[subject]
    serial = sum(q["subject"] == subject for q in QUESTIONS) + 1
    if subject == "math":
        scenario = ["校园测量", "生活购物", "班级活动", "旅行观察", "劳动实践", "图书整理", "运动记录", "自然探索", "科技实践", "期末复习"][((serial-1)//60)%10]
        stem = f"【{scenario}】{stem}"
    texts = [correct, *wrong]
    RNG.shuffle(texts)
    options = [{"id": letter, "text": text} for letter, text in zip("ABCD", texts)]
    answer = next(o["id"] for o in options if o["text"] == correct)
    QUESTIONS.append({
        "id": f"{prefix}-{serial:03d}", "subject": subject, "unit": unit,
        "knowledgePoint": kp, "difficulty": difficulty, "stem": stem,
        "options": options, "answerId": answer, "explanation": explanation,
    })


def numeric(correct: int, candidates: list[int], unit: str = "") -> list[str]:
    values = []
    for value in candidates:
        if value != correct and value >= 0 and value not in values:
            values.append(value)
    delta = 1
    while len(values) < 3:
        for value in (correct + delta, correct - delta, correct + delta * 2):
            if value >= 0 and value != correct and value not in values:
                values.append(value)
            if len(values) == 3:
                break
        delta += 1
    return [f"{v}{unit}" for v in values[:3]]


def math_question(unit: str, kp: str, stem: str, answer: int,
                  candidates: list[int], explanation: str, suffix: str = "", difficulty: int = 2) -> None:
    add("math", unit, kp, difficulty, stem, f"{answer}{suffix}",
        numeric(answer, candidates, suffix), explanation)


def generate_math() -> None:
    # 每个主题 5 类题型、每类 10 个不同情境或数据，共 12×50=600。
    for i in range(10):
        a, b, c = 12 + i * 3, 3 + i % 5, 2 + i % 4
        u = "一 混合运算"
        math_question(u, "乘加混合", f"计算 {a}＋{b}×{c}，结果是多少？", a + b*c,
                      [a+b+c, (a+b)*c, a+b*c-c], f"先算乘法 {b}×{c}={b*c}，再算 {a}＋{b*c}={a+b*c}。")
        math_question(u, "含小括号", f"计算（{a}＋{b}）×{c}，结果是多少？", (a+b)*c,
                      [a+b*c, a+b+c, (a+b)*c-c], f"先算小括号里的 {a}＋{b}={a+b}，再乘 {c}。")
        math_question(u, "乘减混合", f"计算 {a+b*c}－{b}×{c}，结果是多少？", a,
                      [a+b*c, a-b, a+b], f"先算乘法 {b}×{c}={b*c}，再从 {a+b*c} 中减去，得 {a}。")
        math_question(u, "两步应用题", f"一本练习册 {b} 元，一支钢笔 {c} 元。买 {i+2} 本练习册和一支钢笔，共需多少元？",
                      b*(i+2)+c, [b*(i+2)-c, b+c*(i+2), (b+c)*(i+2)],
                      f"练习册需要 {b}×{i+2}={b*(i+2)} 元，再加钢笔 {c} 元，共 {b*(i+2)+c} 元。", " 元", 3)
        x, y = a+b*c, (a+b)*c
        add("math", u, "比较大小", 3, f"不直接算出所有结果，比较 {a}＋{b}×{c} ○（{a}＋{b}）×{c}，○中应填什么？",
            "＜" if x < y else "＞", ["＞" if x < y else "＜", "＝", "无法比较"],
            f"左边是 {a} 加 {b} 个 {c}，右边是 {a} 与 {b} 的和再乘 {c}，实际结果分别为 {x} 和 {y}。")

        u = "二 测量（二）"
        mm = (i+3)*10
        math_question(u, "厘米与毫米", f"{mm//10} 厘米等于多少毫米？", mm, [mm//10, mm*10, mm+10],
                      f"1 厘米=10 毫米，所以 {mm//10} 厘米={mm} 毫米。", " 毫米")
        metres = i+2
        math_question(u, "米与千米", f"学校跑道一圈 {metres*100} 米，跑 10 圈是多少千米？", metres,
                      [metres*10, metres*100, metres+10], f"一圈 {metres*100} 米，10 圈是 {metres*1000} 米，也就是 {metres} 千米。", " 千米")
        cm, extra = 8+i, 3+i%6
        math_question(u, "长度计算", f"一根彩带长 {cm} 厘米 {extra} 毫米，合起来是多少毫米？", cm*10+extra,
                      [cm+extra, cm*10, cm*100+extra], f"{cm} 厘米={cm*10} 毫米，再加 {extra} 毫米，共 {cm*10+extra} 毫米。", " 毫米")
        items = [("铅笔盒的长", "厘米", ["千米", "千克", "分钟"]), ("深圳到广州的距离", "千米", ["毫米", "厘米", "克"]),
                 ("一枚硬币的厚度", "毫米", ["千米", "米", "分米"]), ("教室的长", "米", ["毫米", "厘米", "千米"])]
        thing, ans, wrong = items[i%4]
        add("math", u, "选择长度单位", 2, f"测量{thing}，用哪个单位最合适？", ans, wrong,
            f"根据{thing}的实际大小，选择{ans}最合理。")
        math_question(u, "估测与实测", f"一条绳子长 {metres} 米，剪去 {cm} 厘米，还剩多少厘米？", metres*100-cm,
                      [metres-cm, metres*100+cm, metres*10-cm], f"先把 {metres} 米换成 {metres*100} 厘米，再减去 {cm} 厘米。", " 厘米", 3)

        u = "三 大数加与减（二）"
        a, b = 236+i*17, 148+i*13
        math_question(u, "三位数加法", f"{a}＋{b}＝？", a+b, [a+b-10, a+b+10, a-b],
                      f"相同数位对齐，从个位加起，{a}＋{b}={a+b}。")
        math_question(u, "三位数减法", f"{a+b}－{b}＝？", a, [a-10, a+10, b],
                      f"被减数 {a+b} 减去 {b}，差是 {a}。")
        math_question(u, "连加连减", f"{a}＋{b}－{100+i}＝？", a+b-100-i,
                      [a+b+100+i, a+b, a-b-100-i], f"按从左往右的顺序，先算 {a}＋{b}={a+b}，再减 {100+i}。")
        sold, left = 128+i*5, 96+i*4
        math_question(u, "加减应用", f"图书角原有 {a} 本书，借出 {sold} 本，又还回 {left} 本，现在有多少本？",
                      a-sold+left, [a+sold+left, a-sold-left, a+sold-left],
                      f"借出用减法，还回用加法：{a}－{sold}＋{left}={a-sold+left}。", " 本", 3)
        estimate = round(a, -1)+round(b, -1)
        add("math", u, "估算方法", 3, f"把 {a} 和 {b} 都看成最接近的整十数，估算 {a}＋{b} 的结果是多少？",
            str(estimate), numeric(estimate, [a+b, a+b-100, a+b+100]),
            f"把 {a} 看作 {round(a,-1)}，把 {b} 看作 {round(b,-1)}，估算为 {estimate}；准确值为 {a+b}。")

        u = "四 我们生活的空间（一）"
        directions = ["东", "南", "西", "北"]
        d = directions[i%4]
        left = directions[(i%4-1)%4]
        right = directions[(i%4+1)%4]
        add("math", u, "方向辨认", 2, f"面向{d}方站立，你的左面是什么方向？", left,
            [right, d, directions[(i%4+2)%4]], f"面向{d}方时，左手边是{left}方。")
        add("math", u, "相对方向", 1, f"小华从学校向{d}走回家，原路返回时应向什么方向走？",
            directions[(i%4+2)%4], [d, left, right], f"原路返回要走相反方向，{d}的相反方向是{directions[(i%4+2)%4]}。")
        places = ["图书馆", "体育馆", "教学楼", "校门"]
        p = places[i%4]
        add("math", u, "地图方向", 2, f"校园平面图按“上北下南，左西右东”绘制。{p}在操场的正上方，它在操场的哪一面？",
            "北", ["南", "西", "东"], "平面图上表示北，所以位于操场北面。")
        add("math", u, "位置描述", 2, f"小明在操场{d}侧，小红在操场{directions[(i%4+2)%4]}侧，两人分别位于操场的什么位置？",
            f"相对的两侧，小明在{d}侧", ["同一侧", f"相邻的两侧，小明在{left}侧", "无法根据方向判断"],
            f"{d}和{directions[(i%4+2)%4]}是一组相反方向，因此两人在操场相对两侧。")
        add("math", u, "路线选择", 3, f"从教室先向{d}走到操场，再向{right}走到图书角。若只描述第二段路，应向哪里走？",
            right, [left, d, directions[(i%4+2)%4]], f"第二段路线明确是向{right}走，与第一段方向区分开。")

        u = "五 认识图形"
        shapes = [("三角形", "3", ["4", "5", "6"]), ("四边形", "4", ["3", "5", "6"]),
                  ("五边形", "5", ["3", "4", "6"]), ("六边形", "6", ["3", "4", "5"])]
        shape, sides, wrong = shapes[i%4]
        add("math", u, "多边形的边", 1, f"一个{shape}有几条边？", sides, wrong, f"{shape}有{sides}条边。")
        add("math", u, "长方形特征", 2, "下面哪项同时符合长方形的特征？",
            "有4个直角，对边相等", ["4条边都相等，4个角不一定是直角", "只有一组对边相等", "有3个直角"],
            "长方形有4个直角，两组对边分别相等；正方形是特殊的长方形。")
        add("math", u, "正方形特征", 2, "下面关于正方形的说法，正确的是哪项？",
            "4条边都相等，4个角都是直角", ["对边相等，但邻边一定不相等", "只有2个直角", "正方形不是四边形"],
            "正方形是特殊的四边形，4条边相等，4个角都是直角。")
        add("math", u, "图形分类", 2, f"一个图形有{sides}条直直的边并且首尾相接，按边数应归为哪一类？",
            shape, [s[0] for s in shapes if s[0] != shape], f"由{sides}条直边围成的封闭图形是{shape}。")
        add("math", u, "图形拼组", 3, "用两个完全一样的三角形拼一拼，下面哪种图形一定拼不出来？",
            "只有一条边的封闭图形", ["四边形", "三角形", "不同的四边形"],
            "平面多边形至少有三条边，不可能拼成只有一条边的封闭图形。")

        u = "六 乘除法的应用（二）"
        a, b = 12+i*2, 3+i%6
        math_question(u, "两位数乘一位数", f"{a}×{b}＝？", a*b, [a*b-b, a*b+b, a+b],
                      f"把 {a} 分成整十数和一位数分别乘 {b}，再相加，得到 {a*b}。")
        product = a*b
        math_question(u, "除法意义", f"{product}÷{b}＝？", a, [a-1, a+1, b],
                      f"因为 {a}×{b}={product}，所以 {product}÷{b}={a}。")
        math_question(u, "乘法应用", f"每盒有 {a} 支彩笔，{b} 盒共有多少支？", product,
                      [a+b, product-a, product+b], f"求 {b} 个 {a} 是多少，用乘法 {a}×{b}={product}。", " 支")
        math_question(u, "除法应用", f"把 {product} 个苹果平均装进 {b} 个袋子，每袋多少个？", a,
                      [b, product-b, a+1], f"平均分用除法，{product}÷{b}={a}。", " 个")
        groups = next(d for d in range(2, 7) if a % d == 0)
        math_question(u, "乘除两步应用", f"有 {product} 本书，平均分给 {b} 个班，每班再把书平均放到 {groups} 层书架上，每层多少本？",
                      a//groups, [a, product, a+b],
                      f"先求每班 {product}÷{b}={a} 本，再求每层 {a}÷{groups}={a//groups} 本。")

        u = "七 认识小数"
        whole, tenth = 2+i, 1+i%8
        value = whole+tenth/10
        chinese_whole = ['零','一','二','三','四','五','六','七','八','九','十','十一'][whole]
        chinese_tenth = ['零','一','二','三','四','五','六','七','八','九'][tenth]
        add("math", u, "小数读写", 1, f"小数 {value:.1f} 读作什么？", f"{chinese_whole}点{chinese_tenth}",
            [f"{chinese_whole}点{['零','一','二','三','四','五','六','七','八','九'][tenth%9+1]}", f"{whole+1}点{tenth}", f"{whole+2}点{tenth}"],
            f"小数点读作“点”，{value:.1f} 的整数部分是 {whole}，十分位是 {tenth}。")
        other = value+0.1
        add("math", u, "小数比较", 2, f"比较 {value:.1f} ○ {other:.1f}，○中应填什么？", "＜", ["＞", "＝", "无法比较"],
            f"整数部分相同，十分位 {tenth}＜{tenth+1}，所以 {value:.1f}＜{other:.1f}。")
        sum_value = value+0.2
        add("math", u, "小数加法", 3, f"{value:.1f}＋0.2＝？", f"{sum_value:.1f}",
            [f"{value:.1f}", f"{value+0.3:.1f}", f"{value+1.2:.1f}"],
            f"相同数位对齐，十分位相加，{value:.1f}＋0.2={sum_value:.1f}。")
        subtract_tenth = (tenth-1)%3+1
        difference = value-subtract_tenth/10
        add("math", u, "小数减法", 3, f"{value:.1f} 元减去 0.{subtract_tenth} 元，还剩多少元？", f"{difference:.1f} 元",
            [f"{value:.1f} 元", f"{value+subtract_tenth/10:.1f} 元", f"{value+1:.1f} 元"],
            f"相同数位对齐，{value:.1f}－0.{subtract_tenth}={difference:.1f}，所以还剩 {difference:.1f} 元。")
        add("math", u, "小数意义", 2, f"{value:.1f} 中的数字 {tenth} 表示什么？", f"{tenth} 个 0.1",
            [f"{tenth} 个 1", f"{tenth} 个 0.01", f"{tenth} 个 10"], f"数字 {tenth} 在十分位上，表示 {tenth} 个 0.1。")

        u = "八 调查与记录"
        red, blue, green = 12+i, 9+i, 6+i
        add("math", u, "读取调查数据", 1, f"调查同学喜欢的颜色：红色 {red} 人，蓝色 {blue} 人，绿色 {green} 人。喜欢哪种颜色的人最多？",
            "红色", ["蓝色", "绿色", "一样多"], f"{red}＞{blue}＞{green}，喜欢红色的人最多。")
        math_question(u, "数据比较", f"喜欢红色的有 {red} 人，喜欢蓝色的有 {blue} 人，喜欢红色的比蓝色的多几人？", red-blue,
                      [red+blue, blue-red, red], f"求相差人数用减法：{red}－{blue}={red-blue}。", " 人")
        math_question(u, "数据合计", f"三项调查人数分别是 {red}、{blue}、{green}，一共有多少人参加调查？", red+blue+green,
                      [red+blue, red+blue+green-1, red+blue+green+1], f"把三项人数相加：{red}＋{blue}＋{green}={red+blue+green}。", " 人")
        add("math", u, "记录方法", 2, "调查全班最喜欢的水果时，怎样记录最便于后续统计？",
            "每人只选一项，并用画“正”字等方法逐人记录", ["只记录自己喜欢的水果", "凭感觉估计各项人数", "同一人重复选择并全部计入"],
            "明确调查对象和规则，一人一项、逐人记录，才能保证数据准确。")
        add("math", u, "根据数据决策", 3, f"班级准备购买一种颜色的队旗，调查结果是红色 {red} 人、蓝色 {blue} 人、绿色 {green} 人。若按最多人的选择决定，应怎样选？",
            "选择红色，因为得票最多", ["选择绿色，因为人数最少", "三种都不选", "只按组长个人喜好选择"],
            f"红色 {red} 人最多，按既定规则应选择红色。")

        u = "综合实践 记录我们的校园"
        math_question(u, "校园测量", f"小组测得一段校园小路长 {20+i} 米，另段长 {15+i} 米，两段共长多少米？", 35+2*i,
                      [20+i, 15+i, 5], f"求两段总长度用加法：{20+i}＋{15+i}={35+2*i}。", " 米")
        add("math", u, "测量方案", 2, "要测量操场一圈的长度，哪种做法最合理？",
            "明确起点和终点，沿操场边缘测量并记录单位", ["只测一条直线就当作一圈", "不记单位只写数字", "用铅笔长度估计后不记录过程"],
            "测量前要明确对象、路线、工具和单位，测量后如实记录。")
        add("math", u, "数据真实性", 2, "两次测量同一面墙，结果相差很大，首先应该怎样做？",
            "检查测量方法、工具和单位，再复测", ["直接选择较大的数", "取自己喜欢的数", "把两个结果都删掉"],
            "异常数据需要检查过程并复测，不能随意挑选。")
        math_question(u, "校园数据比较", f"测量花坛两条边，分别长 {8+i} 米和 {6+i} 米，长边比短边长多少米？", 2,
                      [14+2*i, 8+i, 6+i], f"求长度差用减法：{8+i}－{6+i}=2。", " 米")
        add("math", u, "记录表设计", 2, "制作校园测量记录表，下面哪组信息最完整？",
            "地点、测量项目、数据、单位、测量日期", ["只有一个数字", "地点和同学姓名，不需要数据", "数据和颜色，不需要单位"],
            "完整记录应能说明测了哪里、测什么、结果是多少以及使用什么单位。")

        u = "数学好玩 搭配中的学问"
        tops, bottoms = 2+i%3, 3+i%4
        math_question(u, "乘法原理", f"有 {tops} 件上衣和 {bottoms} 条裤子，每件上衣都可与每条裤子搭配，一共有多少种穿法？", tops*bottoms,
                      [tops+bottoms, tops*bottoms-1, tops*bottoms+1], f"每件上衣有 {bottoms} 种裤子可配，共 {tops}×{bottoms}={tops*bottoms} 种。", " 种")
        math_question(u, "有序列举", f"用数字 1、2、{3+i%7} 组成没有重复数字的两位数，一共能组成多少个？", 6,
                      [3, 4, 9], "十位有3种选法，个位再从剩下2个数字中选，共3×2=6个。", " 个")
        add("math", u, "不重复不遗漏", 2, "解决服装搭配问题时，哪种方法最容易做到不重复、不遗漏？",
            "固定一件上衣，依次与所有裤子搭配，再换下一件", ["想到哪组写哪组", "只记录最喜欢的一组", "重复写同一组凑数"],
            "按一定顺序列举或连线，能系统检查所有组合。")
        math_question(u, "路线搭配", f"从家到图书馆有 {tops} 条路，从图书馆到学校有 {bottoms} 条路，经过图书馆从家到学校共有多少种不同走法？", tops*bottoms,
                      [tops+bottoms, tops*bottoms-tops, tops*bottoms+bottoms], f"每段选择都可独立组合，共 {tops}×{bottoms}={tops*bottoms} 种。", " 种")
        add("math", u, "组合与排列", 3, "从甲、乙、丙三名同学中选两名做值日生，只考虑人选不考虑分工，有多少种选法？",
            "3种", ["6种", "2种", "9种"], "可列举为甲乙、甲丙、乙丙，共3种；分工不同时才会变成6种。")

        u = "综合实践 探索年月日的秘密"
        months = [1,3,5,7,8,10,12]
        m = months[i%7]
        add("math", u, "大月小月", 1, f"{m} 月有多少天？", "31天", ["30天", "29天", "28天"], f"{m} 月是大月，有31天。")
        small = [4,6,9,11][i%4]
        add("math", u, "小月认识", 1, f"下面关于 {small} 月的说法，正确的是哪项？", "有30天", ["有31天", "平年有28天", "闰年有29天"], f"{small} 月是小月，有30天。")
        year = 2024+i*4
        add("math", u, "闰年判断", 2, f"{year} 年是闰年还是平年？（该年份不是整百年）", "闰年，2月有29天", ["平年，2月有28天", "闰年，2月有30天", "平年，2月有29天"],
            f"{year} 能被4整除且不是整百年，所以是闰年，2月有29天。")
        math_question(u, "日期推算", f"某年 7 月 {10+i} 日开始实践活动，连续进行 5 天（含开始日），7 月几日结束？", 14+i,
                      [15+i, 13+i, 10+i], f"开始日也算第1天，结束日期为 {10+i}＋5－1={14+i} 日。", " 日")
        add("math", u, "季度与月份", 2, "一年有四个季度，第三季度包括哪三个月？",
            "7月、8月、9月", ["6月、7月、8月", "9月、10月、11月", "10月、11月、12月"], "每三个月为一个季度，第三季度是7月至9月。")

        u = "总复习"
        a, b, c = 25+i*2, 5+i%4, 4+i%3
        math_question(u, "综合计算", f"{a}＋{b}×{c}－{b}＝？", a+b*c-b,
                      [a+b*c, (a+b)*c-b, a+b+c], f"先算 {b}×{c}={b*c}，再从左往右算加减，得 {a+b*c-b}。", "", 3)
        group_count = 3+i%4
        total_students = b*group_count
        math_question(u, "综合应用", f"三年级有 {total_students} 名学生参加研学，每组 {b} 人，能分成多少组？", group_count,
                      [total_students+b, total_students-b, b], f"总人数÷每组人数=组数，{total_students}÷{b}={group_count}。", " 组")
        add("math", u, "单位综合", 2, "下面哪组换算完全正确？",
            "3米=300厘米，2千米=2000米", ["3米=30厘米，2千米=200米", "3厘米=300毫米，2米=20厘米", "3千米=30米，2厘米=200毫米"],
            "1米=100厘米，1千米=1000米，因此3米=300厘米，2千米=2000米。")
        add("math", u, "图形与方向", 2, "在平面图上，一个正方形花坛位于教学楼北面。花坛具有哪项特征？",
            "位于教学楼北面，且4条边相等", ["位于教学楼南面，且只有2条边", "位于教学楼东面，且4个角不是直角", "位置与图形特征无法同时描述"],
            "位置用方向描述；正方形4条边相等、4个角都是直角。")
        add("math", u, "数据与表达", 3, "解决一道实际问题后，哪种检查方式最可靠？",
            "核对条件、单位、计算过程，并判断结果是否符合实际", ["只看答案位数是否好看", "直接照抄同桌答案", "跳过检查"],
            "完整检查要覆盖题意、列式、单位、计算和结果合理性。")


# ENGLISH_DATA 在下方按单元定义，每单元 75 题。
ENGLISH_UNITS = [
    ("Unit 1 How do we feel?", "感受与问候", [
        ("happy","开心的","I am ___ today.","我今天很开心。"),("sad","伤心的","The boy is ___.","这个男孩很伤心。"),
        ("angry","生气的","She is ___ with the mess.","她因凌乱而生气。"),("tired","疲倦的","We are ___ after running.","跑步后我们很累。"),
        ("cold","冷的","I am ___. Close the window.","我很冷。关上窗户。"),("boy","男孩","The ___ is happy.","这个男孩很开心。"),
        ("girl","女孩","The ___ is my sister.","这个女孩是我的妹妹。"),("pencil","铅笔","Here is a ___.","这里有一支铅笔。"),
        ("evening","晚上","Good ___, Mum!","晚上好，妈妈！"),("again","再一次","Let's start ___.","让我们重新开始。"),
        ("fine","很好","I am ___, thank you.","我很好，谢谢。"),("not","不；没有","It is ___ cold.","天不冷。"),
        ("too","也","I am happy ___.","我也很开心。"),("find","找到","I can't ___ my pencil.","我找不到铅笔。"),
        ("start","开始","Let's ___ the lesson.","让我们开始上课。")],
     [("How are you?","I'm fine, thank you."),("How do you feel?","I'm happy."),("What should you say in the morning?","Good morning!"),("You are cold. What can you say?","Let's close the window."),("What should you say in the evening?","Good evening!")]),
    ("Unit 2 What's interesting about families?", "家庭与人物", [
        ("grandfather","爷爷；外公","This is my ___.","这是我的爷爷。"),("grandmother","奶奶；外婆","My ___ is kind.","我的奶奶很和蔼。"),
        ("father","爸爸","He is my ___.","他是我的爸爸。"),("mother","妈妈","She is my ___.","她是我的妈妈。"),
        ("sister","姐妹","This girl is my ___.","这个女孩是我的妹妹。"),("brother","兄弟","The boy is my ___.","这个男孩是我的弟弟。"),
        ("family","家庭","I have a big ___.","我有一个大家庭。"),("big","大的","My family is ___.","我的家庭很大。"),
        ("small","小的","My family is ___.","我的家庭很小。"),("who","谁","___ is he?","他是谁？"),
        ("twin","双胞胎之一","He is my ___ brother.","他是我的双胞胎兄弟。"),("he","他","___ is my father.","他是我的爸爸。"),
        ("she","她","___ is my mother.","她是我的妈妈。"),("my","我的","This is ___ family.","这是我的家庭。"),
        ("me","我（宾格）","This is my family and ___.","这是我的家人和我。")],
     [("Who is he?","He is my father."),("Who is she?","She is my mother."),("Is your family big?","Yes, it is."),("Who is your twin brother?","Pengpeng is my twin brother."),("How do you introduce your family?","This is my family.")]),
    ("Unit 3 What do we look like?", "外貌与身体", [
        ("head","头","Touch your ___.","摸摸你的头。"),("eye","眼睛","I have a big ___.","我有一只大眼睛。"),
        ("ear","耳朵","The rabbit has a long ___.","兔子有一只长耳朵。"),("mouth","嘴巴","Open your ___.","张开你的嘴。"),
        ("hair","头发","My ___ is short.","我的头发很短。"),("nose","鼻子","I have a small ___.","我有一个小鼻子。"),
        ("face","脸","I have a round ___.","我有一张圆脸。"),("haircut","发型","I like my new ___.","我喜欢我的新发型。"),
        ("long","长的","Her hair is ___.","她的头发很长。"),("short","短的","My hair is ___.","我的头发很短。"),
        ("round","圆的","His face is ___.","他的脸是圆的。"),("see","看见","I can ___ your eyes.","我能看见你的眼睛。"),
        ("chair","椅子","Sit on the ___.","坐在椅子上。"),("sit","坐","Please ___ down.","请坐下。"),
        ("great","很棒的","You look ___.","你看起来很棒。")],
     [("What do you look like?","I have big eyes and short hair."),("How is your hair?","It is short."),("What does 'You look great!' mean?","你看起来很棒！"),("What can you see with?","I can see with my eyes."),("What do you say before a haircut?","I like my haircut, Mum.")]),
    ("Unit 4 How do we have fun?", "活动与能力", [
        ("skip","跳绳","Let's ___ together.","让我们一起跳绳。"),("run","跑","I can ___ fast.","我能跑得快。"),
        ("fast","快地","Huihui can run ___.","慧慧能跑得很快。"),("help","帮助","I can ___ you.","我能帮助你。"),
        ("skateboard","滑滑板","Tingting can ___.","婷婷会滑滑板。"),("jump","跳","I can ___ high.","我能跳得高。"),
        ("child","孩子","The ___ is in the park.","这个孩子在公园里。"),("children","孩子们","The ___ want to skip.","孩子们想跳绳。"),
        ("park","公园","We play in the ___.","我们在公园里玩。"),("fun","有趣的","It is ___.","它很有趣。"),
        ("bring","带来","She can ___ some juice.","她能带来一些果汁。"),("get","取来","I can ___ some juice.","我能去取些果汁。"),
        ("want","想要","I ___ to run.","我想要跑步。"),("together","一起","Let's play ___.","让我们一起玩。"),
        ("can","能够","I ___ fly a kite.","我会放风筝。")],
     [("What can you do?","I can run fast."),("Can you fly a kite?","Yes, I can."),("How do we have fun?","We can skip together."),("What do the children want to do?","They want to skip."),("What can Tingting do?","She can skateboard.")]),
    ("Unit 5 What do we eat?", "食物与饮料", [
        ("milk","牛奶","I drink some ___.","我喝一些牛奶。"),("juice","果汁","Here is some ___.","这里有一些果汁。"),
        ("tomato","西红柿","This is a red ___.","这是一个红西红柿。"),("carrot","胡萝卜","I don't like ___.","我不喜欢胡萝卜。"),
        ("apple","苹果","Do you have an ___?","你有一个苹果吗？"),("banana","香蕉","The ___ is yellow.","这根香蕉是黄色的。"),
        ("grape","葡萄","This ___ is purple.","这颗葡萄是紫色的。"),("meat","肉","Do you like ___?","你喜欢肉吗？"),
        ("fish","鱼肉","I like ___ for dinner.","我晚餐喜欢吃鱼。"),("food","食物","We like this ___.","我们喜欢这种食物。"),
        ("fruit","水果","Here is some ___.","这里有一些水果。"),("vegetable","蔬菜","A carrot is a ___.","胡萝卜是一种蔬菜。"),
        ("cut","切","Let's ___ the fruit.","让我们切水果。"),("chicken","鸡肉","I like ___.","我喜欢鸡肉。"),
        ("some","一些","Can I have ___ milk?","我能喝些牛奶吗？")],
     [("Do you like apples?","Yes, I do."),("Do you like meat?","No, I don't."),("What do we eat?","We eat fruit and vegetables."),("What do you want to drink?","I want some juice."),("What can we do with the fruit?","Let's cut the fruit.")]),
    ("Unit 6 What do we like about small animals?", "小动物与特征", [
        ("squirrel","松鼠","That is a ___.","那是一只松鼠。"),("rabbit","兔子","The ___ has long ears.","这只兔子有长耳朵。"),
        ("cat","猫","I see a ___.","我看见一只猫。"),("mouse","老鼠","The ___ is small.","这只老鼠很小。"),
        ("mice","老鼠（复数）","Two ___ are under the box.","两只老鼠在盒子下面。"),("dog","狗","The ___ is cute.","这只狗很可爱。"),
        ("frog","青蛙","The ___ can jump.","青蛙会跳。"),("tail","尾巴","It has a long ___.","它有一条长尾巴。"),
        ("animal","动物","A rabbit is an ___.","兔子是一种动物。"),("cute","可爱的","The cat is ___.","这只猫很可爱。"),
        ("bug","虫子","Frogs eat bad ___.","青蛙吃害虫。"),("bad","坏的；有害的","They eat ___ bugs.","它们吃害虫。"),
        ("has","有（第三人称单数）","It ___ small ears.","它有小耳朵。"),("they","它们","___ have long tails.","它们有长尾巴。"),
        ("that","那；那个","___ is a rabbit.","那是一只兔子。")],
     [("What is that?","That's a squirrel."),("What does the rabbit have?","It has long ears."),("What do frogs eat?","They eat bad bugs."),("Why do you like the cat?","It's cute."),("What is the plural of mouse?","Mice.")]),
    ("Unit 7 What do we know about weather?", "天气与自然", [
        ("sunny","晴朗的","It is ___ today.","今天晴朗。"),("sun","太阳","The ___ is in the sky.","太阳在天空中。"),
        ("windy","刮风的","It is ___ outside.","外面刮风。"),("wind","风","I like the ___.","我喜欢风。"),
        ("rainy","下雨的","It is ___ today.","今天下雨。"),("rain","雨","I don't like the ___.","我不喜欢雨。"),
        ("cloudy","多云的","The sky is ___.","天空多云。"),("cloud","云","The ___ is white.","这朵云是白色的。"),
        ("weather","天气","How is the ___ today?","今天天气怎样？"),("today","今天","It is hot ___.","今天很热。"),
        ("hat","帽子","My ___ is on the chair.","我的帽子在椅子上。"),("plant","植物","The ___ needs water.","这株植物需要水。"),
        ("hot","热的","It is very ___.","天气非常热。"),("very","非常","I am ___ happy.","我非常开心。"),
        ("harvest","丰收","Farmers have a big ___.","农民获得大丰收。")],
     [("How's the weather today?","It's sunny."),("What do you wear on a hot day?","I wear a hat."),("Do you like the rain?","No, I don't."),("What colour is a cloud?","It is white."),("What happens in a big harvest?","Farmers get lots of crops.")]),
    ("Unit 8 Why do we like birthdays?", "生日与分享", [
        ("cake","蛋糕","We have a birthday ___.","我们有一个生日蛋糕。"),("candle","蜡烛","Light the birthday ___.","点燃生日蜡烛。"),
        ("noodles","面条","I eat birthday ___.","我吃生日面。"),("doll","洋娃娃","This ___ is for you.","这个洋娃娃送给你。"),
        ("card","卡片","Here is a birthday ___.","这里有一张生日卡片。"),("gift","礼物","Come and see your ___.","来看看你的礼物。"),
        ("box","盒子","What is in the ___?","盒子里有什么？"),("book","书","I have a new ___.","我有一本新书。"),
        ("ball","球","The ___ is my gift.","这个球是我的礼物。"),("share","分享","I ___ my toys.","我分享我的玩具。"),
        ("love","喜爱","I ___ this toy plane.","我喜爱这架玩具飞机。"),("come","来","___ and see my gift.","来看看我的礼物。"),
        ("meet","会面","The family ___ Ms Li.","这家人与李老师见面。"),("afternoon","下午","Good ___, everyone!","大家下午好！"),
        ("lovely","可爱的","What a ___ picture!","多么可爱的一幅画！")],
     [("What do you say on a friend's birthday?","Happy birthday!"),("What's in your box?","Some books, balls and kites."),("What can you do with your toys?","I can share my toys."),("What do you do with birthday candles?","We light the candles."),("Why do we like birthdays?","We can meet family and share gifts.")]),
]


def generate_english() -> None:
    for unit_index, (unit, topic, words, dialogues) in enumerate(ENGLISH_UNITS):
        other_words = [w for u, _, ws, _ in ENGLISH_UNITS if u != unit for w in ws]
        # 1. 英译中：每词创设一个题干，15题。
        for word, cn, sentence, translation in words:
            distractors = []
            for _, cn2, _, _ in other_words:
                if cn2 != cn and cn2 not in distractors:
                    distractors.append(cn2)
                if len(distractors) == 3:
                    break
            add("english", unit, "词汇理解", 1, f"单词或短语“{word}”的中文意思是什么？", cn, distractors,
                f"{word} 意为“{cn}”。课本语境例句：{sentence.replace('___', word)}")
        # 2. 中译英：考查准确回忆，15题。
        for word, cn, sentence, translation in words:
            distractors = []
            for word2, _, _, _ in other_words:
                if word2 != word and word2 not in distractors:
                    distractors.append(word2)
                if len(distractors) == 3:
                    break
            add("english", unit, "词汇运用", 2, f"在“{unit}”的学习内容中，“{cn}”对应的英文是哪一项？", word, distractors,
                f"“{cn}”应写作 {word}，注意拼写。")
        # 3. 语境选词：每词放入不同句子，15题。
        for word, cn, sentence, translation in words:
            distractors = []
            for word2, _, _, _ in other_words:
                if word2 != word and word2 not in distractors:
                    distractors.append(word2)
                if len(distractors) == 3:
                    break
            add("english", unit, "语境选词", 2, f"根据句意选择恰当的词：{sentence}（{translation}）", word, distractors,
                f"句意提示应选择 {word}（{cn}）。完整句子：{sentence.replace('___', word)}")
        # 4. 分类辨析：3个本单元主题词与1个明确的异类词，15题。
        for i in range(15):
            group = [words[(i+j)%15] for j in range(3)]
            outsider = other_words[(unit_index*17+i*7)%len(other_words)]
            add("english", unit, "分类辨析", 2,
                f"在“{group[0][0]}、{group[1][0]}、{group[2][0]}、{outsider[0]}”中，结合“{topic}”主题，哪一项与其余三项不同类？",
                outsider[0], [w[0] for w in group],
                f"{group[0][0]}、{group[1][0]}、{group[2][0]} 都属于本单元“{topic}”学习内容；{outsider[0]} 意为“{outsider[1]}”，属于其他主题。")
        # 5. 交际与理解：5个核心问答，各用3种中文任务情境，15题。
        for i in range(15):
            question, answer = dialogues[i%5]
            mode = i//5
            prompts = [
                f"在课本交际情境中，遇到“{question}”应怎样回应？",
                f"补全对话：—{question} —____",
                f"判断哪一句与“{question}”构成最恰当的一问一答。",
            ]
            # 干扰项须与问句明显无关，避免把同样合理的肯定/否定回答当作错误项。
            wrong = ["It's a pencil.", "I'm nine years old.", "Good night!"]
            add("english", unit, "交际应答", 2 if mode < 2 else 3, prompts[mode], answer, wrong,
                f"“{question}”对应的恰当表达是“{answer}”，要同时关注问句含义和应答逻辑。")


def generate_chinese() -> None:
    from chinese_data import BASICS, LESSONS
    # 17类语文基础与阅读技能，每类20个独立材料，共340题。
    for unit, kp, entries in BASICS:
        for entry in entries:
            stem, correct, wrong, explanation = entry
            add("chinese", unit, kp, 2 if kp not in {"易错读音", "形近字辨析"} else 1,
                stem, correct, wrong, explanation)
    # 26课，每课5个核心知识，以两种不同认知角度各考一次，共260题。
    for lesson in LESSONS:
        title, facts = lesson[0], lesson[1]
        unit = f"课文阅读《{title}》"
        for idx, (kp, correct, wrong, explanation) in enumerate(facts):
            add("chinese", unit, kp, 2, f"学习《{title}》后，关于“{kp}”，下列理解正确的是哪项？",
                correct, wrong, explanation)
            add("chinese", unit, kp, 3, f"下列哪一项最能体现《{title}》中“{kp}”的学习要点？",
                correct, list(reversed(wrong)), explanation)


def main() -> None:
    generate_math()
    generate_english()
    generate_chinese()
    assert Counter(q["subject"] for q in QUESTIONS) == {"math": 600, "english": 600, "chinese": 600}
    ids = [q["id"] for q in QUESTIONS]
    assert len(ids) == len(set(ids))
    stems = [q["stem"] for q in QUESTIONS]
    duplicates = [s for s, n in Counter(stems).items() if n > 1]
    assert not duplicates, f"重复题干：{duplicates[:5]}"
    OUT.write_text(json.dumps({
        "bankVersion": "1.0.0-shenzhen-2025",
        "curriculum": "深圳小学三年级上册：语文人教版、数学北师大版、英语沪教版（深圳，2024修订）",
        "notice": "原创练习题，按教材单元与常见测评题型编制，不复制商业真题。",
        "questions": QUESTIONS,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
    print(f"Generated {len(QUESTIONS)} questions: {Counter(q['subject'] for q in QUESTIONS)}")
    print(f"Output: {OUT}")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
