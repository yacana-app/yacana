#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

# Copies the release signing key and its passwords from keystore.properties into the
# repository's secrets, for the Release workflow (CONTRIBUTING.md, Releases).
# Only the key's owner runs it, once, or again after changing the key. It never prints a value:
# each one goes to `gh secret set` through a pipe, so it stays out of the screen, the shell
# history and the process list.
#
# Usage: bash tools/set_release_secrets.sh [keystore.properties]
# Needs the GitHub CLI signed in with admin rights on the repository (`gh auth status`).

set -euo pipefail

repo="yacana-app/yacana"
root="$(cd "$(dirname "$0")/.." && pwd)"
properties="${1:-$root/keystore.properties}"

if [ ! -f "$properties" ]; then
  echo "No $properties: it holds storeFile, storePassword, keyAlias and keyPassword" >&2
  exit 1
fi

declare -A values
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    '' | '#'* | '!'*) continue ;;
  esac
  key="${line%%=*}"
  value="${line#*=}"
  value="${value#"${value%%[![:space:]]*}"}"
  # A backslash is written twice in a properties file, as in C:\\keys\\yacana.jks.
  values["${key// /}"]="${value//\\\\/\\}"
done < "$properties"

for key in storeFile storePassword keyAlias keyPassword; do
  if [ -z "${values[$key]:-}" ]; then
    echo "$properties has no $key" >&2
    exit 1
  fi
done

# As Gradle does: a relative storeFile is relative to the project's folder.
store="${values[storeFile]}"
case "$store" in
  /* | [A-Za-z]:*) ;;
  *) store="$root/$store" ;;
esac
if [ ! -f "$store" ]; then
  echo "The key file in storeFile is not there: $store" >&2
  exit 1
fi

gh auth status > /dev/null

base64 -w 0 "$store" | gh secret set KEYSTORE_BASE64 --repo "$repo"
printf '%s' "${values[storePassword]}" | gh secret set KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "${values[keyAlias]}" | gh secret set KEY_ALIAS --repo "$repo"
printf '%s' "${values[keyPassword]}" | gh secret set KEY_PASSWORD --repo "$repo"

echo "Done. The repository's secrets now:"
gh secret list --repo "$repo"
