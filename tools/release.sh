#!/bin/sh
# Build the candidate on a clean commit. All later operations reuse its saved manifest.
set -eu
die() { echo "$*" >&2; exit 1; }
[ "$#" -eq 1 ] || die "usage: $0 build|publish|verify"
case "$1" in build|publish|verify) ;; *) die "usage: $0 build|publish|verify" ;; esac
cd "$(dirname "$0")/.."
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)
case "$version" in ''|*[!0-9A-Za-z.-]*) die "invalid versionName" ;; esac
tag=v$version
apk=herald-$version.apk
manifest=out/herald-$version.release.json
mkdir -p out
exec 9>out/.release.lock
flock -n 9 || die "another release operation is active"
stage=$(mktemp -d out/.release.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 1' HUP INT TERM

# apksigner reads only the signed APK, including when checking a downloaded release.
certificate() {
    ${DOCKER:-sudo docker} run --rm --network none -v "$PWD/$1:/candidate.apk:ro" \
        herald-build:36-m3-1.11.1 apksigner verify --print-certs /candidate.apk > "$stage/certificate"
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$stage/certificate"
}
digest() { sha256sum "$1" > "$stage/digest" && cut -d ' ' -f 1 "$stage/digest"; }

if [ "$1" = build ]; then
    [ -z "$(git status --porcelain)" ] || die "build requires a clean commit"
    for file in "out/$apk" "out/$apk.sha256" "$manifest"; do
        [ ! -e "$file" ] || die "candidate already exists: $file"
    done
    commit=$(git rev-parse HEAD)
    tools/build.sh
    cp out/herald.apk "$stage/$apk"
    hash=$(digest "$stage/$apk")
    cert=$(certificate "$stage/$apk")
    python3 - "$commit" "$version" "$hash" "$cert" > "$stage/release.json" <<'PY'
import json
import re
import sys

commit, version, apk_hash, cert_hash = sys.argv[1:]
if not all(re.fullmatch("[0-9a-f]{64}", value) for value in (apk_hash, cert_hash)):
    sys.exit("missing APK or certificate digest")
print(json.dumps(dict(commit=commit, version=version, apk_sha256=apk_hash, cert_sha256=cert_hash)))
PY
    printf '%s  %s\n' "$hash" "$apk" > "$stage/$apk.sha256"
    mv "$stage/$apk" "$stage/$apk.sha256" out/
    mv "$stage/release.json" "$manifest"
    exit 0
fi

# Validate types/lengths before using the recorded commit as a Git argument.
commit=$(python3 - "$manifest" "$version" <<'PY'
import json
import re
import sys

with open(sys.argv[1]) as source:
    record = json.load(source)
for key, length in (("commit", 40), ("apk_sha256", 64), ("cert_sha256", 64)):
    if not re.fullmatch(f"[a-f0-9]{{{length}}}", record[key]):
        sys.exit(f"invalid {key}")
if record["version"] != sys.argv[2]:
    sys.exit("manifest version mismatch")
print(record["commit"])
PY
)
check_file() {
    hash=$(digest "$1")
    cert=$(certificate "$1")
    python3 - "$manifest" "$hash" "$cert" <<'PY'
import json
import sys

with open(sys.argv[1]) as source:
    record = json.load(source)
if (record["apk_sha256"], record["cert_sha256"]) != tuple(sys.argv[2:]):
    sys.exit("APK or signing certificate differs from the release manifest")
PY
}
remote_commit() {
    git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}" > "$stage/tag"
    # Annotated tags have a peeled commit in the second row.
    awk 'END {print $1}' "$stage/tag"
}
if [ "$1" = publish ]; then
    cp "out/$apk" "out/$apk.sha256" "$stage/"
    check_file "$stage/$apk"
    printf '%s  %s\n' "$hash" "$apk" > "$stage/expected"
    cmp "$stage/expected" "$stage/$apk.sha256"
    remote=$(remote_commit)
    if [ -z "$remote" ]; then
        git push origin "$commit:refs/tags/$tag"
    fi
    [ "$(remote_commit)" = "$commit" ] || die "release tag has a different target"
    gh release create "$tag" "$stage/$apk" "$stage/$apk.sha256" --verify-tag \
        --title "Herald $version" --notes "See CHANGELOG.md for version $version."
else
    gh release download "$tag" --pattern "$apk" --dir "$stage"
    check_file "$stage/$apk"
    [ "$(remote_commit)" = "$commit" ] || die "release tag differs from the recorded commit"
    echo "Verified $apk at $commit"
fi
