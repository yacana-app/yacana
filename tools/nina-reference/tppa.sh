#!/usr/bin/env bash
# The Three Point Polar Alignment plugin's own error for cases of three plate solves: reads
# cases from stdin and prints each one's altitude and azimuth error, as TppaReference.cs says.
#
# Windows only, with N.I.N.A. 3 and the plugin installed, the .NET 8 runtimes and a C# compiler
# from Visual Studio or its Build Tools. From Git Bash:
#
#   tools/nina-reference/tppa.sh < shared/domain/src/commonTest/resources/tppa-cases.txt
#
# NINA_DIR, TPPA_DIR and CSC override where N.I.N.A., the plugin and the compiler are.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
nina="${NINA_DIR:-/c/Program Files/N.I.N.A. - Nighttime Imaging 'N' Astronomy}"
tppa="${TPPA_DIR:-$LOCALAPPDATA/NINA/Plugins/3.0.0/Three Point Polar Alignment}"
csc="${CSC:-$(ls -d "/c/Program Files (x86)/Microsoft Visual Studio/"*/*/MSBuild/Current/Bin/Roslyn/csc.exe 2>/dev/null | tail -1)}"
out="$here/build/tppa"
[ -f "$nina/NINA.Astrometry.dll" ] || { echo "N.I.N.A. not found in $nina (set NINA_DIR)" >&2; exit 1; }
[ -f "$tppa/NINA.Plugins.PolarAlignment.dll" ] || { echo "The plugin is not in $tppa (set TPPA_DIR)" >&2; exit 1; }
[ -f "$csc" ] || { echo "No C# compiler found (set CSC to csc.exe)" >&2; exit 1; }

if [ ! -f "$out/TppaReference.dll" ] || [ "$here/TppaReference.cs" -nt "$out/TppaReference.dll" ]; then
    mkdir -p "$out"
    # N.I.N.A.'s managed assemblies and the plugin's, as references.
    for dir in "$nina" "$tppa"; do
        DIR_WIN="$(cygpath -w "$dir")" powershell.exe -NoProfile -Command \
            'Get-ChildItem -LiteralPath $env:DIR_WIN -Filter *.dll | ForEach-Object { try { [void][Reflection.AssemblyName]::GetAssemblyName($_.FullName); "/reference:""" + $_.FullName + """" } catch {} }' \
            | tr -d '\r'
    done > "$out/references.rsp"
    "$csc" -nologo -noconfig -nostdlib -target:exe -langversion:latest -nowarn:1701,1702 \
        "@$(cygpath -w "$out/references.rsp")" -out:"$(cygpath -w "$out/TppaReference.dll")" "$(cygpath -w "$here/TppaReference.cs")" >&2
    # N.I.N.A. loads SOFA, NOVAS and its ephemeris from External, next to the program.
    mkdir -p "$out/External/x64"
    cp -r "$nina/External/x64/NOVAS" "$nina/External/x64/SOFA" "$out/External/x64/"
    cp -r "$nina/External/JPLEPH" "$out/External/"
    # Its scripts create a database of the program's own, with UT1 − UTC, instead of N.I.N.A.'s.
    cp -r "$nina/Database" "$out/"
    cat > "$out/TppaReference.runtimeconfig.json" <<'JSON'
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

mkdir -p "$out/localappdata/NINA"
LOCALAPPDATA="$(cygpath -w "$out/localappdata")"     "${DOTNET:-/c/Program Files/dotnet/dotnet.exe}" "$(cygpath -w "$out/TppaReference.dll")" "$(cygpath -w "$nina")" "$(cygpath -w "$tppa")"
