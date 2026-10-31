#!/usr/bin/env python3
"""找出 YAML 重複鍵（PyYAML 預設靜默覆蓋，Spring Boot 的 SnakeYAML 會直接拒絕啟動）。"""
import sys, glob, yaml

class Strict(yaml.SafeLoader): pass
def construct_mapping(loader, node, deep=False):
    seen = set()
    for k, _ in node.value:
        key = loader.construct_object(k, deep=deep)
        if key in seen:
            raise yaml.YAMLError(f"duplicate key '{key}' at line {k.start_mark.line + 1}")
        seen.add(key)
    return yaml.SafeLoader.construct_mapping(loader, node, deep)
Strict.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)

bad = 0
for f in sorted(glob.glob("backend/*/src/main/resources/application*.yml") + glob.glob("docker-compose*.yml")):
    try:
        with open(f) as fh:
            for _ in yaml.load_all(fh, Loader=Strict): pass
    except yaml.YAMLError as e:
        print(f"::error file={f}::{e}"); bad += 1
print("checked" if not bad else f"{bad} file(s) with problems")
sys.exit(1 if bad else 0)
