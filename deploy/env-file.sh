# Helpers for the deploy scripts: read settings from a dotenv file without
# executing it. Sourcing .env would run any shell in it (a stray `$(...)` or
# a pasted command) and would also clobber whatever it happens to define, so
# only the named keys are picked out, as plain text.
#
# Usage (from a script in deploy/):
#   . "$ROOT/env-file.sh"
#   ENV_FILE="$(find_env_file "$ROOT")"
#   load_env_vars "$ENV_FILE" POSTGRES_DB POSTGRES_USER

# Prints the .env the stack uses: the repo root's, else one next to the scripts.
# Prints nothing when neither exists.
find_env_file() {
  local root="$1"
  if [[ -n "${ENV_FILE:-}" ]]; then
    printf '%s\n' "$ENV_FILE"
  elif [[ -f "$root/../.env" ]]; then
    printf '%s\n' "$root/../.env"
  elif [[ -f "$root/.env" ]]; then
    printf '%s\n' "$root/.env"
  fi
}

# Sets each named variable from FILE unless it is already set in the
# environment (which wins, like it does for docker compose). Understands
# KEY=value, `export KEY=value`, single or double quotes, and a trailing
# ` # comment` on unquoted values. The last assignment of a key wins.
load_env_vars() {
  local file="$1"
  shift
  [[ -n "$file" && -f "$file" ]] || return 0
  local key line value
  for key in "$@"; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -n "${!key+x}" ]] && continue
    line="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$file" | tail -n 1 || true)"
    [[ -n "$line" ]] || continue
    value="${line#*=}"
    if [[ "$value" =~ ^\"(.*)\"[[:space:]]*$ ]]; then
      value="${BASH_REMATCH[1]}"
    elif [[ "$value" =~ ^\'(.*)\'[[:space:]]*$ ]]; then
      value="${BASH_REMATCH[1]}"
    else
      value="${value%%[[:space:]]#*}"
      value="${value%"${value##*[![:space:]]}"}"
    fi
    printf -v "$key" '%s' "$value"
  done
}
