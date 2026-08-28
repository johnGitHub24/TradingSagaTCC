#!/usr/bin/env bash
# portable-env.sh — Honor OS JAVA_HOME only (never embed / never invent JDK paths)

set -euo pipefail

MIN_JAVA_VERSION="${MIN_JAVA_VERSION:-21}"

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8
if [[ "${JAVA_TOOL_OPTIONS:-}" != *"file.encoding=UTF-8"* ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS# }"
  export JAVA_TOOL_OPTIONS
fi

java_major_version() {
  local out
  out="$("$1" -version 2>&1 | head -n 1)"
  if [[ "$out" =~ version\ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ "$out" =~ version\ \"1\.([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo 0
  fi
}

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "WARN: JAVA_HOME not in environment — rely on PATH / Gradle toolchain." >&2
  return 0 2>/dev/null || true
fi

JAVA_HOME="${JAVA_HOME%/}"
export JAVA_HOME

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "WARN: JAVA_HOME=$JAVA_HOME has no bin/java" >&2
  return 0 2>/dev/null || true
fi

ver="$(java_major_version "$JAVA_HOME/bin/java")"
if [[ "$ver" -lt "$MIN_JAVA_VERSION" ]]; then
  echo "WARN: JAVA_HOME is Java $ver (want ${MIN_JAVA_VERSION}+)" >&2
  return 0 2>/dev/null || true
fi

export PATH="$JAVA_HOME/bin:${PATH#"$JAVA_HOME/bin:"}"
echo "JAVA_HOME=$JAVA_HOME (Java $ver) UTF-8" >&2
