#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="${CODEGRAPH_ENGINE_DIR:-$APP_ROOT/.engine}"
ENGINE_REPOSITORY="${CODEGRAPH_ENGINE_REPOSITORY:-https://github.com/praha-poseidon/code-graph-engine.git}"
ENGINE_REF="${CODEGRAPH_ENGINE_REF:-master}"

# Engine is intentionally private. Keep the read-only token in the caller's
# environment/CI secret; never put it in a repository URL or a committed file.
ENGINE_TOKEN="${CODEGRAPH_ENGINE_TOKEN:-}"

if [[ -z "$ENGINE_TOKEN" ]]; then
  echo "CODEGRAPH_ENGINE_TOKEN is required to build against the private Engine repository" >&2
  exit 3
fi

git_with_engine_auth() {
  if [[ -z "$ENGINE_TOKEN" ]]; then
    git "$@"
  else
    git -c "http.extraheader=AUTHORIZATION: bearer $ENGINE_TOKEN" "$@"
  fi
}

if [[ ! -d "$ENGINE_DIR/.git" ]]; then
  git_with_engine_auth clone --branch "$ENGINE_REF" --depth 1 "$ENGINE_REPOSITORY" "$ENGINE_DIR"
else
  git_with_engine_auth -C "$ENGINE_DIR" fetch --depth 1 origin "$ENGINE_REF"
  git -C "$ENGINE_DIR" checkout --detach FETCH_HEAD
fi

npm --prefix "$APP_ROOT/frontend" ci
rm -rf "$APP_ROOT/src/main/resources/static"
npm --prefix "$APP_ROOT/frontend" run build

mvn -B -f "$ENGINE_DIR/pom.xml" \
  -pl code-graph-spring-boot-starter \
  -am -DskipTests install
mvn -B -f "$APP_ROOT/pom.xml" test package
