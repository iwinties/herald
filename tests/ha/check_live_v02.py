"""Validate T11's two physical HA readings without loading Home Assistant."""

import json
import re
import sys
from copy import deepcopy
from pathlib import Path


def require(condition, rule):
    if not condition:
        raise ValueError(rule)


def check(records):
    require(
        len(records) == 2 and all(isinstance(r, dict) for r in records),
        "expected exactly two records",
    )
    details = {}
    for record in records:
        key = record.get("key")
        require(
            key in ("V15/a", "V15/b") and key not in details,
            "missing or duplicate V15 key",
        )
        require(
            record.get("source") == "physical" and record.get("ok") is True,
            f"{key} physical success",
        )
        detail = record.get("detail")
        require(isinstance(detail, dict), f"{key} missing detail")
        details[key] = detail
    a, b = details["V15/a"], details["V15/b"]
    calls = a.get("calls")
    require(isinstance(calls, list) and len(calls) == 2, "V15/a requires two calls")
    ids, jobs = set(), set()
    for call in [*calls, b.get("call")]:
        require(isinstance(call, dict), "missing call")
        rid, job = call.get("request_id"), call.get("job")
        require(
            isinstance(rid, str) and rid.strip() and rid not in ids,
            "missing or repeated request id",
        )
        require(
            isinstance(job, str) and job.strip() and job not in jobs,
            "missing or repeated job",
        )
        require(
            call.get("event_job") == job and call.get("result") == "played",
            "missing matching played event",
        )
        ids.add(rid)
        jobs.add(job)
    ops = a.get("clip_ops")
    require(isinstance(ops, list) and len(ops) == 3, "V15/a clip sequence length")
    for op, (method, status) in zip(ops, [("GET", 404), ("PUT", 200), ("GET", 200)]):
        require(
            isinstance(op, dict)
            and op.get("method") == method
            and op.get("status") == status,
            "V15/a clip method or status",
        )
        clip = op.get("key")
        require(
            isinstance(clip, str) and clip.strip() and clip == ops[0].get("key"),
            "V15/a clip key",
        )
    require(
        isinstance(b.get("category"), str)
        and re.fullmatch(r"t11_[a-f0-9]{4}", b["category"]),
        "V15/b category",
    )
    require(b.get("marker") == "seen", "V15/b missing marker")
    require(type(b.get("cleared")) is int and b["cleared"] >= 1, "V15/b category clear")
    for field in ("category_deleted", "revoked"):
        answer = b.get(field)
        require(isinstance(answer, dict) and answer.get("ok") is True, f"V15/b {field}")


def self_test():
    def call(i):
        return dict(
            request_id=f"request-{i}",
            job=f"job-{i}",
            event_job=f"job-{i}",
            result="played",
        )

    a = dict(
        calls=[call(1), call(2)],
        clip_ops=[
            dict(method=m, status=s, key="fixture")
            for m, s in [("GET", 404), ("PUT", 200), ("GET", 200)]
        ],
    )
    b = dict(
        call=call(3),
        marker="seen",
        category="t11_abcd",
        cleared=1,
        category_deleted={"ok": True},
        revoked={"ok": True},
    )
    valid = [
        dict(key=k, source="physical", ok=True, detail=d)
        for k, d in [("V15/a", a), ("V15/b", b)]
    ]
    check(valid)
    for path, value in [
        ((0, "detail", "calls", 0, "result"), "error"),
        ((1, "detail", "call", "request_id"), "request-1"),
        ((1, "detail", "call"), {**call(1), "request_id": "request-3"}),
        ((1, "detail", "call", "event_job"), "job-2"),
        ((0, "detail", "clip_ops", 1, "status"), 404),
        ((0, "detail", "clip_ops", 2, "key"), "other"),
        ((1, "detail", "cleared"), None),
        ((1, "detail", "category_deleted"), None),
        ((1, "detail", "marker"), None),
        ((1, "key"), "V15/a"),
        ((0, "ok"), False),
        ((1, "detail", "revoked"), {"ok": False}),
    ]:
        records = deepcopy(valid)
        target = records
        for index in path[:-1]:
            target = target[index]
        target[path[-1]] = value
        try:
            check(records)
        except ValueError:
            continue
        raise AssertionError(f"invalid fixture accepted at {path}")
    print("ok self-test")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_live_v02.py FILE | --self-test")
    if sys.argv[1] == "--self-test":
        self_test()
    else:
        try:
            check(
                [
                    json.loads(line)
                    for line in Path(sys.argv[1]).read_text().splitlines()
                ]
            )
        except (OSError, ValueError) as exc:
            raise SystemExit(str(exc)) from None
        print("ok V15/a V15/b")
