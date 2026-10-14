#!/usr/bin/env bash
# Reads a sky cache folder, such as the one the app exports, with N.I.N.A.'s own code:
# a line per photo as N.I.N.A. loads it, and its offline sky map around the first one, as a PNG.
#
# Windows only, with N.I.N.A. 3 installed, the .NET 8 runtimes (Microsoft.NETCore.App and
# Microsoft.WindowsDesktop.App) and a C# compiler from Visual Studio or its Build Tools. Run it
# from Git Bash:
#
#   tools/nina-reference/cache.sh <cache folder> <map.png>
#
# NINA_DIR and CSC override where N.I.N.A. and the compiler are.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
nina="${NINA_DIR:-/c/Program Files/N.I.N.A. - Nighttime Imaging 'N' Astronomy}"
csc="${CSC:-$(ls -d "/c/Program Files (x86)/Microsoft Visual Studio/"*/*/MSBuild/Current/Bin/Roslyn/csc.exe 2>/dev/null | tail -1)}"
out="$here/build/cache"
[ -f "$nina/NINA.WPF.Base.dll" ] || { echo "N.I.N.A. not found in $nina (set NINA_DIR)" >&2; exit 1; }
[ -f "$csc" ] || { echo "No C# compiler found (set CSC to csc.exe)" >&2; exit 1; }
[ $# -eq 2 ] || { echo "usage: $0 <cache folder> <map.png>" >&2; exit 2; }

if [ ! -f "$out/CacheReference.dll" ] || [ "$here/CacheReference.cs" -nt "$out/CacheReference.dll" ]; then
    mkdir -p "$out"
    # Every managed assembly in N.I.N.A.'s folder, which carries its own .NET 8, as references.
    NINA_WIN="$(cygpath -w "$nina")" powershell.exe -NoProfile -Command \
        'Get-ChildItem -LiteralPath $env:NINA_WIN -Filter *.dll | ForEach-Object { try { [void][Reflection.AssemblyName]::GetAssemblyName($_.FullName); "/reference:""" + $_.FullName + """" } catch {} }' \
        | tr -d '\r' > "$out/references.rsp"
    "$csc" -nologo -noconfig -nostdlib -target:exe -langversion:latest -nowarn:1701,1702 \
        "@$(cygpath -w "$out/references.rsp")" -out:"$(cygpath -w "$out/CacheReference.dll")" "$(cygpath -w "$here/CacheReference.cs")" >&2
    cat > "$out/CacheReference.runtimeconfig.json" <<'JSON'
{
  "runtimeOptions": {
    "tfm": "net8.0",
    "frameworks": [
      { "name": "Microsoft.NETCore.App", "version": "8.0.0" },
      { "name": "Microsoft.WindowsDesktop.App", "version": "8.0.0" }
    ]
  }
}
JSON
fi

"${DOTNET:-/c/Program Files/dotnet/dotnet.exe}" "$(cygpath -w "$out/CacheReference.dll")" "$(cygpath -w "$nina")" "$(cygpath -w "$1")" "$(cygpath -w "$2")"
