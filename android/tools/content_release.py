#!/usr/bin/env python3
"""원격 콘텐츠(#31) 배포 파일 만들기.

묶음 JSON 하나를 받아 Supabase Storage 공개 버킷에 올릴 두 파일을 만든다.
  <out>/packs/content-v<contentVersion>.json   묶음 (입력을 그대로 복사)
  <out>/manifest/<appVersion>.json             이 앱 버전이 볼 manifest (버전, 최소 앱 버전, 경로, SHA-256)

사용:
  python3 tools/content_release.py --pack core/src/main/resources/content/bundled.json --app-version 1
  python3 tools/content_release.py --pack <수정한 묶음>.json --app-version 1 --out dist/content

올리기: Supabase 대시보드 → Storage → 버킷 pleospet-content → dist/content 안의 폴더 구조 그대로 업로드(덮어쓰기).
규칙(core ContentUpdate 주석): contentVersion 은 1씩 올린다. 아이템은 빼지 않는다. 심사 중인 앱 버전의 manifest 는 바꾸지 않는다.
앱 쪽 값 검사(ContentValidator)는 여기서 못 하므로, 올리기 전에 `./gradlew :core:test` 가 통과하는 묶음만 쓴다.
"""
import argparse, hashlib, json, os, shutil, sys


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pack", required=True, help="묶음 JSON 파일")
    ap.add_argument("--app-version", type=int, required=True, help="이 manifest 를 볼 앱 versionCode")
    ap.add_argument("--out", default="dist/content", help="출력 폴더 (기본 dist/content)")
    a = ap.parse_args()

    raw = open(a.pack, "rb").read()
    try:
        pack = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        print(f"묶음을 읽을 수 없음: {e}", file=sys.stderr); return 2
    for k in ("contentVersion", "minAppVersion", "missions", "items", "characters", "scoreBands"):
        if k not in pack:
            print(f"묶음에 {k} 가 없음", file=sys.stderr); return 2
    cv, min_app = int(pack["contentVersion"]), int(pack["minAppVersion"])
    if min_app > a.app_version:
        print(f"minAppVersion {min_app} 이 앱 버전 {a.app_version} 보다 큼. 이 앱은 받지 않는다", file=sys.stderr); return 2

    path = f"packs/content-v{cv}.json"
    os.makedirs(os.path.join(a.out, "packs"), exist_ok=True)
    os.makedirs(os.path.join(a.out, "manifest"), exist_ok=True)
    shutil.copyfile(a.pack, os.path.join(a.out, path))
    manifest = {"contentVersion": cv, "minAppVersion": min_app, "path": path, "sha256": hashlib.sha256(raw).hexdigest()}
    mpath = os.path.join(a.out, "manifest", f"{a.app_version}.json")
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2); f.write("\n")
    print(f"묶음   {os.path.join(a.out, path)}  ({len(raw)} bytes)")
    print(f"manifest {mpath}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    print("\n올릴 곳: <base>/manifest/%d.json, <base>/%s   (base = https://<ref>.supabase.co/storage/v1/object/public/pleospet-content)" % (a.app_version, path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
